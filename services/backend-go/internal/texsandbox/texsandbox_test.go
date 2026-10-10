package texsandbox

import (
	"context"
	"encoding/base64"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strings"
	"testing"
	"time"
)

func TestFindForbiddenCatchesFileAndShellPrimitives(t *testing.T) {
	cases := []string{
		`\openin5=/proc/self/environ`,
		`\newread\f`,
		`\read5 to \x`,
		`\immediate\write18{id}`,
		`\newwrite\o`,
		`\input|ls`,
		`\input{|ls}`,
		`\include{|ls}`,
		`\verbatiminput{a}`,
		`\lstinputlisting{a}`,
	}
	for _, snippet := range cases {
		if FindForbidden(snippet) == "" {
			t.Errorf("expected %q to be forbidden", snippet)
		}
	}
}

func TestFindForbiddenAllowsOrdinaryConstructs(t *testing.T) {
	cases := []string{
		"Hello $x^2$",
		`\usepackage[utf8]{inputenc}`,
		`\includegraphics{fig}`,
		`\section{Read}`,
		// Project files: kpathsea's openin_any=p confines these reads.
		`\input{chapters/intro}`,
		`\include{appendix}`,
		`\InputIfFileExists{x}{}{}`,
	}
	for _, snippet := range cases {
		if got := FindForbidden(snippet); got != "" {
			t.Errorf("expected %q to be allowed, forbidden matched %q", snippet, got)
		}
	}
}

func TestCompileRefusesForbiddenSourceWithoutStartingAProcess(t *testing.T) {
	dir := t.TempDir()
	result := Compile(context.Background(), "definitely-not-a-real-binary", dir,
		`\documentclass{article}\begin{document}\openin5=/etc/passwd\end{document}`, nil)
	if result.Status != "error" {
		t.Fatalf("status = %q, want error", result.Status)
	}
	if len(result.Errors) == 0 || !strings.Contains(result.Errors[0], "Unsupported construct") {
		t.Fatalf("errors = %v", result.Errors)
	}
}

func TestSandboxEnvCarriesNoServerSecrets(t *testing.T) {
	t.Setenv("DATABASE_URL", "leak-me")
	t.Setenv("INTERNAL_API_TOKEN", "leak-me-too")
	env := sandboxEnv(t.TempDir())
	joined := strings.Join(env, "\x00")
	if strings.Contains(joined, "leak-me") {
		t.Fatalf("sandbox env leaked a secret: %v", env)
	}
	var sawOpenIn, sawOpenOut bool
	for _, kv := range env {
		sawOpenIn = sawOpenIn || kv == "openin_any=p"
		sawOpenOut = sawOpenOut || kv == "openout_any=p"
	}
	if !sawOpenIn || !sawOpenOut {
		t.Fatalf("expected kpathsea paranoid mode in env: %v", env)
	}
}

func TestSlotsAcquireTimesOutWhenSaturated(t *testing.T) {
	slots := NewSlots(1)
	if err := slots.Acquire(time.Second); err != nil {
		t.Fatalf("first acquire should succeed: %v", err)
	}
	start := time.Now()
	err := slots.Acquire(50 * time.Millisecond)
	if err != ErrBusy {
		t.Fatalf("expected ErrBusy, got %v", err)
	}
	if elapsed := time.Since(start); elapsed > time.Second {
		t.Fatalf("acquire blocked too long: %v", elapsed)
	}
}

func TestCancelledAdmissionDoesNotConsumeASlot(t *testing.T) {
	slots := NewSlots(1)
	if err := slots.Acquire(time.Second); err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	if err := slots.AcquireContext(ctx, time.Hour); err != context.Canceled {
		t.Fatalf("cancelled admission: %v", err)
	}
	slots.Release()
	if err := slots.Acquire(time.Second); err != nil {
		t.Fatal("slot was lost after cancellation")
	}
}

