package files

import (
	"archive/zip"
	"bytes"
	"errors"
	"io"
	"reflect"
	"strings"
	"testing"
	"time"
)

var (
	pngBytes = []byte("\x89PNG\r\n\x1a\n rest of an image")
	pdfBytes = []byte("%PDF-1.5\n%%EOF")
)

func TestValidPathKeepsTheSandboxRulesAndReservesTheProjectsOwnNames(t *testing.T) {
	for _, p := range []string{"refs.bib", "figures/plot 1.png", "a/b/c/d/e/f.tex", "Main.tex.bak", "chapters/main.tex", "out/output.pdf"} {
		if !ValidPath(p) {
			t.Errorf("%q should be valid", p)
		}
	}
	for _, p := range []string{"", "main.tex", "MAIN.TEX", "output.pdf", "../x.tex", "/etc/x", ".hidden", "a/.git/x", "a//b", "a/b/c/d/e/f/g.tex", "a\\b.tex", "naïve.tex", strings.Repeat("a", 201)} {
		if ValidPath(p) {
			t.Errorf("%q should be refused", p)
		}
	}
}

func TestClassifyStoresTextAsTextAndChecksBinaryMagic(t *testing.T) {
	f, err := classify("refs.bib", []byte("\xef\xbb\xbf@article{a,}"))
	if err != nil || f.Kind != KindText || f.Text != "@article{a,}" || f.ContentType != "text/x-bibtex" {
		t.Fatalf("%+v %v", f, err)
	}
	f, err = classify("fig/A.PNG", pngBytes)
	if err != nil || f.Kind != KindBinary || f.ContentType != "image/png" || f.size() != len(pngBytes) {
		t.Fatalf("%+v %v", f, err)
	}
	cases := map[string]struct {
		path string
		data []byte
		want error
	}{
		"html posing as png": {"x.png", []byte("<html>"), errContentLooks},
		"unknown extension":  {"x.exe", []byte("MZ"), errUnsupported},
		"no extension":       {"Makefile", []byte("all:"), errUnsupported},
		"binary in a .tex":   {"x.tex", []byte("a\x00b"), errBadText},
		"invalid utf-8":      {"x.tex", []byte("\xff\xfe"), errBadText},
		"text over 1 MiB":    {"x.tex", bytes.Repeat([]byte("a"), MaxTextBytes+1), errTooLargeEntry},
		"file over 5 MiB":    {"x.pdf", append([]byte("%PDF-"), make([]byte, MaxFileBytes)...), errTooLargeEntry},
		"reserved":           {"main.tex", []byte("x"), errReservedPath},
		"invalid path":       {"../x.tex", []byte("x"), errInvalidPath},
	}
	for name, c := range cases {
		if _, err := classify(c.path, c.data); !errors.Is(err, c.want) {
			t.Errorf("%s: got %v, want %v", name, err, c.want)
		}
	}
}

func TestNewTextNeedsAKnownTextExtension(t *testing.T) {
	if f, err := newText("chapters/intro.tex", "Hello"); err != nil || f.Kind != KindText || f.size() != 5 {
		t.Fatalf("%+v %v", f, err)
	}
	for p, want := range map[string]error{"x.png": errUnsupported, "output.pdf": errReservedPath, "a//b.tex": errInvalidPath} {
		if _, err := newText(p, ""); !errors.Is(err, want) {
			t.Errorf("%s: %v", p, err)
		}
	}
	if _, err := newText("x.tex", "\x07"); !errors.Is(err, errBadText) {
		t.Fatal(err)
	}
	if _, err := newText("x.tex", strings.Repeat("a", MaxTextBytes+1)); !errors.Is(err, errTooLargeEntry) {
		t.Fatal(err)
	}
}

