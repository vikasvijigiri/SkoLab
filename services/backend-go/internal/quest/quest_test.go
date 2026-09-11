package quest

import (
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/gin-gonic/gin"
)

// db.Pool is nil in a unit test (no Postgres service container here — see the
// identical rationale in internal/feed/feed_test.go), so these tests cover
// the input-validation and no-DB guard paths. The 2026-09-11 backend
// response audit found this package had no test file at all.

func init() {
	gin.SetMode(gin.TestMode)
}

func do(r *gin.Engine, method, path, body string) *httptest.ResponseRecorder {
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
	w := httptest.NewRecorder()
	r.ServeHTTP(w, req)
	return w
}

// ── leaderboard ──────────────────────────────────────────────────────────────

func TestGetLeaderboard_NoDBIs503(t *testing.T) {
	r := gin.New()
	r.GET("/api/v1/leaderboard/:field", GetLeaderboard)

	w := do(r, http.MethodGet, "/api/v1/leaderboard/Physics", "")

	if w.Code != http.StatusServiceUnavailable {
		t.Fatalf("status = %d, want 503 (db.Pool nil in unit test)", w.Code)
	}
}

func TestGetLeaderboard_AllFieldsAliasAlsoNoDBIs503(t *testing.T) {
	// "all"/"any"/""/"all fields" take a different query branch than a named
	// field -- both must hit the same nil-pool guard before either runs.
	r := gin.New()
	r.GET("/api/v1/leaderboard/:field", GetLeaderboard)

	w := do(r, http.MethodGet, "/api/v1/leaderboard/all", "")

	if w.Code != http.StatusServiceUnavailable {
		t.Fatalf("status = %d, want 503", w.Code)
	}
}

// ── GetUserQuests ────────────────────────────────────────────────────────────

func TestGetUserQuests_MissingUserIDIs400(t *testing.T) {
	proxyCalled := false
	proxy := func(c *gin.Context) { proxyCalled = true }

	r := gin.New()
	r.GET("/api/v1/users/quests", GetUserQuests(proxy))

	w := do(r, http.MethodGet, "/api/v1/users/quests", "")

	if w.Code != http.StatusBadRequest {
		t.Fatalf("status = %d, want 400", w.Code)
	}
	if proxyCalled {
		t.Fatal("proxy fallback must not run when user_id is missing")
	}
}

func TestGetUserQuests_NoDBFallsThroughToProxy(t *testing.T) {
	// db.Pool == nil: with no database at all, Python is the only place that
	// can serve or generate quests -- the handler must delegate, not 503.
	proxyCalled := false
	proxy := func(c *gin.Context) {
		proxyCalled = true
		c.JSON(http.StatusOK, gin.H{"proxied": true})
	}

	r := gin.New()
	r.GET("/api/v1/users/quests", GetUserQuests(proxy))

	w := do(r, http.MethodGet, "/api/v1/users/quests?user_id=u1", "")

	if !proxyCalled {
		t.Fatal("expected the proxy fallback to run when db.Pool is nil")
	}
	if w.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200 (from the proxy stub)", w.Code)
	}
}

// ── CompleteQuest ────────────────────────────────────────────────────────────

func TestCompleteQuest_MissingParamsIs400(t *testing.T) {
	r := gin.New()
	r.POST("/api/v1/users/quests/complete", CompleteQuest)

	cases := []string{
		"/api/v1/users/quests/complete",
		"/api/v1/users/quests/complete?user_id=u1",
		"/api/v1/users/quests/complete?quest_id=q1",
	}
	for _, path := range cases {
		w := do(r, http.MethodPost, path, "")
		if w.Code != http.StatusBadRequest {
			t.Fatalf("%s: status = %d, want 400", path, w.Code)
		}
	}
}

func TestCompleteQuest_NoDBIs503(t *testing.T) {
	r := gin.New()
	r.POST("/api/v1/users/quests/complete", CompleteQuest)

	w := do(r, http.MethodPost, "/api/v1/users/quests/complete?user_id=u1&quest_id=q1", "")

	if w.Code != http.StatusServiceUnavailable {
		t.Fatalf("status = %d, want 503 (db.Pool nil in unit test)", w.Code)
	}
}