func TestScrubPathsRemovesWorkDir(t *testing.T) {
	dir := filepath.Join(t.TempDir(), "skolab-tex-abc123")
	log := "error in " + filepath.Join(dir, "main.tex") + " at line 3"
	scrubbed := scrubPaths(log, dir)
	if strings.Contains(scrubbed, dir) {
		t.Fatalf("work dir leaked into scrubbed log: %q", scrubbed)
	}
}

// ── real pdflatex (skipped where no TeX is installed) ───────────────────────

func findPdflatex(t *testing.T) string {
	t.Helper()
	path, err := exec.LookPath("pdflatex")
	if err != nil {
		t.Skip("pdflatex not installed")
	}
	return path
}

func TestRealCompileReadsNoServerFiles(t *testing.T) {
	engine := findPdflatex(t)
	dir := t.TempDir()
	secretDir := t.TempDir()
	secretPath := filepath.Join(secretDir, "secret.txt")
	if err := os.WriteFile(secretPath, []byte("SUPER-SECRET-TOKEN-12345"), 0o600); err != nil {
		t.Fatal(err)
	}
	src := `\documentclass{article}\begin{document}\input{` +
		filepath.ToSlash(secretPath) + `}\end{document}`
	// \input is allowed for project files; kpathsea's openin_any=p is what
	// refuses an absolute path.
	result := Compile(context.Background(), engine, dir, src, nil)
	if result.Status != "error" {
		t.Fatalf("status = %q, want error (kpathsea must refuse the absolute read)", result.Status)
	}
	if strings.Contains(result.Log, "SUPER-SECRET") {
		t.Fatalf("secret leaked into compile log")
	}
	if result.PDFBase64 != "" {
		t.Fatalf("a refused compile must not produce a PDF")
	}
}

// The keyword denylist is a fast first refusal, not the security boundary:
// TeX can spell any primitive without naming it (\csname, catcodes). These
// sources slip past FindForbidden on purpose, so the test proves the real
// controls -- kpathsea's paranoid openin/openout and -no-shell-escape --
// still refuse every read, write and command.
func TestRealCompileContainsDenylistBypasses(t *testing.T) {
	engine := findPdflatex(t)
	secretPath := filepath.ToSlash(filepath.Join(t.TempDir(), "secret.txt"))
	// Reading the file prints the marker into the log: compiled PDFs are
	// compressed, so a typeset secret would not be visible in the output.
	if err := os.WriteFile(secretPath, []byte(`\message{SUPER-SECRET-TOKEN-12345}`), 0o600); err != nil {
		t.Fatal(err)
	}
	outside := t.TempDir()
	marker := filepath.ToSlash(filepath.Join(outside, "pwned"))
	doc := func(body string) string {
		return `\documentclass{article}\begin{document}` + body + `x\end{document}`
	}
	sources := map[string]string{
		"csname absolute read":  doc(`\csname input\endcsname{` + secretPath + `}`),
		"catcode absolute read": doc("\\catcode`\\@=0 @input " + secretPath + " "),
		"csname parent read":    doc(`\csname input\endcsname{../../../../../../..` + secretPath + `}`),
		"csname pipe read":      doc(`\csname input\endcsname{|cat ` + secretPath + `}`),
		"csname shell escape":   doc(`\immediate\csname write\endcsname18{touch ` + marker + `}`),
		"csname absolute write": doc(`\csname newwrite\endcsname\f\immediate\csname openout\endcsname\f=` +
			marker + `.tex \immediate\csname write\endcsname\f{x}\immediate\closeout\f `),
	}
	for name, src := range sources {
		t.Run(name, func(t *testing.T) {
			if hit := FindForbidden(src); hit != "" {
				t.Fatalf("bypass no longer bypasses the denylist (%q); rewrite it", hit)
			}
			result := Compile(context.Background(), engine, t.TempDir(), src, nil)
			pdf, _ := base64.StdEncoding.DecodeString(result.PDFBase64)
			if strings.Contains(result.Log, "SUPER-SECRET") || strings.Contains(string(pdf), "SUPER-SECRET") {
				t.Fatal("secret leaked")
			}
			for _, path := range []string{marker, marker + ".tex"} {
				if _, err := os.Stat(path); err == nil {
					t.Fatalf("sandbox wrote %s", path)
				}
			}
		})
	}
}

