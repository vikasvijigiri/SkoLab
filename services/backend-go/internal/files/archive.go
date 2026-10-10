package files

import (
	"archive/zip"
	"bytes"
	"errors"
	"io"
	"net/http"
	"path"
	"sort"
	"strings"
	"time"
	"unicode"
	"unicode/utf8"

	"github.com/gin-gonic/gin"
)

const (
	// maxZipEntries and maxZipBytes bound what an import unpacks, whatever
	// the archive's own headers claim (a zip bomb lies in them).
	maxZipEntries = 500
	maxZipBytes   = MaxProjectBytes + 1<<20
	maxMainRunes  = 100_000 // internal/document's limit for main.tex
)

var (
	errNotZip      = errors.New("not a zip archive")
	errZipTooLarge = errors.New("archive unpacks too large")
	errNoMain      = errors.New("no main file")
	errMainLarge   = errors.New("main file too large")
	errImportFull  = errors.New("project too large")
)

// zipBundle writes a project as a .zip: main.tex, every file and folder,
// and output.pdf when there is one.
func zipBundle(b Bundle, at time.Time) ([]byte, error) {
	var buf bytes.Buffer
	w := zip.NewWriter(&buf)
	add := func(name string, data []byte, method uint16) error {
		f, err := w.CreateHeader(&zip.FileHeader{Name: name, Method: method, Modified: at})
		if err != nil {
			return err
		}
		_, err = f.Write(data)
		return err
	}
	if err := add(MainPath, []byte(b.Main), zip.Deflate); err != nil {
		return nil, err
	}
	for _, f := range b.Files {
		var err error
		switch f.Kind {
		case KindFolder:
			_, err = w.CreateHeader(&zip.FileHeader{Name: f.Path + "/", Modified: at})
		case KindText:
			err = add(f.Path, f.Data, zip.Deflate)
		default: // images and PDFs are compressed already
			err = add(f.Path, f.Data, zip.Store)
		}
		if err != nil {
			return nil, err
		}
	}
	if b.Output != nil {
		if err := add(OutputPath, b.Output, zip.Store); err != nil {
			return nil, err
		}
	}
	if err := w.Close(); err != nil {
		return nil, err
	}
	return buf.Bytes(), nil
}

// imported is a project unpacked from a .zip.
type imported struct {
	Main    string
	Files   []NewFile
	Skipped []string
}

type zipEntry struct {
	name   string
	folder bool
	data   []byte
}

