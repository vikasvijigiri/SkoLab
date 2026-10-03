package texsandbox

import (
	"context"
	"encoding/base64"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func TestFindForbiddenCatchesFileAndShellPrimitives(t *testing.T) {
	cases := []string{
		`\input{/etc/passwd}`,
		`\include{secrets}`,
		`\openin5=/proc/self/environ`,
		`\newread\f`,
		`\read5 to \x`,
		`\immediate\write18{id}`,
		`\newwrite\o`,
		`\input|ls`,
		`\verbatiminput{a}`,
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
		`\documentclass{article}\begin{document}\input{/etc/passwd}\end{document}`)
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
	result := Compile(context.Background(), engine, dir, src)
	if result.Status != "error" {
		t.Fatalf("status = %q, want error (source should be refused before pdflatex runs)", result.Status)
	}
	if strings.Contains(result.Log, "SUPER-SECRET") {
		t.Fatalf("secret leaked into compile log")
	}
	if result.PDFBase64 != "" {
		t.Fatalf("a refused compile must not produce a PDF")
	}
}

func TestRealCompileProducesAPDFWithoutLeakingPaths(t *testing.T) {
	engine := findPdflatex(t)
	dir := t.TempDir()
	result := Compile(context.Background(), engine, dir,
		`\documentclass{article}\begin{document}Hello, world.\end{document}`)
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
		`\documentclass{article}\begin{document}\def\a{\a}\a\end{document}`)
	elapsed := time.Since(started)
	if result.Status != "timeout" {
		t.Fatalf("status = %q, want timeout", result.Status)
	}
	if elapsed > CompileTimeout+5*time.Second {
		t.Fatalf("compile ran %v past its %v timeout — process group not killed?", elapsed, CompileTimeout)
	}
}
