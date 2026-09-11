package feed

import (
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
