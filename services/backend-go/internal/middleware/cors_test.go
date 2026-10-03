package middleware

import (
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/gin-gonic/gin"
)

func newCORSRouter() *gin.Engine {
	gin.SetMode(gin.TestMode)
	r := gin.New()
	r.Use(CORS())
	r.GET("/ping", func(c *gin.Context) { c.Status(http.StatusOK) })
	return r
}

func TestCORS_AllowedOriginGetsHeaders(t *testing.T) {
	r := newCORSRouter()
	req := httptest.NewRequest(http.MethodGet, "/ping", nil)
	req.Header.Set("Origin", "http://localhost:3000")
	w := httptest.NewRecorder()
	r.ServeHTTP(w, req)

	if got := w.Header().Get("Access-Control-Allow-Origin"); got != "http://localhost:3000" {
		t.Errorf("Access-Control-Allow-Origin = %q, want %q", got, "http://localhost:3000")
	}
	if got := w.Header().Get("Access-Control-Allow-Credentials"); got != "true" {
		t.Errorf("Access-Control-Allow-Credentials = %q, want %q", got, "true")
	}
	if w.Code != http.StatusOK {
		t.Errorf("status = %d, want %d", w.Code, http.StatusOK)
	}
}

func TestCORS_DisallowedOriginGetsNoHeaders(t *testing.T) {
	r := newCORSRouter()
	req := httptest.NewRequest(http.MethodGet, "/ping", nil)
	req.Header.Set("Origin", "http://evil.example.com")
	w := httptest.NewRecorder()
	r.ServeHTTP(w, req)

	if got := w.Header().Get("Access-Control-Allow-Origin"); got != "" {
		t.Errorf("expected no Access-Control-Allow-Origin for disallowed origin, got %q", got)
	}
	// The request itself should still be served -- CORS only gates the browser's
	// ability to read the response, not the gateway's willingness to process it.
	if w.Code != http.StatusOK {
		t.Errorf("status = %d, want %d (non-browser/no-CORS requests must still pass through)", w.Code, http.StatusOK)
	}
}

func TestCORS_PreflightShortCircuitsWithNoContent(t *testing.T) {
	r := newCORSRouter()
	req := httptest.NewRequest(http.MethodOptions, "/ping", nil)
	req.Header.Set("Origin", "http://localhost:3000")
	w := httptest.NewRecorder()
	r.ServeHTTP(w, req)

	if w.Code != http.StatusNoContent {
		t.Errorf("preflight status = %d, want %d", w.Code, http.StatusNoContent)
	}
	if got := w.Header().Get("Access-Control-Allow-Methods"); got == "" {
		t.Error("expected Access-Control-Allow-Methods to be set on preflight response")
	}
}

func TestCORS_NoOriginHeaderPassesThroughUnmodified(t *testing.T) {
	r := newCORSRouter()
	req := httptest.NewRequest(http.MethodGet, "/ping", nil)
	w := httptest.NewRecorder()
	r.ServeHTTP(w, req)

	if got := w.Header().Get("Access-Control-Allow-Origin"); got != "" {
		t.Errorf("expected no CORS headers for a same-origin/non-browser request, got %q", got)
	}
	if w.Code != http.StatusOK {
		t.Errorf("status = %d, want %d", w.Code, http.StatusOK)
	}
}

func TestCORS_AllowsIdempotencyKeyAndExposesCreateHeaders(t *testing.T) {
	gin.SetMode(gin.TestMode)
	r := gin.New()
	r.Use(CORS())
	r.POST("/x", func(c *gin.Context) { c.Status(http.StatusCreated) })
	req := httptest.NewRequest(http.MethodOptions, "/x", nil)
	req.Header.Set("Origin", "http://localhost:3000")
	w := httptest.NewRecorder()
	r.ServeHTTP(w, req)
	if !strings.Contains(w.Header().Get("Access-Control-Allow-Headers"), "Idempotency-Key") {
		t.Fatalf("Allow-Headers = %q", w.Header().Get("Access-Control-Allow-Headers"))
	}
	for _, h := range []string{"Location", "Idempotent-Replayed"} {
		if !strings.Contains(w.Header().Get("Access-Control-Expose-Headers"), h) {
			t.Fatalf("Expose-Headers = %q, missing %s", w.Header().Get("Access-Control-Expose-Headers"), h)
		}
	}
}

func TestCORS_ProductionOnlyAllowsConfiguredOrigins(t *testing.T) {
	t.Setenv("APP_ENV", "production")
	t.Setenv("APP_BASE_URL", "https://app.skolab.example")
	t.Setenv("CORS_ORIGINS", "https://preview.skolab.example")
	r := newCORSRouter()
	for _, tc := range []struct {
		origin  string
		allowed bool
	}{
		{"http://localhost:3000", false},
		{"http://127.0.0.1:8000", false},
		{"https://app.skolab.example", true},
		{"https://preview.skolab.example", true},
	} {
		req := httptest.NewRequest(http.MethodGet, "/ping", nil)
		req.Header.Set("Origin", tc.origin)
		w := httptest.NewRecorder()
		r.ServeHTTP(w, req)
		got := w.Header().Get("Access-Control-Allow-Origin")
		if tc.allowed && got != tc.origin || !tc.allowed && got != "" {
			t.Errorf("origin %q: Access-Control-Allow-Origin = %q, allowed = %v", tc.origin, got, tc.allowed)
		}
		if IsAllowedOrigin(tc.origin) != tc.allowed {
			t.Errorf("origin %q: WebSocket origin policy differs from HTTP", tc.origin)
		}
	}
}

func TestCORS_ProductionDoesNotTrustDefaultBaseURL(t *testing.T) {
	t.Setenv("APP_ENV", "production")
	t.Setenv("APP_BASE_URL", "http://localhost:8000")
	t.Setenv("CORS_ORIGINS", "")
	if IsAllowedOrigin("http://localhost:8000") {
		t.Fatal("production must not trust the default local APP_BASE_URL")
	}
}
