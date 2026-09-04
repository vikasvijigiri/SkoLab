package system

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/gin-gonic/gin"
)

func init() { gin.SetMode(gin.TestMode) }

func TestRoot(t *testing.T) {
	w := httptest.NewRecorder()
	c, _ := gin.CreateTestContext(w)
	c.Request = httptest.NewRequest(http.MethodGet, "/api/v1/", nil)

	Root(c)

	if w.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200", w.Code)
	}
	if got := w.Body.String(); got != `{"message":"Welcome to the SkoLab API!"}` {
		t.Errorf("body = %s", got)
	}
}

// With no DB pool initialised, the DB probe fails → database "degraded" and the
// overall state is "outage" (Python: db degraded ⇒ outage). ai_inference is
// derived from a stubbed Python /ai_status.
func TestStatus_DBDegradedGivesOutage(t *testing.T) {
	ai := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/api/v1/ai_status" {
			t.Errorf("ai_status path = %q", r.URL.Path)
		}
		_, _ = w.Write([]byte(`{"llm_active": true}`))
	}))
	defer ai.Close()

	old := PythonBackendURL
	PythonBackendURL = ai.URL
	defer func() { PythonBackendURL = old }()

	w := httptest.NewRecorder()
	c, _ := gin.CreateTestContext(w)
	c.Request = httptest.NewRequest(http.MethodGet, "/api/v1/status", nil)

	Status(c)

	if w.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200", w.Code)
	}
	var resp StatusResponse
	if err := json.Unmarshal(w.Body.Bytes(), &resp); err != nil {
		t.Fatalf("body not StatusResponse-shaped: %v (%s)", err, w.Body.String())
	}
	if resp.Services.Database != "degraded" {
		t.Errorf("services.database = %q, want degraded", resp.Services.Database)
	}
	if resp.Services.CacheLayer != "degraded" {
		t.Errorf("services.cache_layer = %q, want degraded (tracks DB)", resp.Services.CacheLayer)
	}
	if resp.Status != "outage" {
		t.Errorf("status = %q, want outage", resp.Status)
	}
	if resp.Services.APIGateway != "operational" {
		t.Errorf("services.api_gateway = %q, want operational", resp.Services.APIGateway)
	}
	if resp.Services.AIInference != "operational" {
		t.Errorf("services.ai_inference = %q, want operational (llm_active true)", resp.Services.AIInference)
	}
	// incidents must serialise as an array (…":[…), never null.
	if !strings.Contains(w.Body.String(), `"incidents":[`) {
		t.Errorf("incidents not a JSON array: %s", w.Body.String())
	}
}

func TestStatus_AIDegradedWhenPythonUnreachable(t *testing.T) {
	old := PythonBackendURL
	PythonBackendURL = "http://127.0.0.1:1"
	defer func() { PythonBackendURL = old }()

	w := httptest.NewRecorder()
	c, _ := gin.CreateTestContext(w)
	c.Request = httptest.NewRequest(http.MethodGet, "/api/v1/status", nil)

	Status(c)

	var resp StatusResponse
	if err := json.Unmarshal(w.Body.Bytes(), &resp); err != nil {
		t.Fatalf("bad body: %v", err)
	}
	if resp.Services.AIInference != "degraded" {
		t.Errorf("ai_inference = %q, want degraded", resp.Services.AIInference)
	}
}

func TestReadIncidents_NeverNil(t *testing.T) {
	got := readIncidents()
	if got == nil {
		t.Fatal("readIncidents() returned nil, want a (possibly empty) slice")
	}
	b, _ := json.Marshal(got)
	if string(b) == "null" {
		t.Fatal("readIncidents() marshals to null")
	}
}
