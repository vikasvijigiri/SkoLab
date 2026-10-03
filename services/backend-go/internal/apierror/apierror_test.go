package apierror

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/gin-gonic/gin"
)

func TestAbortWritesTheStandardBody(t *testing.T) {
	gin.SetMode(gin.TestMode)
	w := httptest.NewRecorder()
	c, _ := gin.CreateTestContext(w)
	Abort(c, http.StatusNotFound, "not_found", "Workspace not found")

	var body map[string]string
	if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
		t.Fatal(err)
	}
	if w.Code != 404 || body["code"] != "not_found" || body["error"] != "Workspace not found" || len(body) != 2 {
		t.Fatalf("%d %v", w.Code, body)
	}
	if !c.IsAborted() {
		t.Fatal("the request must stop here")
	}
}

func TestUnknownPathIs404AndWrongMethodIs405WithAllow(t *testing.T) {
	gin.SetMode(gin.TestMode)
	r := gin.New()
	r.HandleMethodNotAllowed = true
	r.NoRoute(NoRoute)
	r.NoMethod(NoMethod)
	r.GET("/things", func(c *gin.Context) { c.Status(http.StatusOK) })
	r.DELETE("/things", func(c *gin.Context) { c.Status(http.StatusNoContent) })

	w := httptest.NewRecorder()
	r.ServeHTTP(w, httptest.NewRequest(http.MethodGet, "/nothing-here", nil))
	if w.Code != http.StatusNotFound || !json.Valid(w.Body.Bytes()) {
		t.Fatalf("unknown path: %d %s", w.Code, w.Body)
	}

	w = httptest.NewRecorder()
	r.ServeHTTP(w, httptest.NewRequest(http.MethodPut, "/things", nil))
	var body map[string]string
	_ = json.Unmarshal(w.Body.Bytes(), &body)
	if w.Code != http.StatusMethodNotAllowed || body["code"] != "method_not_allowed" {
		t.Fatalf("wrong method: %d %s", w.Code, w.Body)
	}
	if allow := w.Header().Get("Allow"); allow != "GET, DELETE" && allow != "DELETE, GET" {
		t.Fatalf("Allow = %q", allow)
	}
}
