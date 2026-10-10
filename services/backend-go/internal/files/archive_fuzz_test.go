package files

import (
	"archive/zip"
	"bytes"
	"slices"
	"strings"
	"testing"
	"unicode/utf8"
)

func fuzzZip(t testing.TB, entries map[string]string) []byte {
	var buf bytes.Buffer
	w := zip.NewWriter(&buf)
	for name, body := range entries {
		f, err := w.Create(name)
		if err != nil {
			t.Fatal(err)
		}
		if _, err := f.Write([]byte(body)); err != nil {
			t.Fatal(err)
		}
	}
	if err := w.Close(); err != nil {
		t.Fatal(err)
	}
	return buf.Bytes()
}

// FuzzUnzipProject feeds arbitrary bytes, and zips with hostile names, to the
// .zip import: it must never panic, and whatever it accepts must be a project
// the store can hold (valid paths, valid text, within the limits).
func FuzzUnzipProject(f *testing.F) {
	doc := `\documentclass{article}\begin{document}x\end{document}`
	f.Add(fuzzZip(f, map[string]string{"main.tex": doc, "sec/intro.tex": "hi", "refs.bib": "@a{b,}"}))
	f.Add(fuzzZip(f, map[string]string{"paper/main.tex": doc, "paper/../../etc/passwd.tex": "x"}))
	f.Add(fuzzZip(f, map[string]string{"main.tex": doc, "/abs.tex": "x", `a\b.tex`: "x", "output.pdf": "%PDF"}))
	f.Add(fuzzZip(f, map[string]string{"MAIN.TEX": "\xef\xbb\xbf" + doc, "main.tex/": "", "x\x00.tex": "x"}))
	f.Add([]byte("PK\x03\x04 not really a zip"))
	f.Fuzz(func(t *testing.T, archive []byte) {
		out, err := unzipProject(archive)
		if err != nil {
			return
		}
		if !utf8.ValidString(out.Main) || !validText(out.Main) {
			t.Fatalf("main.tex holds invalid text")
		}
		used, seen := 0, map[string]bool{}
		for _, file := range out.Files {
			if !ValidPath(file.Path) || strings.HasPrefix(file.Path, "/") || slices.Contains(strings.Split(file.Path, "/"), "..") {
				t.Fatalf("accepted unsafe path %q", file.Path)
			}
			if seen[strings.ToLower(file.Path)] {
				t.Fatalf("accepted %q twice", file.Path)
			}
			seen[strings.ToLower(file.Path)] = true
			if file.Kind == KindText && !validText(file.Text) {
				t.Fatalf("accepted invalid text in %q", file.Path)
			}
			used += file.size()
		}
		if used > MaxProjectBytes || len(out.Files) > MaxEntries {
			t.Fatalf("accepted %d files, %d bytes: over the project limits", len(out.Files), used)
		}
	})
}