func TestRealCompileProducesAPDFWithoutLeakingPaths(t *testing.T) {
	engine := findPdflatex(t)
	dir := t.TempDir()
	result := Compile(context.Background(), engine, dir,
		`\documentclass{article}\begin{document}Hello, world.\end{document}`, nil)
	if result.Status != "compiled" {
		t.Fatalf("status = %q, want compiled; log=%s", result.Status, result.Log)
	}
	pdf, err := base64.StdEncoding.DecodeString(result.PDFBase64)
	if err != nil {
		t.Fatalf("pdf_base64 did not decode: %v", err)
	}
	if !strings.HasPrefix(string(pdf), "%PDF-") {
		t.Fatalf("decoded output is not a PDF")
	}
	if strings.Contains(result.Log, dir) {
		t.Fatalf("work dir path leaked into log: %s", result.Log)
	}
}

func TestRealRunawaySourceIsKilledAtTheTimeout(t *testing.T) {
	engine := findPdflatex(t)
	dir := t.TempDir()
	started := time.Now()
	result := Compile(context.Background(), engine, dir,
		`\documentclass{article}\begin{document}\def\a{\a}\a\end{document}`, nil)
	elapsed := time.Since(started)
	if result.Status != "timeout" {
		t.Fatalf("status = %q, want timeout", result.Status)
	}
	if elapsed > CompileTimeout+5*time.Second {
		t.Fatalf("compile ran %v past its %v timeout — process group not killed?", elapsed, CompileTimeout)
	}
}

func TestNeedsRerunReadsTheEnginesOwnHint(t *testing.T) {
	dir := t.TempDir()
	if needsRerun(dir) {
		t.Fatal("no log yet: no rerun")
	}
	cases := map[string]bool{
		"LaTeX Warning: Label(s) may have changed. Rerun to get cross-references right.":                                            true,
		"Package rerunfilecheck Warning: File `main.out' has changed.\n(rerunfilecheck)                Rerun to get outlines right": true,
		"Output written on main.pdf (1 page, 1234 bytes).":                                                                          false,
	}
	for log, want := range cases {
		if err := os.WriteFile(filepath.Join(dir, "main.log"), []byte(log), 0o600); err != nil {
			t.Fatal(err)
		}
		if got := needsRerun(dir); got != want {
			t.Errorf("needsRerun(%q) = %v, want %v", log, got, want)
		}
	}
}

// ── project files ───────────────────────────────────────────────────────────

func TestValidPath(t *testing.T) {
	for _, p := range []string{
		"refs.bib", "chapters/intro.tex", "figs/a b/(1)+x,y-z_w.png", "Main.bib",
		"a/b/c/d/e/f.tex", "sub/main.tex", strings.Repeat("a", 80),
	} {
		if !ValidPath(p) {
			t.Errorf("ValidPath(%q) = false, want true", p)
		}
	}
	for _, p := range []string{
		"", "../x", "/etc/x", ".hidden", "a/.git/config", "a//b", "a/", "a\\b",
		"main.tex", "MAIN.TEX", "main.pdf", "main.log", "main.aux", "main.bbl",
		"main.blg", "main.out", "compile.out", "a/b/c/d/e/f/g.tex", "a/./b", "x\x00",
		"caf\u00e9.tex", "a:b", "a*b", strings.Repeat("a", 81), strings.Repeat("a/", 100) + "b",
	} {
		if ValidPath(p) {
			t.Errorf("ValidPath(%q) = true, want false", p)
		}
	}
}