// readZip unpacks every entry, reading no more than maxZipBytes in total.
func readZip(archive []byte) ([]zipEntry, error) {
	r, err := zip.NewReader(bytes.NewReader(archive), int64(len(archive)))
	if err != nil {
		return nil, errNotZip
	}
	if len(r.File) > maxZipEntries {
		return nil, errZipTooLarge
	}
	var entries []zipEntry
	budget := int64(maxZipBytes)
	for _, f := range r.File {
		name := strings.ReplaceAll(f.Name, `\`, "/")
		if f.FileInfo().IsDir() || strings.HasSuffix(name, "/") {
			entries = append(entries, zipEntry{name: strings.TrimSuffix(name, "/"), folder: true})
			continue
		}
		rc, err := f.Open()
		if err != nil {
			return nil, errNotZip
		}
		data, err := io.ReadAll(io.LimitReader(rc, budget+1))
		_ = rc.Close()
		if err != nil {
			return nil, errNotZip
		}
		budget -= int64(len(data))
		if budget < 0 {
			return nil, errZipTooLarge
		}
		entries = append(entries, zipEntry{name: name, data: data})
	}
	return entries, nil
}

// skippable is what an archive carries that is not the project: macOS
// resource forks, hidden files, and folders made only of those.
func skippable(name string) bool {
	for _, segment := range strings.Split(name, "/") {
		if segment == "__MACOSX" || strings.HasPrefix(segment, ".") {
			return true
		}
	}
	return false
}

// commonRoot is the one folder every entry sits in ("paper-main/" in a
// GitHub download), or "" when files sit at the top.
func commonRoot(entries []zipEntry) string {
	root := ""
	for _, e := range entries {
		if skippable(e.name) {
			continue
		}
		top, _, nested := strings.Cut(e.name, "/")
		if !nested && !e.folder {
			return ""
		}
		if root == "" {
			root = top
		} else if root != top {
			return ""
		}
	}
	return root
}

// unzipProject turns a .zip into main.tex and the project's other files:
// main.tex at the top, or else the only top-level .tex with \documentclass.
func unzipProject(archive []byte) (imported, error) {
	entries, err := readZip(archive)
	if err != nil {
		return imported{}, err
	}
	if root := commonRoot(entries); root != "" {
		for i := range entries {
			entries[i].name = strings.TrimPrefix(strings.TrimPrefix(entries[i].name, root), "/")
		}
	}
	sort.Slice(entries, func(i, j int) bool { return entries[i].name < entries[j].name })

	var out imported
	mainIndex := -1
	var candidates []int
	for i, e := range entries {
		if e.folder || strings.Contains(e.name, "/") || extension(e.name) != "tex" {
			continue
		}
		if strings.EqualFold(e.name, MainPath) {
			mainIndex = i
			break
		}
		if bytes.Contains(e.data, []byte(`\documentclass`)) {
			candidates = append(candidates, i)
		}
	}
	if mainIndex < 0 && len(candidates) == 1 {
		mainIndex = candidates[0]
	}
	if mainIndex < 0 {
		return imported{}, errNoMain
	}
	main := string(bytes.TrimPrefix(entries[mainIndex].data, []byte("\xef\xbb\xbf")))
	if utf8.RuneCountInString(main) > maxMainRunes || !validText(main) {
		return imported{}, errMainLarge
	}
	out.Main = main

	seen := map[string]bool{}
	bytesUsed, count := 0, 0
	for i, e := range entries {
		if i == mainIndex || e.name == "" || skippable(e.name) {
			continue
		}
		key := strings.ToLower(e.name)
		if seen[key] {
			out.Skipped = append(out.Skipped, e.name)
			continue
		}
		var f NewFile
		if e.folder {
			if !ValidPath(e.name) {
				out.Skipped = append(out.Skipped, e.name+"/")
				continue
			}
			f = NewFile{Path: e.name, Kind: KindFolder}
		} else {
			var err error
			if f, err = classify(e.name, e.data); err != nil {
				out.Skipped = append(out.Skipped, e.name)
				continue
			}
		}
		seen[key] = true
		bytesUsed += f.size()
		count++
		out.Files = append(out.Files, f)
	}
	if bytesUsed > MaxProjectBytes || count > MaxEntries {
		return imported{}, errImportFull
	}
	if out.Skipped == nil {
		out.Skipped = []string{}
	}
	return out, nil
}

func importError(c *gin.Context, err error) {
	switch {
	case errors.Is(err, errNoMain):
		fail(c, http.StatusBadRequest, "no_main_file",
			"Put main.tex at the top of the .zip, or a single .tex file with \\documentclass")
	case errors.Is(err, errMainLarge):
		fail(c, http.StatusBadRequest, "main_too_large", "The main file must be UTF-8 text of at most 100,000 characters")
	case errors.Is(err, errZipTooLarge), errors.Is(err, errImportFull):
		fail(c, http.StatusRequestEntityTooLarge, "project_full", "The project may hold at most 10 MB in 200 files and folders")
	default:
		fail(c, http.StatusBadRequest, "invalid_archive", "The file is not a .zip archive")
	}
}

// importTitle is the new workspace's title: the one sent, else the
// archive's name, validated like any title.
func importTitle(title, archiveName string) (string, bool) {
	title = strings.TrimSpace(title)
	if title == "" {
		base := path.Base(strings.ReplaceAll(archiveName, `\`, "/"))
		title = strings.TrimSpace(strings.TrimSuffix(base, path.Ext(base)))
	}
	if title == "" || title == "." || title == "/" {
		title = "Imported project"
	}
	if utf8.RuneCountInString(title) > 255 || !utf8.ValidString(title) || strings.IndexFunc(title, unicode.IsControl) >= 0 {
		return "", false
	}
	return title, true
}

// downloadName is a title made safe as a file name.
func downloadName(title string) string {
	var b strings.Builder
	space := false
	for _, r := range title {
		switch {
		case unicode.IsLetter(r) || unicode.IsDigit(r) || r == '-' || r == '_' || r == '.':
			if space && b.Len() > 0 {
				b.WriteByte(' ')
			}
			space = false
			b.WriteRune(r)
		default:
			space = true
		}
		if utf8.RuneCountInString(b.String()) >= 80 {
			break
		}
	}
	name := strings.Trim(b.String(), ". ")
	if name == "" {
		return "project"
	}
	return name
}
