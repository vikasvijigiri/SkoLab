// Package texsandbox compiles untrusted LaTeX source under layered defence.
//
// This is a Go port of services/backend/app/api/v1/endpoints/colab.py's
// _compile_source, moved here so it can run as its own minimal, Cloud-Run/
// Fargate-deployable binary (cmd/colab-sandbox) instead of a subprocess
// inside the API. Worker separation reduces resource contention and exposure
// of API credentials. Per-request container isolation requires a job runtime;
// a long-lived worker provides per-request temporary directories and processes.
//
// Layers, in order: (1) admission control — a bounded worker pool, refuse
// fast past it; (2) source policy — file-I/O / shell-adjacent primitives
// refused before any process starts; (3) a scrubbed environment and
// kpathsea "paranoid" file access for the subprocess itself; (4) OS resource
// limits via prlimit and a killed process group on timeout; (5) bounded,
// path-scrubbed output.
package texsandbox

import (
	"context"
	"encoding/base64"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"runtime"
	"strconv"
	"strings"
	"time"
)

const (
	MaxPDFBytes    = 8 * 1024 * 1024
	CompileTimeout = 20 * time.Second
	logTailBytes   = 20_000
)

// Result mirrors services/backend/app/schemas/colab.py's CompileResponse.
type Result struct {
	Status    string   `json:"status"`
	PDFBase64 string   `json:"pdf_base64,omitempty"`
	Log       string   `json:"log"`
	Errors    []string `json:"errors,omitempty"`
}

var (
	forbiddenPrimitives = regexp.MustCompile(
		`\\(input|include|openin|openout|read|readline|write|newread|newwrite|` +
			`verbatiminput|lstinputlisting)([^a-zA-Z]|$)`,
	)
	forbiddenPipe = regexp.MustCompile(`\\(?:input|openin|openout)\s*\{?\s*\|`)
	errorLine     = regexp.MustCompile(`^(.*?):(\d+):\s*(.*)$`)
)

// FindForbidden returns the first disallowed construct in source, or "".
func FindForbidden(source string) string {
	if m := forbiddenPipe.FindString(source); m != "" {
		return m
	}
	return forbiddenPrimitives.FindString(source)
}

// Slots bounds worker concurrency the same way the Python version's
// threading.BoundedSemaphore does: acquire with a timeout, refuse fast
// rather than queue unbounded CPU-heavy compiles.
type Slots chan struct{}

func NewSlots(n int) Slots {
	if n < 1 {
		n = 1
	}
	s := make(Slots, n)
	for i := 0; i < n; i++ {
		s <- struct{}{}
	}
	return s
}

// ErrBusy is returned when no slot became free within the wait budget.
var ErrBusy = fmt.Errorf("compile workers are busy")

func (s Slots) Acquire(wait time.Duration) error {
	return s.AcquireContext(context.Background(), wait)
}

func (s Slots) AcquireContext(ctx context.Context, wait time.Duration) error {
	timer := time.NewTimer(wait)
	defer timer.Stop()
	select {
	case <-s:
		return nil
	case <-ctx.Done():
		return ctx.Err()
	case <-timer.C:
		return ErrBusy
	}
}

func (s Slots) Release() { s <- struct{}{} }

// Compile runs one LaTeX source through pdflatex inside a scrubbed sandbox.
// engine is the resolved binary path (caller checks exec.LookPath("pdflatex")
// once at startup, not per request). workDir must be a fresh, private
// directory the caller owns and removes.
func Compile(ctx context.Context, engine, workDir, source string) Result {
	if forbidden := FindForbidden(source); forbidden != "" {
		return Result{
			Status: "error",
			Errors: []string{fmt.Sprintf(
				"Unsupported construct '%s': file access and shell-adjacent "+
					"primitives are disabled in the CoLab compiler.", forbidden,
			)},
		}
	}

	texPath := filepath.Join(workDir, "main.tex")
	if err := os.WriteFile(texPath, []byte(source), 0o600); err != nil {
		return Result{Status: "error", Errors: []string{"internal error writing source"}}
	}

	command := limited([]string{
		engine, "-no-shell-escape", "-interaction=nonstopmode",
		"-halt-on-error", "-file-line-error", "main.tex",
	})

	returncode, tail, timedOut, err := runBounded(ctx, command, workDir, sandboxEnv(workDir))
	if err != nil {
		return Result{Status: "error", Errors: []string{"internal error running compiler"}}
	}
	log := scrubPaths(tail, workDir)

	if timedOut {
		return Result{
			Status: "timeout",
			Log:    log,
			Errors: []string{fmt.Sprintf("Compilation exceeded the %.0f second limit.", CompileTimeout.Seconds())},
		}
	}

	pdfPath := filepath.Join(workDir, "main.pdf")
	info, statErr := os.Stat(pdfPath)
	if returncode != 0 || statErr != nil {
		errs := parseErrors(log)
		if len(errs) == 0 {
			errs = []string{"LaTeX compilation failed."}
		}
		return Result{Status: "error", Log: log, Errors: errs}
	}

	if info.Size() > MaxPDFBytes {
		return Result{Status: "error", Log: log, Errors: []string{"Compiled PDF exceeds the 8 MB limit."}}
	}
	pdfBytes, err := os.ReadFile(pdfPath)
	if err != nil {
		return Result{Status: "error", Log: log, Errors: []string{"internal error reading compiled PDF"}}
	}
	return Result{Status: "compiled", PDFBase64: base64.StdEncoding.EncodeToString(pdfBytes), Log: log}
}

