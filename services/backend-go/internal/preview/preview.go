// Package preview renders the first page of a compiled PDF as a small JPEG,
// for document cards. It runs poppler's pdftoppm (poppler-utils, installed
// in the production image) with a time limit, a size limit on what goes in
// and comes out, and at most two renders at a time, so it cannot take over
// a small instance. Where pdftoppm is missing, Render reports ErrUnavailable
// and cards show no picture.
package preview

import (
	"bytes"
	"context"
	"errors"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"time"
)

const (
	// Width in pixels: sharp on a card at twice its CSS size.
	Width       = 480
	timeout     = 15 * time.Second
	maxInput    = 16 << 20
	maxOutput   = 512 << 10
	jpegQuality = "quality=78,progressive=y"
)

var (
	ErrUnavailable = errors.New("preview: pdftoppm is not installed")
	ErrRender      = errors.New("preview: the PDF could not be rendered")
	slots          = make(chan struct{}, 2)
	// tool is pdftoppm's path, found once; tests may replace it.
	tool, lookErr = exec.LookPath("pdftoppm")
)

// Available reports whether previews can be rendered here.
func Available() bool { return lookErr == nil }

// Render answers page 1 of pdf as a JPEG Width pixels wide.
func Render(ctx context.Context, pdf []byte) ([]byte, error) {
	if lookErr != nil {
		return nil, ErrUnavailable
	}
	if len(pdf) == 0 || len(pdf) > maxInput || !bytes.HasPrefix(pdf, []byte("%PDF")) {
		return nil, ErrRender
	}
	select {
	case slots <- struct{}{}:
		defer func() { <-slots }()
	case <-ctx.Done():
		return nil, ctx.Err()
	}
	dir, err := os.MkdirTemp("", "skolab-preview-")
	if err != nil {
		return nil, err
	}
	defer os.RemoveAll(dir)
	in := filepath.Join(dir, "in.pdf")
	if err := os.WriteFile(in, pdf, 0o600); err != nil {
		return nil, err
	}
	ctx, cancel := context.WithTimeout(ctx, timeout)
	defer cancel()
	out := filepath.Join(dir, "page")
	cmd := exec.CommandContext(ctx, tool, "-jpeg", "-jpegopt", jpegQuality, "-f", "1", "-l", "1",
		"-scale-to-x", strconv.Itoa(Width), "-scale-to-y", "-1", "-singlefile", in, out)
	cmd.Dir = dir
	cmd.Env = []string{"PATH=" + os.Getenv("PATH"), "HOME=" + dir}
	if err := cmd.Run(); err != nil {
		return nil, ErrRender
	}
	image, err := os.ReadFile(out + ".jpg")
	if err != nil || len(image) == 0 || len(image) > maxOutput {
		return nil, ErrRender
	}
	return image, nil
}
