package middleware

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/gin-gonic/gin"
)

func TestRecoveryAnswersWithTheStandardError(t *testing.T) {
	gin.SetMode(gin.TestMode)
	r := gin.New()
	r.Use(Recovery())
	r.GET("/boom", func(*gin.Context) { panic("boom") })
	r.GET("/late", func(c *gin.Context) {
		c.String(http.StatusOK, "partial")
		panic("after writing")
	})

	w := httptest.NewRecorder()
	r.ServeHTTP(w, httptest.NewRequest(http.MethodGet, "/boom", nil))
	var body map[string]string
	if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil || w.Code != http.StatusInternalServerError || body["code"] != "internal_error" {
		t.Fatalf("got %d %q, want 500 internal_error", w.Code, w.Body)
	}

	w = httptest.NewRecorder()
	r.ServeHTTP(w, httptest.NewRequest(http.MethodGet, "/late", nil))
	if w.Code != http.StatusOK || w.Body.String() != "partial" {
		t.Fatalf("got %d %q, want the already-written response left alone", w.Code, w.Body)
	}
}
