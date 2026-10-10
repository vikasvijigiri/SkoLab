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
// refused before any process starts, in main.tex and every project .tex
// file; (3) a scrubbed environment and kpathsea "paranoid" file access for
// the subprocess itself (openin_any=p is what confines \input and \include
// to the work dir: no absolute paths, no "..", no dotfiles); (4) OS resource
// limits via prlimit and a killed process group on timeout; (5) bounded,
// path-scrubbed output.
package texsandbox

import (
	"bytes"
	"context"
	"encoding/base64"
	"errors"
	"fmt"
	"io"
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
	// Cross-references, citations and tables of contents settle on a later
	// pass (what latexmk does); every pass shares the one CompileTimeout.
	maxPasses      = 3
	rerunScanBytes = 64 * 1024
	// A bibtex run adds a pass: the bibliography lands on the pass after it
	// and its citations resolve on the one after that.
	maxPassesWithBibtex = 4
	auxScanBytes        = 8 * 1024 * 1024

	// MaxFiles and MaxFilesBytes bound the project files sent with one
	// compile (decoded bytes, main.tex not included).
	MaxFiles      = 200
	MaxFilesBytes = 10 * 1024 * 1024

	maxPathBytes = 200
	maxPathDepth = 6
)

// File is one project file written next to main.tex before the compile.
// Path is "/"-separated and relative to the work dir (see ValidPath).
type File struct {
	Path string
	Data []byte
}

// Result mirrors services/backend/app/schemas/colab.py's CompileResponse.
type Result struct {
	Status    string   `json:"status"`
	PDFBase64 string   `json:"pdf_base64,omitempty"`
	Log       string   `json:"log"`
	Errors    []string `json:"errors,omitempty"`
}

var (
	// \input and \include are allowed: a project is several files, and
	// kpathsea's openin_any=p keeps their reads inside the work dir. The
	// pipe form stays refused (it would be a shell read without -shell-escape
	// anyway), as do the raw read/write primitives.
	forbiddenPrimitives = regexp.MustCompile(
		`\\(openin|openout|read|readline|write|newread|newwrite|` +
			`verbatiminput|lstinputlisting)([^a-zA-Z]|$)`,
	)
	forbiddenPipe = regexp.MustCompile(`\\(?:input|include|openin|openout)\s*\{?\s*\|`)
	bibdata       = []byte(`\bibdata{`)
	// One path segment: 1..80 characters of a portable, shell- and
	// TeX-inert alphabet.
	pathSegment = regexp.MustCompile(`^[A-Za-z0-9 _.,()+\-]{1,80}$`)
	// reservedRoot are the names the compile itself owns at the work-dir
	// root (compared case-insensitively).
	reservedRoot = map[string]bool{
		"main.tex": true, "main.pdf": true, "main.log": true, "main.aux": true,
		"main.bbl": true, "main.blg": true, "main.out": true, "compile.out": true,
	}
	errorLine = regexp.MustCompile(`^(.*?):(\d+):\s*(.*)$`)
	rerunHint = regexp.MustCompile(
		`Rerun to get|Rerun LaTeX|Please rerun LaTeX|Label\(s\) may have changed|\(rerunfilecheck\)\s+Rerun`,
	)
)

// FindForbidden returns the first disallowed construct in source, or "".
func FindForbidden(source string) string {
	if m := forbiddenPipe.FindString(source); m != "" {
		return m
	}
	return forbiddenPrimitives.FindString(source)
}

// ValidPath reports whether p is an acceptable project file path: 1..200
// bytes of "/"-separated segments, each 1..80 characters of
// [A-Za-z0-9 _.,()+-] not starting with ".", at most 6 deep, and not one of
// the names the compile owns at the root (main.tex and its build outputs).
// Nothing in it can be absolute, a parent reference, or a dotfile.
func ValidPath(p string) bool {
	if len(p) == 0 || len(p) > maxPathBytes {
		return false
	}
	segments := strings.Split(p, "/")
	if len(segments) > maxPathDepth {
		return false
	}
	for _, seg := range segments {
		if !pathSegment.MatchString(seg) || seg[0] == '.' {
			return false
		}
	}
	return len(segments) > 1 || !reservedRoot[strings.ToLower(p)]
}

