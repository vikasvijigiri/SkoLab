package user

import (
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/skolab/backend-go/internal/auth"
)

// db.Pool is nil in a unit test (no Postgres service container here — see the
// identical rationale in internal/feed/feed_test.go and internal/quest/
// quest_test.go), so the handler tests below cover input-validation and the
// no-DB guard paths. The 2026-09-11 backend response audit found this
// package had no test file at all, and that SyncUserProfile/DeleteUser were
// missing the nil-pool guard every sibling handler has (now fixed above).

func router() *gin.Engine {
	gin.SetMode(gin.TestMode)
	r := gin.New()

	usersAPI := r.Group("/api/v1/users")
	usersAPI.Use(auth.VerifyUser())
	{
		usersAPI.POST("/profile/sync", SyncUserProfile)
		usersAPI.DELETE("/:userId", DeleteUser)
	}

	memoryAPI := r.Group("/api/v1/user_memory")
	memoryAPI.Use(auth.VerifyUser())
	{
		memoryAPI.POST("/events", SyncUserMemoryEvents)
		memoryAPI.GET("/:userId", GetUserMemory)
	}
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

var devAuth = map[string]string{"Authorization": "Bearer devtoken"}

// ── SyncUserProfile ──────────────────────────────────────────────────────────

func TestSyncUserProfile_MalformedBodyIs400(t *testing.T) {
	w := do(router(), http.MethodPost, "/api/v1/users/profile/sync", "not json", devAuth)
	if w.Code != http.StatusBadRequest {
		t.Fatalf("status = %d, want 400", w.Code)
	}
}

func TestSyncUserProfile_MissingRequiredFieldIs400(t *testing.T) {
	w := do(router(), http.MethodPost, "/api/v1/users/profile/sync", `{"name":"Ada"}`, devAuth)
	if w.Code != http.StatusBadRequest {
		t.Fatalf("status = %d, want 400 (missing uid)", w.Code)
	}
}

func TestSyncUserProfile_NoDBIs503(t *testing.T) {
	// Regression test: this handler used to call db.Pool.Exec with no nil
	// check at all, which panics (into gin.Recovery's generic 500) instead
	// of the clean 503 every sibling handler gives when the DB is down.
	w := do(router(), http.MethodPost, "/api/v1/users/profile/sync",
		`{"uid":"u1","name":"Ada Lovelace"}`, devAuth)
	if w.Code != http.StatusServiceUnavailable {
		t.Fatalf("status = %d, want 503 (db.Pool nil in unit test)", w.Code)
	}
}

// ── DeleteUser ───────────────────────────────────────────────────────────────

func TestDeleteUser_MismatchedUserIs403(t *testing.T) {
	// auth.VerifyUser() sets user_id="dev_user" in dev/CI mode (see
	// internal/auth/firebase_test.go) -- a path param that doesn't match it
	// is exactly the IDOR shape this check exists to reject.
	w := do(router(), http.MethodDelete, "/api/v1/users/someone_else", "", devAuth)
	if w.Code != http.StatusForbidden {
		t.Fatalf("status = %d, want 403", w.Code)
	}
}

func TestDeleteUser_NoDBIs503(t *testing.T) {
	// Regression test: same missing-guard bug as SyncUserProfile, for the
	// GDPR delete path -- must not panic when the DB is unavailable.
	w := do(router(), http.MethodDelete, "/api/v1/users/dev_user", "", devAuth)
	if w.Code != http.StatusServiceUnavailable {
		t.Fatalf("status = %d, want 503 (db.Pool nil in unit test)", w.Code)
	}
}

// ── SyncUserMemoryEvents ─────────────────────────────────────────────────────

func TestSyncUserMemoryEvents_MalformedBodyIs400(t *testing.T) {
	w := do(router(), http.MethodPost, "/api/v1/user_memory/events", "not json", devAuth)
	if w.Code != http.StatusBadRequest {
		t.Fatalf("status = %d, want 400", w.Code)
	}
}

func TestSyncUserMemoryEvents_EmptyEventsIsNoOpSuccess(t *testing.T) {
	// Explicitly short-circuits before ever touching db.Pool -- must succeed
	// even with no database configured.
	w := do(router(), http.MethodPost, "/api/v1/user_memory/events",
		`{"user_id":"u1","events":[]}`, devAuth)
	if w.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200", w.Code)
	}
}

func TestSyncUserMemoryEvents_NoDBIs503(t *testing.T) {
	body := `{"user_id":"u1","events":[{"type":"PAPER_CLOSED","paperTitle":"On Computing"}]}`
	w := do(router(), http.MethodPost, "/api/v1/user_memory/events", body, devAuth)
	if w.Code != http.StatusServiceUnavailable {
		t.Fatalf("status = %d, want 503 (db.Pool nil in unit test)", w.Code)
	}
}

// ── GetUserMemory ────────────────────────────────────────────────────────────

