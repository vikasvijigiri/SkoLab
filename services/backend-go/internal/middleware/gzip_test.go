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