func TestCheckFiles(t *testing.T) {
	ok := []File{{Path: "a/b.tex", Data: []byte("x")}, {Path: "a/c.tex"}, {Path: "refs.bib"}}
	if err := CheckFiles(ok); err != nil {
		t.Fatal(err)
	}
	tooMany := make([]File, MaxFiles+1)
	for i := range tooMany {
		tooMany[i] = File{Path: "f" + strings.Repeat("x", i%50) + string(rune('a'+i%26)) + ".tex"}
	}
	for name, files := range map[string][]File{
		"bad path":    {{Path: "../x"}},
		"duplicate":   {{Path: "A.tex"}, {Path: "a.tex"}},
		"file/folder": {{Path: "a"}, {Path: "a/b.tex"}},
		"too large":   {{Path: "a.bin", Data: make([]byte, MaxFilesBytes/2+1)}, {Path: "b.bin", Data: make([]byte, MaxFilesBytes/2)}},
		"too many":    tooMany,
	} {
		if CheckFiles(files) == nil {
			t.Errorf("%s: accepted", name)
		}
	}
}

func TestCompileWritesProjectFilesPrivately(t *testing.T) {
	dir := t.TempDir()
	files := []File{{Path: "chapters/one/intro.tex", Data: []byte("hi")}, {Path: "refs.bib", Data: []byte("@misc{a}")}}
	if err := writeFiles(dir, files); err != nil {
		t.Fatal(err)
	}
	got, err := os.ReadFile(filepath.Join(dir, "chapters", "one", "intro.tex"))
	if err != nil || string(got) != "hi" {
		t.Fatalf("intro.tex: %q %v", got, err)
	}
	if runtime.GOOS != "windows" {
		for path, want := range map[string]os.FileMode{"chapters": 0o700, "chapters/one": 0o700, "refs.bib": 0o600} {
			info, err := os.Stat(filepath.Join(dir, path))
			if err != nil || info.Mode().Perm() != want {
				t.Errorf("%s: mode %v %v, want %v", path, info.Mode().Perm(), err, want)
			}
		}
	}
	// Never overwrite, never follow a link out of the work dir.
	if err := writeFiles(dir, files[1:]); err == nil {
		t.Fatal("an existing file was overwritten")
	}
	if runtime.GOOS != "windows" {
		outside := t.TempDir()
		if err := os.Symlink(outside, filepath.Join(dir, "link")); err != nil {
			t.Fatal(err)
		}
		if err := writeFiles(dir, []File{{Path: "link/x.tex", Data: []byte("x")}}); err == nil {
			t.Fatal("wrote through a symlink")
		}
		if _, err := os.Stat(filepath.Join(outside, "x.tex")); err == nil {
			t.Fatal("a file escaped the work dir")
		}
	}
}

func TestCompileRefusesForbiddenProjectTexFiles(t *testing.T) {
	result := Compile(context.Background(), "definitely-not-a-real-binary", t.TempDir(),
		`\documentclass{article}\begin{document}\input{sub/evil}\end{document}`,
		[]File{{Path: "sub/evil.tex", Data: []byte(`\newread\f`)}})
	if result.Status != "error" || len(result.Errors) == 0 || !strings.Contains(result.Errors[0], "sub/evil.tex") {
		t.Fatalf("got %+v", result)
	}
	// Only .tex files are TeX the user wrote; data files are not scanned.
	result = Compile(context.Background(), "definitely-not-a-real-binary", t.TempDir(),
		`\documentclass{article}\begin{document}x\end{document}`,
		[]File{{Path: "data.txt", Data: []byte(`\newread`)}, {Path: "../x.tex"}})
	if result.Status != "error" || !strings.Contains(result.Errors[0], "Invalid project files") {
		t.Fatalf("got %+v", result)
	}
}

