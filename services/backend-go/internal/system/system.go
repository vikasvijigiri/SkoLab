// Package system serves the non-LLM metadata routes ported from the Python
// backend's app/api/v1/endpoints/system.py: the API-router root (GET /) and the
// public status report (GET /status). GET /ai_status stays in Python — it
// reports the LLM key/health. See decisions/0010.
package system

import (
	"context"
	"encoding/json"
	"net/http"
	"os"
	"path/filepath"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/skolab/backend-go/internal/db"
)

// PythonBackendURL is where the gateway reaches the Python LLM service. Mirrors
// main.go's own default; overridable in tests.
var PythonBackendURL = func() string {
	if v := os.Getenv("PYTHON_BACKEND_URL"); v != "" {
		return v
	}
	return "http://localhost:8000"
}()

// aiStatusClient is a short-timeout client for the one cross-service call /status
// makes (to Python's /api/v1/ai_status for the llm_active flag).
var aiStatusClient = &http.Client{Timeout: 2 * time.Second}

// RootResponse mirrors app/schemas/system.py::RootResponse.
type RootResponse struct {
	Message string `json:"message"`
}

// ServiceStatuses mirrors app/schemas/system.py::SystemServiceStatuses.
type ServiceStatuses struct {
	APIGateway  string `json:"api_gateway"`
	Database    string `json:"database"`
	CacheLayer  string `json:"cache_layer"`
	AIInference string `json:"ai_inference"`
}

// StatusResponse mirrors app/schemas/system.py::SystemStatusResponse.
type StatusResponse struct {
	Status    string           `json:"status"`
	Services  ServiceStatuses  `json:"services"`
	Incidents []map[string]any `json:"incidents"`
}

// Root handles GET /api/v1/ — byte-identical to the Python read_root handler.
func Root(c *gin.Context) {
	c.JSON(http.StatusOK, RootResponse{Message: "Welcome to the SkoLab API!"})
}

// Status handles GET /api/v1/status. Ports get_system_status: probe the DB and
// cache, read the incidents file, fold in the LLM-inference flag, and derive an
// overall state. The overall state depends only on DB / cache / active
// incidents — never on ai_inference — matching the Python logic exactly.
func Status(c *gin.Context) {
	ctx, cancel := context.WithTimeout(c.Request.Context(), 3*time.Second)
	defer cancel()

	dbStatus := probeDB(ctx)
	// Python: Redis L2 is PINGed when active; otherwise "the L2 is Postgres and
	// its reachability tracks the DB probe". The gateway's L2 is Postgres
	// (cache_entries), so cache_layer == database here.
	cacheStatus := dbStatus

	aiStatus := "operational"
	if !llmActive(ctx) {
		aiStatus = "degraded"
	}

	incidents := readIncidents()
	activeIncidents := 0
	for _, inc := range incidents {
		if s, _ := inc["status"].(string); s != "resolved" {
			activeIncidents++
		}
	}

	overall := "operational"
	switch {
	case dbStatus == "degraded" || cacheStatus == "degraded":
		if dbStatus == "degraded" {
			overall = "outage"
		} else {
			overall = "degraded"
		}
	case activeIncidents > 0:
		overall = "degraded"
	}

	c.JSON(http.StatusOK, StatusResponse{
		Status: overall,
		Services: ServiceStatuses{
			APIGateway:  "operational",
			Database:    dbStatus,
			CacheLayer:  cacheStatus,
			AIInference: aiStatus,
		},
		Incidents: incidents,
	})
}

func probeDB(ctx context.Context) string {
	if db.Pool == nil {
		return "degraded"
	}
	if err := db.Pool.Ping(ctx); err != nil {
		return "degraded"
	}
	return "operational"
}

// llmActive asks the Python service whether the LLM is up. Any failure to reach
// it (which is where /ai_status lives) counts as the LLM being unavailable —
// same net result as Python's is_llm_working() returning False.
func llmActive(ctx context.Context) bool {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, PythonBackendURL+"/api/v1/ai_status", nil)
	if err != nil {
		return false
	}
	resp, err := aiStatusClient.Do(req)
	if err != nil {
		return false
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return false
	}
	var body struct {
		LLMActive bool `json:"llm_active"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&body); err != nil {
		return false
	}
	return body.LLMActive
}

// readIncidents walks up from the working directory for docs/incidents.json,
// mirroring Python's _find_repo_root. In the deployed container (docker context
// is services/backend-go/) the file is absent and this returns an empty slice —
// which is exactly what the Python service also returns there, since its own
// docker context is services/backend/. A never-nil slice keeps the JSON `[]`.
func readIncidents() []map[string]any {
	out := []map[string]any{}
	dir, err := os.Getwd()
	if err != nil {
		return out
	}
	for i := 0; i < 8; i++ {
		p := filepath.Join(dir, "docs", "incidents.json")
		if raw, err := os.ReadFile(p); err == nil {
			var parsed []map[string]any
			if json.Unmarshal(raw, &parsed) == nil && parsed != nil {
				return parsed
			}
			return out
		}
		parent := filepath.Dir(dir)
		if parent == dir {
			break
		}
		dir = parent
	}
	return out
}