func parseErrors(log string) []string {
	var errs []string
	for _, line := range strings.Split(log, "\n") {
		if m := errorLine.FindStringSubmatch(strings.TrimSpace(line)); m != nil {
			errs = append(errs, "line "+m[2]+": "+m[3])
			if len(errs) >= 20 {
				break
			}
		}
	}
	return errs
}

// runBounded runs command in its own process group and kills the whole
// group if it outlives CompileTimeout, so a forked/grandchild process (TeX
// can spawn helpers) can never survive past the deadline. Output goes to a
// temp file rather than an in-memory pipe so a runaway log cannot exhaust
// this process's memory; only its tail is read back.
func runBounded(ctx context.Context, command []string, dir string, env []string) (int, string, bool, error) {
	outPath := filepath.Join(dir, "compile.out")
	out, err := os.Create(outPath)
	if err != nil {
		return 0, "", false, err
	}
	defer out.Close()

	runCtx, cancel := context.WithTimeout(ctx, CompileTimeout)
	defer cancel()

	cmd := exec.CommandContext(runCtx, command[0], command[1:]...)
	cmd.Dir = dir
	cmd.Env = env
	cmd.Stdout = out
	cmd.Stderr = out
	cmd.Stdin = nil
	// isolateProcessGroup/killProcessGroup are platform-specific (POSIX vs.
	// Windows) — see runbounded_unix.go / runbounded_windows.go. On POSIX,
	// this puts the child in its own process group and kills that whole
	// group on timeout, so a grandchild TeX spawns cannot outlive the
	// deadline; exec.CommandContext's default Cancel only signals the direct
	// child.
	isolateProcessGroup(cmd)
	cmd.Cancel = func() error { return killProcessGroup(cmd) }

	runErr := cmd.Run()
	timedOut := runCtx.Err() != nil

	tail, readErr := tailFile(outPath, logTailBytes)
	if readErr != nil {
		return 0, "", timedOut, readErr
	}

	returncode := 0
	if !timedOut {
		if exitErr, ok := runErr.(*exec.ExitError); ok {
			returncode = exitErr.ExitCode()
		} else if runErr != nil {
			return 0, tail, false, runErr
		}
	}
	return returncode, tail, timedOut, nil
}

func tailFile(path string, maxBytes int64) (string, error) {
	f, err := os.Open(path)
	if err != nil {
		return "", err
	}
	defer f.Close()
	info, err := f.Stat()
	if err != nil {
		return "", err
	}
	offset := info.Size() - maxBytes
	if offset < 0 {
		offset = 0
	}
	if _, err := f.Seek(offset, 0); err != nil {
		return "", err
	}
	buf := make([]byte, info.Size()-offset)
	if _, err := f.Read(buf); err != nil && err.Error() != "EOF" {
		// a short read at EOF is fine; anything else, surface it
	}
	return string(buf), nil
}

// scrubPaths never hands the client this worker's filesystem layout.
func scrubPaths(log, workDir string) string {
	return strings.ReplaceAll(log, workDir, ".")
}

// sandboxEnv builds a minimal environment carrying none of this process's
// own configuration (no DATABASE_URL, no INTERNAL_API_TOKEN — this binary
// deliberately has neither, see cmd/colab-sandbox/main.go, but the scrub
// stays even if that ever changes) and sets kpathsea "paranoid" mode so the
// engine cannot read outside workDir even via an absolute path or "..".
func sandboxEnv(workDir string) []string {
	env := []string{
		"PATH=" + os.Getenv("PATH"),
		"HOME=" + workDir,
		"TMPDIR=" + workDir,
		"TEXMFOUTPUT=" + workDir,
		"LANG=C.UTF-8",
		"openin_any=p",
		"openout_any=p",
		"shell_escape=f",
	}
	if runtime.GOOS == "windows" {
		// Dev-machine-only carve-out (matches the Python original): MiKTeX
		// needs its profile locations to initialize at all. Production runs
		// the Linux path above, where this branch never executes.
		for _, name := range []string{"SYSTEMROOT", "USERPROFILE", "APPDATA", "LOCALAPPDATA"} {
			if v := os.Getenv(name); v != "" {
				env = append(env, name+"="+v)
			}
		}
		env = append(env, "TEMP="+workDir, "TMP="+workDir)
	}
	return env
}

// limited wraps command with prlimit(1) OS resource caps when available
// (the deployed Linux image installs util-linux for this; a dev machine
// without it just runs uncapped — same tradeoff the caller documents).
func limited(command []string) []string {
	prlimit, err := exec.LookPath("prlimit")
	if err != nil {
		return command
	}
	args := []string{
		"--cpu=30",
		"--as=" + strconv.Itoa(1024*1024*1024),
		"--fsize=" + strconv.Itoa(64*1024*1024),
		"--nofile=256",
	}
	return append(append([]string{prlimit}, args...), command...)
}