func TestGetUserMemory_NoDBIs503(t *testing.T) {
	w := do(router(), http.MethodGet, "/api/v1/user_memory/u1", "", devAuth)
	if w.Code != http.StatusServiceUnavailable {
		t.Fatalf("status = %d, want 503 (db.Pool nil in unit test, cache empty)", w.Code)
	}
}

// ── aggregateMemory — pure function, no DB, previously untested entirely ────

func TestAggregateMemory_EmptyLogsReturnsBaselineProfile(t *testing.T) {
	profile := aggregateMemory("u1", nil)

	if profile.UserID != "u1" {
		t.Fatalf("UserID = %q, want u1", profile.UserID)
	}
	if profile.ReadingPace != "" || profile.ResearchStyle != "" {
		t.Fatalf("expected zero-value pace/style for no history, got pace=%q style=%q",
			profile.ReadingPace, profile.ResearchStyle)
	}
	if profile.TotalPapersRead != 0 || len(profile.TopTopics) != 0 {
		t.Fatalf("expected an empty profile, got %+v", profile)
	}
}

func TestAggregateMemory_ClassifiesReadingPaceFromPaperClosedDurations(t *testing.T) {
	// avgReadMinutes = 400s / 60 ≈ 6.67 min -> deep_reader (>= 5.0).
	logs := []logRow{
		{EventType: "PAPER_CLOSED", Meta: map[string]any{"paperTitle": "Paper A", "durationSeconds": float64(400)}, CreatedAt: time.Now()},
		{EventType: "PAPER_CLOSED", Meta: map[string]any{"paperTitle": "Paper B", "durationSeconds": float64(400)}, CreatedAt: time.Now()},
	}

	profile := aggregateMemory("u1", logs)

	if profile.ReadingPace != "deep_reader" {
		t.Fatalf("ReadingPace = %q, want deep_reader", profile.ReadingPace)
	}
	if profile.TotalPapersRead != 2 {
		t.Fatalf("TotalPapersRead = %d, want 2 (both sessions >= 90s)", profile.TotalPapersRead)
	}
	if len(profile.RecentlyReadPapers) != 2 {
		t.Fatalf("RecentlyReadPapers = %v, want both titles", profile.RecentlyReadPapers)
	}
}

func TestAggregateMemory_ShortSessionsCountAsUnfinishedNotRead(t *testing.T) {
	// duration in [1, 90) -> unfinished; a paper the reader opened but bounced off.
	logs := []logRow{
		{EventType: "PAPER_CLOSED", Meta: map[string]any{"paperTitle": "Skimmed paper", "durationSeconds": float64(30)}, CreatedAt: time.Now()},
	}

	profile := aggregateMemory("u1", logs)

	if profile.TotalPapersRead != 0 {
		t.Fatalf("TotalPapersRead = %d, want 0 (session under the 90s read threshold)", profile.TotalPapersRead)
	}
	if len(profile.UnfinishedPapers) != 1 || profile.UnfinishedPapers[0] != "Skimmed paper" {
		t.Fatalf("UnfinishedPapers = %v, want [Skimmed paper]", profile.UnfinishedPapers)
	}
}

func TestAggregateMemory_ResearchStyleReflectsDomainBreadth(t *testing.T) {
	cases := []struct {
		name     string
		domains  []string
		expected string
	}{
		{"single domain", []string{"physics"}, "exploratory"},
		{"two domains", []string{"physics", "biology"}, "focused"},
		{"four-plus domains", []string{"physics", "biology", "chemistry", "geology"}, "interdisciplinary"},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			var logs []logRow
			for _, d := range tc.domains {
				logs = append(logs, logRow{
					EventType: "PAPER_VIEWED",
					Meta:      map[string]any{"paperDomain": d},
					CreatedAt: time.Now(),
				})
			}
			profile := aggregateMemory("u1", logs)
			if profile.ResearchStyle != tc.expected {
				t.Fatalf("ResearchStyle = %q, want %q for domains %v", profile.ResearchStyle, tc.expected, tc.domains)
			}
		})
	}
}

func TestAggregateMemory_TracksFrequentCollaborators(t *testing.T) {
	logs := []logRow{
		{EventType: "COLLAB_VIEWED", Meta: map[string]any{"collaboratorName": "Marie Curie"}, CreatedAt: time.Now()},
		{EventType: "COLLAB_VIEWED", Meta: map[string]any{"collaboratorName": "Marie Curie"}, CreatedAt: time.Now()},
		{EventType: "AUTHOR_VIEWED", Meta: map[string]any{"authorName": "Niels Bohr"}, CreatedAt: time.Now()},
	}

	profile := aggregateMemory("u1", logs)

	if len(profile.FrequentCollaborators) != 2 {
		t.Fatalf("FrequentCollaborators = %v, want 2 distinct names", profile.FrequentCollaborators)
	}
	if profile.FrequentCollaborators[0] != "Marie Curie" {
		t.Fatalf("FrequentCollaborators[0] = %q, want the more frequent name (Marie Curie) ranked first",
			profile.FrequentCollaborators[0])
	}
}
