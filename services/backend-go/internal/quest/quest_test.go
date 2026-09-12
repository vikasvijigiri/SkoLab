package quest

import (
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/gin-gonic/gin"
	"github.com/skolab/backend-go/internal/auth"
)

// devAuth is a Bearer token that auth.VerifyUser() accepts in dev/CI mode
// (no Firebase credentials configured), setting user_id="dev_user" -- see
// internal/auth/firebase_test.go and internal/user/user_test.go, which use
// the identical pattern.
var devAuth = map[string]string{"Authorization": "Bearer devtoken"}

// db.Pool is nil in a unit test (no Postgres service container here — see the
// identical rationale in internal/feed/feed_test.go), so these tests cover
// the input-validation and no-DB guard paths. The 2026-09-11 backend
// response audit found this package had no test file at all. The 2026-09-12
// endpoint audit then found GetUserQuests/CompleteQuest never checked their
// ?user_id= against the verified caller -- these routes are registered
// behind auth.VerifyUser() in main.go, so the tests below do the same
// instead of registering the handlers bare.

func init() {
	gin.SetMode(gin.TestMode)
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

// ── leaderboard ──────────────────────────────────────────────────────────────
// Public — no user_id, no auth required.

func TestGetLeaderboard_NoDBIs503(t *testing.T) {
	r := gin.New()
	r.GET("/api/v1/leaderboard/:field", GetLeaderboard)

	w := do(r, http.MethodGet, "/api/v1/leaderboard/Physics", "", nil)

	if w.Code != http.StatusServiceUnavailable {
		t.Fatalf("status = %d, want 503 (db.Pool nil in unit test)", w.Code)
	}
}

func TestGetLeaderboard_AllFieldsAliasAlsoNoDBIs503(t *testing.T) {
	// "all"/"any"/""/"all fields" take a different query branch than a named
	// field -- both must hit the same nil-pool guard before either runs.
	r := gin.New()
	r.GET("/api/v1/leaderboard/:field", GetLeaderboard)

	w := do(r, http.MethodGet, "/api/v1/leaderboard/all", "", nil)

	if w.Code != http.StatusServiceUnavailable {
		t.Fatalf("status = %d, want 503", w.Code)
	}
}

// ── GetUserQuests ────────────────────────────────────────────────────────────
// Behind auth.VerifyUser() in main.go — mirror that here so user_id
// ownership checks have a real verified identity to compare against.

func questsRouter(proxy gin.HandlerFunc) *gin.Engine {
	r := gin.New()
	api := r.Group("/api/v1")
	api.Use(auth.VerifyUser())
	{
		api.GET("/users/quests", GetUserQuests(proxy))
		api.POST("/users/quests/complete", CompleteQuest)
	}
	return r
}

func TestGetUserQuests_MissingUserIDIs400(t *testing.T) {
	proxyCalled := false
	proxy := func(c *gin.Context) { proxyCalled = true }

	w := do(questsRouter(proxy), http.MethodGet, "/api/v1/users/quests", "", devAuth)

	if w.Code != http.StatusBadRequest {
		t.Fatalf("status = %d, want 400", w.Code)
	}
	if proxyCalled {
		t.Fatal("proxy fallback must not run when user_id is missing")
	}
}

func TestGetUserQuests_MismatchedUserIDIs403(t *testing.T) {
	// auth.VerifyUser() sets user_id="dev_user" in dev/CI mode -- a
	// ?user_id= that doesn't match it is exactly the IDOR shape this check
	// exists to reject (2026-09-12 endpoint audit).
	proxyCalled := false
	proxy := func(c *gin.Context) { proxyCalled = true }

	w := do(questsRouter(proxy), http.MethodGet, "/api/v1/users/quests?user_id=someone_else", "", devAuth)

	if w.Code != http.StatusForbidden {
		t.Fatalf("status = %d, want 403", w.Code)
	}
	if proxyCalled {
		t.Fatal("proxy fallback must not run for a mismatched user_id")
	}
}

func TestGetUserQuests_NoDBFallsThroughToProxy(t *testing.T) {
	// db.Pool == nil: with no database at all, Python is the only place that
	// can serve or generate quests -- the handler must delegate, not 503.
	// user_id must be "dev_user" (the verified identity) or the ownership
	// check above 403s before this path is even reached.
	proxyCalled := false
	proxy := func(c *gin.Context) {
		proxyCalled = true
		c.JSON(http.StatusOK, gin.H{"proxied": true})
	}

	w := do(questsRouter(proxy), http.MethodGet, "/api/v1/users/quests?user_id=dev_user", "", devAuth)

	if !proxyCalled {
		t.Fatal("expected the proxy fallback to run when db.Pool is nil")
	}
	if w.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200 (from the proxy stub)", w.Code)
	}
}

// ── CompleteQuest ────────────────────────────────────────────────────────────

func TestCompleteQuest_MissingParamsIs400(t *testing.T) {
	r := questsRouter(func(c *gin.Context) {})

	cases := []string{
		"/api/v1/users/quests/complete",
		"/api/v1/users/quests/complete?user_id=dev_user",
		"/api/v1/users/quests/complete?quest_id=q1",
	}
	for _, path := range cases {
		w := do(r, http.MethodPost, path, "", devAuth)
		if w.Code != http.StatusBadRequest {
			t.Fatalf("%s: status = %d, want 400", path, w.Code)
		}
	}
}

func TestCompleteQuest_MismatchedUserIDIs403(t *testing.T) {
	// Without this check, an authenticated caller could complete quests
	// (and award themselves the resulting entropy) for an arbitrary other
	// user_id (2026-09-12 endpoint audit).
	r := questsRouter(func(c *gin.Context) {})

	w := do(r, http.MethodPost, "/api/v1/users/quests/complete?user_id=someone_else&quest_id=q1", "", devAuth)

	if w.Code != http.StatusForbidden {
		t.Fatalf("status = %d, want 403", w.Code)
	}
}

func TestCompleteQuest_NoDBIs503(t *testing.T) {
	r := questsRouter(func(c *gin.Context) {})

	w := do(r, http.MethodPost, "/api/v1/users/quests/complete?user_id=dev_user&quest_id=q1", "", devAuth)

	if w.Code != http.StatusServiceUnavailable {
		t.Fatalf("status = %d, want 503 (db.Pool nil in unit test)", w.Code)
	}
}