// CheckFiles validates a compile's project files as a set: count, decoded
// total, every path (ValidPath), no two paths equal ignoring case, and no
// file sitting where another file needs a folder. It returns a message
// naming the problem, or nil.
func CheckFiles(files []File) error {
	if len(files) > MaxFiles {
		return fmt.Errorf("at most %d project files are allowed", MaxFiles)
	}
	total := 0
	seen := make(map[string]bool, len(files))
	for _, f := range files {
		if !ValidPath(f.Path) {
			return fmt.Errorf("invalid project file path %q", f.Path)
		}
		total += len(f.Data)
		key := strings.ToLower(f.Path)
		if seen[key] {
			return fmt.Errorf("duplicate project file path %q", f.Path)
		}
		seen[key] = true
	}
	if total > MaxFilesBytes {
		return fmt.Errorf("project files exceed %d bytes in total", MaxFilesBytes)
	}
	for _, f := range files {
		for i := strings.IndexByte(f.Path, '/'); i >= 0; i = nextSlash(f.Path, i) {
			if seen[strings.ToLower(f.Path[:i])] {
				return fmt.Errorf("project file %q is also used as a folder", f.Path[:i])
			}
		}
	}
	return nil
}

// WireFile is a project file as the compile request carries it.
type WireFile struct {
	Path          string `json:"path"`
	ContentBase64 string `json:"content_base64"`
}

// DecodeFiles decodes request files (strict, padded standard base64; no
// line breaks) and checks the result with CheckFiles. Counts and sizes are
// bounded before anything is decoded.
func DecodeFiles(wire []WireFile) ([]File, error) {
	if len(wire) > MaxFiles {
		return nil, fmt.Errorf("at most %d project files are allowed", MaxFiles)
	}
	encoded := 0
	for _, w := range wire {
		encoded += len(w.ContentBase64)
	}
	if encoded > base64.StdEncoding.EncodedLen(MaxFilesBytes) {
		return nil, fmt.Errorf("project files exceed %d bytes in total", MaxFilesBytes)
	}
	files := make([]File, 0, len(wire))
	for _, w := range wire {
		if strings.ContainsAny(w.ContentBase64, "\r\n") {
			return nil, fmt.Errorf("project file %q is not valid base64", w.Path)
		}
		data, err := base64.StdEncoding.Strict().DecodeString(w.ContentBase64)
		if err != nil {
			return nil, fmt.Errorf("project file %q is not valid base64", w.Path)
		}
		files = append(files, File{Path: w.Path, Data: data})
	}
	if err := CheckFiles(files); err != nil {
		return nil, err
	}
	return files, nil
}

func nextSlash(p string, after int) int {
	if j := strings.IndexByte(p[after+1:], '/'); j >= 0 {
		return after + 1 + j
	}
	return -1
}

// writeFiles writes the project files under workDir through an os.Root, so
// no path, and no symlink, can ever resolve outside it; folders are 0700,
// files 0600 and created exclusively (an existing name is an error, never
// an overwrite).
func writeFiles(workDir string, files []File) error {
	root, err := os.OpenRoot(workDir)
	if err != nil {
		return err
	}
	defer root.Close()
	for _, f := range files {
		segments := strings.Split(f.Path, "/")
		for i := 1; i < len(segments); i++ {
			dir := strings.Join(segments[:i], "/")
			if err := root.Mkdir(dir, 0o700); err != nil && !errors.Is(err, os.ErrExist) {
				return err
			}
		}
		out, err := root.OpenFile(f.Path, os.O_WRONLY|os.O_CREATE|os.O_EXCL, 0o600)
		if err != nil {
			return err
		}
		_, werr := out.Write(f.Data)
		if cerr := out.Close(); werr == nil {
			werr = cerr
		}
		if werr != nil {
			return werr
		}
	}
	return nil
}

