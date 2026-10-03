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