func TestParentsAndWithin(t *testing.T) {
	if got := parents("a/b/c.tex"); !reflect.DeepEqual(got, []string{"a", "a/b"}) {
		t.Fatal(got)
	}
	if got := parents("c.tex"); got != nil {
		t.Fatal(got)
	}
	if !within("A/b", "a") || !within("a", "A") || within("ab/c", "a") {
		t.Fatal("within")
	}
}

func TestFitsCountsBytesAndEntries(t *testing.T) {
	u := Usage{Bytes: MaxProjectBytes - 10, Entries: MaxEntries - 1}
	if !fits(u, []NewFile{{Kind: KindText, Text: "0123456789"}}) {
		t.Fatal("exactly full should fit")
	}
	if fits(u, []NewFile{{Kind: KindText, Text: "01234567890"}}) || fits(u, []NewFile{{Kind: KindFolder}, {Kind: KindFolder}}) {
		t.Fatal("over the limit should not fit")
	}
}

func zipOf(t *testing.T, entries map[string]string) []byte {
	t.Helper()
	var buf bytes.Buffer
	w := zip.NewWriter(&buf)
	for name, content := range entries {
		f, err := w.Create(name)
		if err != nil {
			t.Fatal(err)
		}
		if _, err := io.WriteString(f, content); err != nil {
			t.Fatal(err)
		}
	}
	if err := w.Close(); err != nil {
		t.Fatal(err)
	}
	return buf.Bytes()
}

func TestZipBundleHoldsMainFilesFoldersAndThePDF(t *testing.T) {
	data, err := zipBundle(Bundle{
		Title: "Paper", Main: `\documentclass{article}`,
		Files:  []Stored{{Path: "fig", Kind: KindFolder}, {Path: "fig/a.png", Kind: KindBinary, Data: pngBytes}, {Path: "refs.bib", Kind: KindText, Data: []byte("@a{}")}},
		Output: pdfBytes,
	}, time.Date(2026, 10, 9, 0, 0, 0, 0, time.UTC))
	if err != nil {
		t.Fatal(err)
	}
	r, err := zip.NewReader(bytes.NewReader(data), int64(len(data)))
	if err != nil {
		t.Fatal(err)
	}
	got := map[string]string{}
	for _, f := range r.File {
		rc, _ := f.Open()
		b, _ := io.ReadAll(rc)
		_ = rc.Close()
		got[f.Name] = string(b)
	}
	want := map[string]string{"main.tex": `\documentclass{article}`, "fig/": "", "fig/a.png": string(pngBytes), "refs.bib": "@a{}", "output.pdf": string(pdfBytes)}
	if !reflect.DeepEqual(got, want) {
		t.Fatalf("%v", got)
	}
}

func TestUnzipProjectFindsMainAndUnwrapsASingleFolder(t *testing.T) {
	p, err := unzipProject(zipOf(t, map[string]string{
		"paper-main/":                   "",
		"paper-main/paper.tex":          `\documentclass{article}\input{sections/intro}`,
		"paper-main/sections/":          "",
		"paper-main/sections/intro.tex": "Hello",
		"paper-main/fig.png":            string(pngBytes),
		"paper-main/.DS_Store":          "junk",
		"__MACOSX/paper-main/._fig.png": "junk",
		"paper-main/notes.docx":         "PK",
		"paper-main/build.log":          "log",
	}))
	if err != nil {
		t.Fatal(err)
	}
	if !strings.HasPrefix(p.Main, `\documentclass`) {
		t.Fatal(p.Main)
	}
	var paths []string
	for _, f := range p.Files {
		paths = append(paths, f.Path+":"+f.Kind)
	}
	if want := []string{"fig.png:binary", "sections:folder", "sections/intro.tex:text"}; !reflect.DeepEqual(paths, want) {
		t.Fatalf("%v", paths)
	}
	if !reflect.DeepEqual(p.Skipped, []string{"build.log", "notes.docx"}) {
		t.Fatalf("%v", p.Skipped)
	}
}

