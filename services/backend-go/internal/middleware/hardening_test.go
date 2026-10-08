package middleware

import (
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/gin-gonic/gin"
)

func hardenedRouter() *gin.Engine {
	gin.SetMode(gin.TestMode)
	r := gin.New()
	r.Use(SecurityHeaders(), BodyLimit(16))
	echo := func(c *gin.Context) {
		body, err := io.ReadAll(c.Request.Body)
		if err != nil {
			c.AbortWithStatus(http.StatusBadRequest)
			return
		}
		c.String(http.StatusOK, string(body))
	}
	r.POST("/api/v1/echo", echo)
	r.GET("/gateway-health", func(c *gin.Context) { c.Status(http.StatusOK) })
	return r
}

func TestSecurityHeadersOnEveryResponse(t *testing.T) {
	r := hardenedRouter()
	for _, path := range []string{"/gateway-health", "/api/v1/echo", "/nope"} {
		w := httptest.NewRecorder()
		r.ServeHTTP(w, httptest.NewRequest(http.MethodGet, path, nil))
		for _, header := range []string{"Strict-Transport-Security", "X-Content-Type-Options", "X-Frame-Options", "Referrer-Policy", "Content-Security-Policy"} {
			if w.Header().Get(header) == "" {
				t.Fatalf("%s: missing %s", path, header)
			}
		}
	}
}

func TestAPIResponsesAreNeverCached(t *testing.T) {
	r := hardenedRouter()
	w := httptest.NewRecorder()
	r.ServeHTTP(w, httptest.NewRequest(http.MethodPost, "/api/v1/echo", strings.NewReader("hi")))
	if got := w.Header().Get("Cache-Control"); got != "no-store" {
		t.Fatalf("api Cache-Control = %q", got)
	}
	w = httptest.NewRecorder()
	r.ServeHTTP(w, httptest.NewRequest(http.MethodGet, "/gateway-health", nil))
	if got := w.Header().Get("Cache-Control"); got != "" {
		t.Fatalf("health Cache-Control = %q", got)
	}
}

func TestBodyLimit(t *testing.T) {
	r := hardenedRouter()
	cases := []struct {
		name    string
		body    io.Reader
		chunked bool
		want    int
	}{
		{"within the limit", strings.NewReader("small"), false, http.StatusOK},
		{"declared too large", strings.NewReader(strings.Repeat("x", 17)), false, http.StatusRequestEntityTooLarge},
		{"undeclared and too large", io.MultiReader(strings.NewReader(strings.Repeat("x", 64))), true, http.StatusBadRequest},
	}
	for _, tc := range cases {
		req := httptest.NewRequest(http.MethodPost, "/api/v1/echo", tc.body)
		if tc.chunked {
			req.ContentLength = -1
		}
		w := httptest.NewRecorder()
		r.ServeHTTP(w, req)
		if w.Code != tc.want {
			t.Fatalf("%s: status %d, want %d", tc.name, w.Code, tc.want)
		}
	}
}

func TestRequestIDValid(t *testing.T) {
	for id, want := range map[string]bool{
		"3f2b9c1e-7a4d-4e0b-9a51-0c3e1d2f4a5b": true,
		"req_123.abc:9":                        true,
		"":                                     false,
		strings.Repeat("a", 129):               false,
		"bad id":                               false,
		"inject\nfake=log":                     false,
		"<script>":                             false,
	} {
		if got := RequestIDValid(id); got != want {
			t.Fatalf("RequestIDValid(%q) = %v, want %v", id, got, want)
		}
	}
}

func TestMalformedQueryIs400(t *testing.T) {
	gin.SetMode(gin.TestMode)
	r := gin.New()
	r.Use(ValidQuery())
	r.GET("/api/v1/items", func(c *gin.Context) { c.String(http.StatusOK, c.Query("page_token")) })
	for query, want := range map[string]int{
		"":                   http.StatusOK,
		"?page_token=abc":    http.StatusOK,
		"?page_token=a%2Bb":  http.StatusOK,
		"?page_token=%%%":    http.StatusBadRequest,
		"?page_token=%zz":    http.StatusBadRequest,
		"?a=1&b=%":           http.StatusBadRequest,
		"?page_token=a%00b":  http.StatusBadRequest,
		"?page_token=%ff":    http.StatusBadRequest,
		"?a%00=1":            http.StatusBadRequest,
		"?page_token=%C3%A9": http.StatusOK,
	} {
		req := httptest.NewRequest(http.MethodGet, "/api/v1/items", nil)
		req.URL.RawQuery = strings.TrimPrefix(query, "?")
		w := httptest.NewRecorder()
		r.ServeHTTP(w, req)
		if w.Code != want {
			t.Fatalf("%q: status %d, want %d", query, w.Code, want)
		}
	}
}

func TestPathWithNULOrInvalidUTF8Is404(t *testing.T) {
	gin.SetMode(gin.TestMode)
	r := gin.New()
	r.Use(ValidPath())
	r.GET("/api/v1/workspaces/:id", func(c *gin.Context) { c.String(http.StatusOK, c.Param("id")) })
	for path, want := range map[string]int{
		"/api/v1/workspaces/ws-1":      http.StatusOK,
		"/api/v1/workspaces/caf%C3%A9": http.StatusOK,
		"/api/v1/workspaces/ws%001":    http.StatusNotFound,
		"/api/v1/workspaces/%ff":       http.StatusNotFound,
	} {
		w := httptest.NewRecorder()
		r.ServeHTTP(w, httptest.NewRequest(http.MethodGet, path, nil))
		if w.Code != want {
			t.Fatalf("%s: status %d, want %d", path, w.Code, want)
		}
	}
}

func TestStorable(t *testing.T) {
	for s, want := range map[string]bool{"": true, "ada": true, "café": true, "a\x00b": false, "\xff": false} {
		if got := Storable(s); got != want {
			t.Fatalf("Storable(%q) = %v, want %v", s, got, want)
		}
	}
}