func TestRealCompileInputsProjectFiles(t *testing.T) {
	engine := findPdflatex(t)
	src := `\documentclass{article}\begin{document}\input{chapters/intro}\include{appendix}\end{document}`
	files := []File{
		{Path: "chapters/intro.tex", Data: []byte(`\section{Intro}\label{s}See~\ref{s}. \message{INTRO-WAS-READ}`)},
		{Path: "appendix.tex", Data: []byte(`Appendix text.`)},
	}
	result := Compile(context.Background(), engine, t.TempDir(), src, files)
	if result.Status != "compiled" {
		t.Fatalf("status = %q; errors=%v log=%s", result.Status, result.Errors, result.Log)
	}
	if !strings.Contains(result.Log, "INTRO-WAS-READ") {
		t.Fatalf("intro.tex was not read: %s", result.Log)
	}
}

func TestRealCompileRunsBibtex(t *testing.T) {
	engine := findPdflatex(t)
	if _, err := exec.LookPath("bibtex"); err != nil {
		t.Skip("bibtex not installed")
	}
	dir := t.TempDir()
	src := `\documentclass{article}\begin{document}See \cite{knuth}.\bibliographystyle{plain}\bibliography{bib/refs}\end{document}`
	files := []File{{Path: "bib/refs.bib", Data: []byte(`@book{knuth, author={Donald Knuth}, title={The {\TeX}book}, year={1984}, publisher={Addison-Wesley}}`)}}
	result := Compile(context.Background(), engine, dir, src, files)
	if result.Status != "compiled" {
		t.Fatalf("status = %q; errors=%v log=%s", result.Status, result.Errors, result.Log)
	}
	bbl, err := os.ReadFile(filepath.Join(dir, "main.bbl"))
	if err != nil || !strings.Contains(string(bbl), "Knuth") {
		t.Fatalf("bibtex did not run: %q %v", bbl, err)
	}
	if strings.Contains(result.Log, "Citation `knuth' on page 1 undefined") {
		t.Fatalf("citation left undefined after the bibtex passes: %s", result.Log)
	}
}

func TestHasBibdata(t *testing.T) {
	dir := t.TempDir()
	if hasBibdata(dir) {
		t.Fatal("no aux: no bibtex")
	}
	for aux, want := range map[string]bool{`\relax \bibdata{refs}`: true, `\relax \citation{x}`: false} {
		if err := os.WriteFile(filepath.Join(dir, "main.aux"), []byte(aux), 0o600); err != nil {
			t.Fatal(err)
		}
		if got := hasBibdata(dir); got != want {
			t.Errorf("hasBibdata(%q) = %v", aux, got)
		}
	}
}

func TestDecodeFiles(t *testing.T) {
	files, err := DecodeFiles([]WireFile{{Path: "a/b.tex", ContentBase64: "aGk="}, {Path: "empty.txt"}})
	if err != nil || len(files) != 2 || string(files[0].Data) != "hi" || len(files[1].Data) != 0 {
		t.Fatalf("got %v %v", files, err)
	}
	big := base64.StdEncoding.EncodeToString(make([]byte, MaxFilesBytes+1))
	for name, wire := range map[string][]WireFile{
		"not base64":     {{Path: "a.tex", ContentBase64: "!!!!"}},
		"unpadded":       {{Path: "a.tex", ContentBase64: "aGk"}},
		"line break":     {{Path: "a.tex", ContentBase64: "aG\nk="}},
		"non-zero tail":  {{Path: "a.tex", ContentBase64: "aGl="}},
		"bad path":       {{Path: "/etc/x", ContentBase64: "aGk="}},
		"reserved":       {{Path: "main.tex", ContentBase64: "aGk="}},
		"too large":      {{Path: "a.bin", ContentBase64: big}},
		"too many files": make([]WireFile, MaxFiles+1),
	} {
		if _, err := DecodeFiles(wire); err == nil {
			t.Errorf("%s: accepted", name)
		}
	}
}
