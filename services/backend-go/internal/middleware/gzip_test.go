package middleware

import (
	"compress/gzip"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/gin-gonic/gin"
)

func newGzipTestRouter() *gin.Engine {
	gin.SetMode(gin.TestMode)
	r := gin.New()
	r.Use(Gzip())
	r.GET("/data", func(c *gin.Context) {
		// Large enough that a broken gzip wrapper (e.g. writing to the
		// wrong io.Writer) would visibly corrupt more than a byte or two.
		c.String(http.StatusOK, strings.Repeat("skolab-payload-", 200))
	})
	return r
}

func TestGzip_CompressesWhenClientAcceptsIt(t *testing.T) {
	r := newGzipTestRouter()

	req := httptest.NewRequest(http.MethodGet, "/data", nil)
	req.Header.Set("Accept-Encoding", "gzip")
	w := httptest.NewRecorder()
	r.ServeHTTP(w, req)

	if w.Code != http.StatusOK {
		t.Fatalf("status = %d, want %d", w.Code, http.StatusOK)
	}
	if w.Header().Get("Content-Encoding") != "gzip" {
		t.Fatalf("Content-Encoding = %q, want %q", w.Header().Get("Content-Encoding"), "gzip")
	}
	if w.Header().Get("Content-Length") != "" {
		t.Errorf("Content-Length should be stripped once the body is re-encoded, got %q", w.Header().Get("Content-Length"))
	}

	gz, err := gzip.NewReader(w.Body)
	if err != nil {
		t.Fatalf("response body is not valid gzip: %v", err)
	}
	defer gz.Close()
	decoded, err := io.ReadAll(gz)
	if err != nil {
		t.Fatalf("failed to decompress response body: %v", err)
	}
	want := strings.Repeat("skolab-payload-", 200)
	if string(decoded) != want {
		t.Errorf("decompressed body does not match the original payload (len %d vs %d)", len(decoded), len(want))
	}
}

func TestGzip_PassesThroughWhenClientDoesNotAcceptIt(t *testing.T) {
	r := newGzipTestRouter()

	req := httptest.NewRequest(http.MethodGet, "/data", nil)
	// No Accept-Encoding header at all.
	w := httptest.NewRecorder()
	r.ServeHTTP(w, req)

	if w.Header().Get("Content-Encoding") != "" {
		t.Errorf("Content-Encoding should be unset for a client that never asked for gzip, got %q", w.Header().Get("Content-Encoding"))
	}
	if w.Body.String() != strings.Repeat("skolab-payload-", 200) {
		t.Errorf("body should be the plain, uncompressed payload when the client didn't request gzip")
	}
}

// TestGzip_SurvivesAMidStreamFlush reproduces the exact shape of a real,
// live production bug: httputil.ReverseProxy flushes its destination writer
// between each chunk read from an upstream response reporting
// ContentLength == -1 (any chunked/streaming response -- every proxied LLM
// route here). Confirmed live against skolab-gateway's real
// /discovery/predict: the proxied, gzip-wrapped response came back as
// undecodable binary noise on every attempt, while the identical request
// sent straight to the Python backend (no gateway, no gzip) came back as
// clean JSON, and a native (non-proxied) Go gzip route decompressed
// correctly every time -- isolating the break to a flush landing between
// two writes on a gzip-wrapped connection.
//
// httptest.NewRecorder() cannot catch this: its Flush() is a no-op flag
// with no real chunked-transfer-encoding framing behind it, so it cannot
// reproduce a bug that only exists on a real net/http connection. This
// spins up an actual httptest.Server (a real TCP loopback listener running
// Go's real http.response/chunked writer -- the same type production runs
// on) and fetches from it as a real client would.
func TestGzip_SurvivesAMidStreamFlush(t *testing.T) {
	gin.SetMode(gin.TestMode)
	r := gin.New()
	r.Use(Gzip())
	firstChunk := strings.Repeat("skolab-chunk-one-", 200)
	secondChunk := strings.Repeat("skolab-chunk-two-", 200)
	r.GET("/stream", func(c *gin.Context) {
		c.Status(http.StatusOK)
		_, _ = c.Writer.Write([]byte(firstChunk))
		// The exact call ReverseProxy makes between upstream reads for a
		// chunked response -- this is what a writer wrapping Write() but not
		// Flush() gets wrong.
		c.Writer.Flush()
		_, _ = c.Writer.Write([]byte(secondChunk))
	})

	srv := httptest.NewServer(r)
	defer srv.Close()

	req, err := http.NewRequest(http.MethodGet, srv.URL+"/stream", nil)
	if err != nil {
		t.Fatalf("building request: %v", err)
	}
	// Go's http.Transport auto-decompresses gzip whenever IT is the one that
	// added Accept-Encoding -- setting it explicitly here, like a real
	// browser does, means the raw (still-compressed) bytes reach this test
	// exactly as they reached the real client that hit this bug.
	req.Header.Set("Accept-Encoding", "gzip")
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatalf("request failed: %v", err)
	}
	defer resp.Body.Close()

	gz, err := gzip.NewReader(resp.Body)
	if err != nil {
		t.Fatalf("response body is not valid gzip after a mid-stream flush: %v", err)
	}
	defer gz.Close()
	decoded, err := io.ReadAll(gz)
	if err != nil {
		t.Fatalf("failed to decompress a response that was flushed mid-stream: %v", err)
	}
	want := firstChunk + secondChunk
	if string(decoded) != want {
		t.Errorf("decompressed body corrupted by the mid-stream flush (len %d vs want %d)", len(decoded), len(want))
	}
}

func TestGzip_SkipsWebSocketUpgradeRequests(t *testing.T) {
	r := newGzipTestRouter()

	req := httptest.NewRequest(http.MethodGet, "/data", nil)
	req.Header.Set("Accept-Encoding", "gzip")
	req.Header.Set("Upgrade", "websocket")
	w := httptest.NewRecorder()
	r.ServeHTTP(w, req)

	if w.Header().Get("Content-Encoding") == "gzip" {
		t.Error("a WebSocket upgrade request must not be wrapped for gzip -- it would break the handshake/Hijack path")
	}
}