// findForbiddenInProject checks main.tex and every .tex project file.
func findForbiddenInProject(source string, files []File) (string, string) {
	if hit := FindForbidden(source); hit != "" {
		return hit, "main.tex"
	}
	for _, f := range files {
		if strings.HasSuffix(strings.ToLower(f.Path), ".tex") {
			if hit := FindForbidden(string(f.Data)); hit != "" {
				return hit, f.Path
			}
		}
	}
	return "", ""
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

// Compile runs one LaTeX source (as main.tex) plus its project files
// through pdflatex inside a scrubbed sandbox, with a bibtex run when the
// document has a bibliography. engine is the resolved binary path (caller
// checks exec.LookPath("pdflatex") once at startup, not per request).
// workDir must be a fresh, private directory the caller owns and removes.
// files are checked with CheckFiles here as well; callers validate first to
// answer a bad request with their own error.
func Compile(ctx context.Context, engine, workDir, source string, files []File) Result {
	if forbidden, where := findForbiddenInProject(source, files); forbidden != "" {
		msg := fmt.Sprintf("Unsupported construct '%s': file access and shell-adjacent "+
			"primitives are disabled in the CoLab compiler.", forbidden)
		if where != "main.tex" {
			msg = fmt.Sprintf("Unsupported construct '%s' in %s: file access and shell-adjacent "+
				"primitives are disabled in the CoLab compiler.", forbidden, where)
		}
		return Result{Status: "error", Errors: []string{msg}}
	}
	if err := CheckFiles(files); err != nil {
		return Result{Status: "error", Errors: []string{"Invalid project files: " + err.Error()}}
	}

	texPath := filepath.Join(workDir, "main.tex")
	if err := os.WriteFile(texPath, []byte(source), 0o600); err != nil {
		return Result{Status: "error", Errors: []string{"internal error writing source"}}
	}
	if err := writeFiles(workDir, files); err != nil {
		return Result{Status: "error", Errors: []string{"internal error writing project files"}}
	}

	command := limited([]string{
		engine, "-no-shell-escape", "-interaction=nonstopmode",
		"-halt-on-error", "-file-line-error", "main.tex",
	})

	ctx, cancel := context.WithTimeout(ctx, CompileTimeout)
	defer cancel()
	var (
		returncode int
		tail       string
		timedOut   bool
	)
	passes, limit, forced := 0, maxPasses, 0
	bibtexRan := false
	for passes < limit {
		var err error
		returncode, tail, timedOut, err = runBounded(ctx, command, workDir, sandboxEnv(workDir))
		passes++
		if err != nil {
			return Result{Status: "error", Errors: []string{"internal error running compiler"}}
		}
		if timedOut || returncode != 0 {
			break
		}
		if !bibtexRan && passes == 1 && hasBibdata(workDir) {
			if bibtex, lookErr := exec.LookPath("bibtex"); lookErr == nil {
				bibtexRan = true
				_, bibTail, bibTimedOut, bibErr := runBounded(ctx, limited([]string{bibtex, "main"}), workDir, sandboxEnv(workDir))
				if bibErr != nil {
					return Result{Status: "error", Errors: []string{"internal error running bibtex"}}
				}
				if bibTimedOut {
					tail, timedOut = bibTail, true
					break
				}
				// bibtex's own exit status is not fatal: its warnings and
				// errors show as undefined citations in the passes below.
				limit, forced = maxPassesWithBibtex, 2
				continue
			}
		}
		if forced > 0 {
			forced--
			if forced > 0 {
				continue
			}
		}
		if !needsRerun(workDir) {
			break
		}
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

// hasBibdata reports whether the first pass asked for a BibTeX bibliography
// (\bibliography, or biblatex with backend=bibtex, writes \bibdata).
func hasBibdata(workDir string) bool {
	f, err := os.Open(filepath.Join(workDir, "main.aux"))
	if err != nil {
		return false
	}
	defer f.Close()
	aux, err := io.ReadAll(io.LimitReader(f, auxScanBytes))
	return err == nil && bytes.Contains(aux, bibdata)
}

// needsRerun reports whether pdflatex's own log asks for another pass.
func needsRerun(workDir string) bool {
	tail, err := tailFile(filepath.Join(workDir, "main.log"), rerunScanBytes)
	return err == nil && rerunHint.MatchString(tail)
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
