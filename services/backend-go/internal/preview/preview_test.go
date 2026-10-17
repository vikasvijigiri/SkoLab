package preview

import (
	"bytes"
	"context"
	"errors"
	"path/filepath"
	"testing"
)

// A tiny, valid one-page PDF.
const onePage = "%PDF-1.4\n1 0 obj<</Type/Catalog/Pages 2 0 R>>endobj\n2 0 obj<</Type/Pages/Kids[3 0 R]/Count 1>>endobj\n" +
	"3 0 obj<</Type/Page/Parent 2 0 R/MediaBox[0 0 200 300]/Contents 4 0 R/Resources<<>>>>endobj\n" +
	"4 0 obj<</Length 35>>stream\n0 0 1 rg 20 20 160 260 re f\nendstream endobj\n" +
	"trailer<</Root 1 0 R>>\n%%EOF\n"

func TestRendersTheFirstPageAsAJPEG(t *testing.T) {
	if !Available() {
		t.Skip("pdftoppm not installed here")
	}
	image, err := Render(context.Background(), []byte(onePage))
	if err != nil {
		t.Fatal(err)
	}
	if !bytes.HasPrefix(image, []byte{0xff, 0xd8}) || len(image) < 200 {
		t.Fatalf("not a JPEG: %d bytes", len(image))
	}
}

func TestRefusesWhatIsNotAPDF(t *testing.T) {
	if !Available() {
		t.Skip("pdftoppm not installed here")
	}
	for _, input := range [][]byte{nil, []byte("hello"), []byte("%PDF-1.4 broken")} {
		if _, err := Render(context.Background(), input); !errors.Is(err, ErrRender) {
			t.Errorf("%q: %v", input, err)
		}
	}
	if _, err := Render(context.Background(), append([]byte("%PDF"), make([]byte, maxInput)...)); !errors.Is(err, ErrRender) {
		t.Errorf("oversized: %v", err)
	}
}

func TestWithoutTheToolThereIsNoPreview(t *testing.T) {
	saved, savedErr := tool, lookErr
	t.Cleanup(func() { tool, lookErr = saved, savedErr })
	tool, lookErr = "", errors.New("missing")
	if Available() {
		t.Fatal("available without the tool")
	}
	if _, err := Render(context.Background(), []byte(onePage)); !errors.Is(err, ErrUnavailable) {
		t.Fatal(err)
	}
}

func TestAFailingToolIsARenderError(t *testing.T) {
	saved, savedErr := tool, lookErr
	t.Cleanup(func() { tool, lookErr = saved, savedErr })
	// A tool that exits without writing an image.
	tool, lookErr = filepath.Join(t.TempDir(), "absent-binary"), nil
	if _, err := Render(context.Background(), []byte(onePage)); !errors.Is(err, ErrRender) {
		t.Fatal(err)
	}
}

func TestACancelledRequestDoesNotWaitForASlot(t *testing.T) {
	if !Available() {
		t.Skip("pdftoppm not installed here")
	}
	for i := 0; i < cap(slots); i++ {
		slots <- struct{}{}
	}
	t.Cleanup(func() {
		for i := 0; i < cap(slots); i++ {
			<-slots
		}
	})
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	if _, err := Render(ctx, []byte(onePage)); !errors.Is(err, context.Canceled) {
		t.Fatal(err)
	}
}
