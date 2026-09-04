package feed

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/gin-gonic/gin"
	"github.com/skolab/backend-go/internal/auth"
)

// These tests cover the db.Pool == nil paths (no database in a unit test) plus
// the request-shape guards. The security-relevant branch — dismiss owner
// mismatch => 403 — needs a live users table and is verified by the CI Postgres
// job / manual review (see docs/plans/2026-09-04-phase2-feed-to-go.md). db.Pool
// is nil here, so a well-formed authenticated dismiss lands on the 503 DB guard,
// which is the assertion below.

func router() *gin.Engine {
	gin.SetMode(gin.TestMode)
	r := gin.New()
	r.GET("/api/v1/support/metrics", GetSupportMetrics)
	r.GET("/api/v1/integrations/zotero/auth", ZoteroAuthInit)
	r.GET("/api/v1/integrations/zotero/callback", ZoteroAuthCallback)
	r.POST("/api/v1/integrations/zotero/sync", ZoteroSyncPapers)

	// Mirror main.go: dismiss sits behind the real auth middleware.
	grp := r.Group("/api/v1/daily_feed")
	grp.Use(auth.VerifyUser())
	grp.POST("/dismiss", DismissDailyFeedItem)
	return r
}

func do(r *gin.Engine, method, path, body string, headers map[string]string) *httptest.ResponseRecorder {
	var rdr *strings.Reader
	if body != "" {
		rdr = strings.NewReader(body)
	} else {
		rdr = strings.NewReader("")
	}
	req := httptest.NewRequest(method, path, rdr)
	if body != "" {
		req.Header.Set("Content-Type", "application/json")
	}
	for k, v := range headers {
		req.Header.Set(k, v)
	}
	w := httptest.NewRecorder()
	r.ServeHTTP(w, req)
	return w
}

// ── support metrics ─────────────────────────────────────────────────────────

func TestSupportMetrics_ShapeAndStatus(t *testing.T) {
	w := do(router(), http.MethodGet, "/api/v1/support/metrics", "", nil)
	if w.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200", w.Code)
	}
	var body map[string]any
	if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
		t.Fatalf("body not JSON: %v", err)
	}
	for _, key := range []string{"sla_targets", "performance_metrics", "queue_status"} {
		if _, ok := body[key].(map[string]any); !ok {
			t.Fatalf("missing/!object key %q in %s", key, w.Body.String())
		}
	}
	qs := body["queue_status"].(map[string]any)
	if qs["zendesk_integration_status"] != "operational" {
		t.Fatalf("zendesk_integration_status = %v, want operational", qs["zendesk_integration_status"])
	}
}

// ── zotero stubs ────────────────────────────────────────────────────────────

func TestZoteroAuth_RequiresUserID(t *testing.T) {
	w := do(router(), http.MethodGet, "/api/v1/integrations/zotero/auth", "", nil)
	if w.Code != http.StatusUnprocessableEntity {
		t.Fatalf("no user_id: status = %d, want 422", w.Code)
	}
}

func TestZoteroAuth_ReturnsMockURLWithUserID(t *testing.T) {
	w := do(router(), http.MethodGet, "/api/v1/integrations/zotero/auth?user_id=u1", "", nil)
	if w.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200", w.Code)
	}
	var body map[string]string
	_ = json.Unmarshal(w.Body.Bytes(), &body)
	if !strings.Contains(body["authorization_url"], "mock_token_skolab_u1") {
		t.Fatalf("authorization_url = %q, want it to embed the user id", body["authorization_url"])
	}
}

func TestZoteroCallback_RequiresBothParams(t *testing.T) {
	w := do(router(), http.MethodGet, "/api/v1/integrations/zotero/callback?oauth_token=t", "", nil)
	if w.Code != http.StatusUnprocessableEntity {
		t.Fatalf("missing oauth_verifier: status = %d, want 422", w.Code)
	}
}

func TestZoteroCallback_OKWithBothParams(t *testing.T) {
	w := do(router(), http.MethodGet, "/api/v1/integrations/zotero/callback?oauth_token=t&oauth_verifier=v", "", nil)
	if w.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200", w.Code)
	}
	var body map[string]any
	_ = json.Unmarshal(w.Body.Bytes(), &body)
	if body["zotero_user_id"] != "8765432" {
		t.Fatalf("zotero_user_id = %v, want 8765432", body["zotero_user_id"])
	}
}

func TestZoteroSync_EchoesTitles(t *testing.T) {
	body := `{"user_id":"u1","papers":[{"title":"Paper A"},{"foo":"bar"}]}`
	w := do(router(), http.MethodPost, "/api/v1/integrations/zotero/sync", body, nil)
	if w.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200", w.Code)
	}
	var resp struct {
		SyncedCount  int      `json:"synced_count"`
		SyncedPapers []string `json:"synced_papers"`
	}
	if err := json.Unmarshal(w.Body.Bytes(), &resp); err != nil {
		t.Fatalf("body: %v (%s)", err, w.Body.String())
	}
	if resp.SyncedCount != 2 {
		t.Fatalf("synced_count = %d, want 2", resp.SyncedCount)
	}
	if resp.SyncedPapers[0] != "Paper A" || resp.SyncedPapers[1] != "Untitled Paper" {
		t.Fatalf("synced_papers = %v, want [Paper A, Untitled Paper]", resp.SyncedPapers)
	}
}

func TestZoteroSync_MalformedBodyIs400(t *testing.T) {
	w := do(router(), http.MethodPost, "/api/v1/integrations/zotero/sync", "not json", nil)
	if w.Code != http.StatusBadRequest {
		t.Fatalf("status = %d, want 400", w.Code)
	}
}

// ── daily_feed/dismiss ─────────────────────────────────────────────────────

// No Authorization header => auth.VerifyUser() aborts with 401 before the
// handler runs.
func TestDismiss_NoTokenIs401(t *testing.T) {
	body := `{"author_id":"A1","work_id":"W1"}`
	w := do(router(), http.MethodPost, "/api/v1/daily_feed/dismiss", body, nil)
	if w.Code != http.StatusUnauthorized {
		t.Fatalf("status = %d, want 401", w.Code)
	}
}

// A malformed body is rejected before any DB work, regardless of auth backend.
func TestDismiss_MalformedBodyIs400(t *testing.T) {
	w := do(router(), http.MethodPost, "/api/v1/daily_feed/dismiss", "not json",
		map[string]string{"Authorization": "Bearer devtoken"})
	if w.Code != http.StatusBadRequest {
		t.Fatalf("status = %d, want 400", w.Code)
	}
}

// Missing a required field (work_id) is also a 400 from binding.
func TestDismiss_MissingFieldIs400(t *testing.T) {
	w := do(router(), http.MethodPost, "/api/v1/daily_feed/dismiss", `{"author_id":"A1"}`,
		map[string]string{"Authorization": "Bearer devtoken"})
	if w.Code != http.StatusBadRequest {
		t.Fatalf("status = %d, want 400", w.Code)
	}
}

// Well-formed + authenticated (dev_user, since authClient is nil in a unit
// test and GIN_MODE != release) but db.Pool == nil => 503 DB guard. The
// owner-mismatch 403 branch lives just past this point and needs a live DB.
func TestDismiss_NoDBIs503(t *testing.T) {
	w := do(router(), http.MethodPost, "/api/v1/daily_feed/dismiss", `{"author_id":"A1","work_id":"W1"}`,
		map[string]string{"Authorization": "Bearer devtoken"})
	if w.Code != http.StatusServiceUnavailable {
		t.Fatalf("status = %d, want 503 (db.Pool nil in unit test)", w.Code)
	}
}