func TestUnzipProjectPrefersMainTexAndRefusesAmbiguity(t *testing.T) {
	p, err := unzipProject(zipOf(t, map[string]string{"main.tex": "A", "other.tex": `\documentclass{x}`}))
	if err != nil || p.Main != "A" || len(p.Files) != 1 || p.Files[0].Path != "other.tex" {
		t.Fatalf("%+v %v", p, err)
	}
	if _, err := unzipProject(zipOf(t, map[string]string{"a.tex": `\documentclass{x}`, "b.tex": `\documentclass{y}`})); !errors.Is(err, errNoMain) {
		t.Fatal(err)
	}
	if _, err := unzipProject(zipOf(t, map[string]string{"sub/main.tex": "x", "readme.md": "x"})); !errors.Is(err, errNoMain) {
		t.Fatal(err)
	}
	if _, err := unzipProject(zipOf(t, map[string]string{"main.tex": strings.Repeat("a", maxMainRunes+1)})); !errors.Is(err, errMainLarge) {
		t.Fatal(err)
	}
	if _, err := unzipProject([]byte("not a zip")); !errors.Is(err, errNotZip) {
		t.Fatal(err)
	}
	p, err = unzipProject(zipOf(t, map[string]string{"main.tex": "x", "Refs.bib": "a", "refs.bib": "b"}))
	if err != nil || len(p.Files) != 1 || len(p.Skipped) != 1 {
		t.Fatalf("a case-insensitive duplicate is skipped: %+v %v", p, err)
	}
}

func TestUnzipProjectBoundsWhatItUnpacks(t *testing.T) {
	entries := map[string]string{"main.tex": "x"}
	for i := 0; i <= maxZipEntries; i++ {
		entries[strings.Repeat("d", 1+i/26)+string(rune('a'+i%26))+".txt"] = ""
	}
	if _, err := unzipProject(zipOf(t, entries)); !errors.Is(err, errZipTooLarge) {
		t.Fatalf("too many entries: %v", err)
	}
	// Compresses to almost nothing, unpacks past the budget.
	bomb := map[string]string{"main.tex": "x", "a.txt": strings.Repeat("a", maxZipBytes/2+1), "b.txt": strings.Repeat("b", maxZipBytes/2+1)}
	if _, err := unzipProject(zipOf(t, bomb)); !errors.Is(err, errZipTooLarge) {
		t.Fatalf("zip bomb: %v", err)
	}
	full := map[string]string{"main.tex": "x"}
	for i := 0; i <= MaxEntries; i++ {
		full[strings.Repeat("e", 1+i/26)+string(rune('a'+i%26))+".txt"] = ""
	}
	if _, err := unzipProject(zipOf(t, full)); !errors.Is(err, errImportFull) {
		t.Fatalf("more entries than a project holds: %v", err)
	}
}

func TestImportTitleAndDownloadName(t *testing.T) {
	cases := map[[2]string]string{
		{"", "My Paper.zip"}:          "My Paper",
		{"  Thesis ", "x.zip"}:        "Thesis",
		{"", ""}:                      "Imported project",
		{"", `C:\Users\me\draft.zip`}: "draft",
	}
	for in, want := range cases {
		if got, ok := importTitle(in[0], in[1]); !ok || got != want {
			t.Errorf("%v: %q %v", in, got, ok)
		}
	}
	if _, ok := importTitle("a\x07b", ""); ok {
		t.Fatal("control characters are refused")
	}
	if _, ok := importTitle(strings.Repeat("a", 256), ""); ok {
		t.Fatal("over 255 characters is refused")
	}
	for in, want := range map[string]string{"Quantum: dots / wells?": "Quantum dots wells", "***": "project", "Ünïcode title": "Ünïcode title", "..x..": "x"} {
		if got := downloadName(in); got != want {
			t.Errorf("%q -> %q, want %q", in, got, want)
		}
	}
	if got := downloadName(strings.Repeat("a", 200)); len(got) != 80 {
		t.Fatalf("%d", len(got))
	}
}
