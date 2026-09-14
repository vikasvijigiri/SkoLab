package activity

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/skolab/backend-go/internal/auth"
	"github.com/skolab/backend-go/internal/firestore"
	"github.com/skolab/backend-go/internal/services/openalex"
)

func TestCleanID(t *testing.T) {
	cases := map[string]string{
		"https://openalex.org/A5023888391": "A5023888391",
		"A5023888391":                      "A5023888391",
		"  w12345 ":                        "W12345",
		"":                                 "",
	}
	for in, want := range cases {
		if got := cleanID(in); got != want {
			t.Errorf("cleanID(%q) = %q, want %q", in, got, want)
		}
	}
}

func TestClampLimit(t *testing.T) {
	if got := clampLimit("", 20, 40); got != 20 {
		t.Errorf("empty → %d, want default 20", got)
	}
	if got := clampLimit("0", 20, 40); got != 20 {
		t.Errorf("0 → %d, want default 20", got)
	}
	if got := clampLimit("500", 20, 40); got != 40 {
		t.Errorf("500 → %d, want max 40", got)
	}
	if got := clampLimit("7", 20, 40); got != 7 {
		t.Errorf("7 → %d, want 7", got)
	}
}

func TestEnvInt(t *testing.T) {
	if got := envInt("ACTIVITY_NO_SUCH_KEY_123", 9); got != 9 {
		t.Errorf("unset → %d, want fallback 9", got)
	}
	t.Setenv("ACTIVITY_TMP_KEY", "13")
	if got := envInt("ACTIVITY_TMP_KEY", 9); got != 13 {
		t.Errorf("set 13 → %d", got)
	}
	t.Setenv("ACTIVITY_TMP_KEY", "-4")
	if got := envInt("ACTIVITY_TMP_KEY", 9); got != 9 {
		t.Errorf("negative → %d, want fallback 9", got)
	}
}

func TestParseDate(t *testing.T) {
	if !parseDate("").IsZero() {
		t.Error("empty string should be the zero time")
	}
	if !parseDate("not-a-date").IsZero() {
		t.Error("malformed string should be the zero time")
	}
	got := parseDate("2026-08-14")
	if got.Year() != 2026 || got.Month() != time.August || got.Day() != 14 {
		t.Errorf("parseDate(2026-08-14) = %v", got)
	}
}

func TestAuthorNames(t *testing.T) {
	as := []openalex.Authorship{
		{Author: openalex.AuthorRef{DisplayName: "Ada L."}},
		{Author: openalex.AuthorRef{DisplayName: ""}},
		{Author: openalex.AuthorRef{DisplayName: "Grace H."}},
		{Author: openalex.AuthorRef{DisplayName: "Katherine J."}},
	}
	got := authorNames(as, 2)
	if len(got) != 2 || got[0] != "Ada L." || got[1] != "Grace H." {
		t.Fatalf("authorNames cap/skip-blank failed: %v", got)
	}
	if n := authorNames(nil, 3); len(n) != 0 {
		t.Fatalf("nil authorships → %v, want empty", n)
	}
}

// With no DB pool and no query params every source is skipped: the handler must
// still answer 200 with a JSON array (never null) and degraded=true.
func TestGetActivityFeed_NilPoolCleanShape(t *testing.T) {
	gin.SetMode(gin.TestMode)
	r := gin.New()
	r.GET("/activity_feed", GetActivityFeed)

	req := httptest.NewRequest(http.MethodGet, "/activity_feed", nil)
	w := httptest.NewRecorder()
	r.ServeHTTP(w, req)

	if w.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200", w.Code)
	}
	var body FeedResponse
	if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
		t.Fatalf("body not JSON: %v — %s", err, w.Body.String())
	}
	if body.Items == nil {
		t.Fatal("items serialised as null; want []")
	}
	if len(body.Items) != 0 {
		t.Fatalf("expected no items with nil pool, got %d", len(body.Items))
	}
	if !body.Degraded {
		t.Fatal("degraded should be true when the network could not be read")
	}
}

func TestGetActivityFeed_LimitParamAccepted(t *testing.T) {
	gin.SetMode(gin.TestMode)
	r := gin.New()
	r.GET("/activity_feed", GetActivityFeed)

	req := httptest.NewRequest(http.MethodGet, "/activity_feed?user_id=&author_id=&limit=5", nil)
	w := httptest.NewRecorder()
	r.ServeHTTP(w, req)

	if w.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200", w.Code)
	}
}

// devAuth is a Bearer token that auth.VerifyUser()/VerifyUserOptional()
// accept in dev/CI mode (no Firebase credentials configured), setting
// user_id="dev_user" -- see internal/auth/firebase_test.go.
var devAuth = map[string]string{"Authorization": "Bearer devtoken"}

// optionalAuthRouter mirrors main.go's actual wiring for this route
// (auth.VerifyUserOptional(), not a hard VerifyUser(), so an anonymous
// caller still gets the public trending floor).
func optionalAuthRouter() *gin.Engine {
	gin.SetMode(gin.TestMode)
	r := gin.New()
	r.GET("/activity_feed", auth.VerifyUserOptional(), GetActivityFeed)
	return r
}

// fakeVerifiedUser stands in for a real Firebase token verification,
// setting the same context key VerifyUser()/VerifyUserOptional() would once
// a token actually checks out. Needed because VerifyUserOptional has no
// dev/CI dev_user fallback the way VerifyUser does -- with no Firebase
// client configured (true in CI) it just leaves user_id unset regardless of
// what Authorization header is sent, so devAuth alone can't simulate "an
// authenticated caller" through it the way it can through VerifyUser.
func fakeVerifiedUser(uid string) gin.HandlerFunc {
	return func(c *gin.Context) {
		c.Set("user_id", uid)
		c.Next()
	}
}

func verifiedUserRouter(uid string) *gin.Engine {
	gin.SetMode(gin.TestMode)
	r := gin.New()
	r.GET("/activity_feed", fakeVerifiedUser(uid), GetActivityFeed)
	return r
}

func TestGetActivityFeed_AnonymousNoUserIDStillOK(t *testing.T) {
	// The public trending floor must keep working with zero auth.
	req := httptest.NewRequest(http.MethodGet, "/activity_feed", nil)
	w := httptest.NewRecorder()
	optionalAuthRouter().ServeHTTP(w, req)
	if w.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200", w.Code)
	}
}

func TestGetActivityFeed_MismatchedUserIDIs403(t *testing.T) {
	// Before this check, GetActivityFeed had no auth middleware at all and
	// trusted ?user_id= outright -- any caller could read another user's
	// connection-derived feed (who they're connected to, at what
	// institution, recent activity) by supplying that user's id
	// (2026-09-12 endpoint audit).
	req := httptest.NewRequest(http.MethodGet, "/activity_feed?user_id=someone_else", nil)
	req.Header.Set("Authorization", devAuth["Authorization"])
	w := httptest.NewRecorder()
	optionalAuthRouter().ServeHTTP(w, req)
	if w.Code != http.StatusForbidden {
		t.Fatalf("status = %d, want 403", w.Code)
	}
}

func TestGetActivityFeed_UnauthenticatedWithUserIDIs403(t *testing.T) {
	// A caller with no token at all supplying a user_id must also be
	// rejected, not silently treated as anonymous-with-a-hint.
	req := httptest.NewRequest(http.MethodGet, "/activity_feed?user_id=someone_else", nil)
	w := httptest.NewRecorder()
	optionalAuthRouter().ServeHTTP(w, req)
	if w.Code != http.StatusForbidden {
		t.Fatalf("status = %d, want 403", w.Code)
	}
}

func TestGetActivityFeed_OwnUserIDIsAccepted(t *testing.T) {
	req := httptest.NewRequest(http.MethodGet, "/activity_feed?user_id=dev_user", nil)
	w := httptest.NewRecorder()
	verifiedUserRouter("dev_user").ServeHTTP(w, req)
	if w.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200", w.Code)
	}
}

// With no Firestore client configured (true in CI, same as the nil-pool
// case), a user_id + author_id request must still answer 200 rather than
// blocking on or erroring from the new tracked/citation sources — they
// degrade to "no items" exactly like peerPublications/connectionEvents do
// with a nil db.Pool. This also guarantees no live network call is made:
// citationAlert is gated on author_id being non-empty, so an empty author_id
// (as here) must never reach fetchAuthorHook.
func TestGetActivityFeed_TrackedAndCitationSourcesDegradeCleanly(t *testing.T) {
	req := httptest.NewRequest(http.MethodGet, "/activity_feed?user_id=dev_user&author_id=", nil)
	w := httptest.NewRecorder()
	verifiedUserRouter("dev_user").ServeHTTP(w, req)
	if w.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200", w.Code)
	}
	var body FeedResponse
	if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
		t.Fatalf("body not JSON: %v — %s", err, w.Body.String())
	}
}

// ── trackedResearcherIDs (decisions/0021 Track feeds decisions/0022 Signals) ─

func TestTrackedResearcherIDs_BuildsMapFromFirestoreDocs(t *testing.T) {
	orig := firestoreListDocsHook
	defer func() { firestoreListDocsHook = orig }()

	var gotCollection string
	firestoreListDocsHook = func(_ context.Context, collection string, limit int) ([]map[string]any, error) {
		gotCollection = collection
		return []map[string]any{
			{"authorId": "https://openalex.org/A111", "name": "Ada Lovelace", "trackedAt": int64(1)},
			{"authorId": "", "name": "Should be skipped"},
			{"authorId": "a222", "name": "Grace Hopper"},
		}, nil
	}

	got := trackedResearcherIDs(context.Background(), "u1")
	if gotCollection != "users/u1/tracked_researchers" {
		t.Errorf("collection = %q, want users/u1/tracked_researchers", gotCollection)
	}
	want := map[string]string{"A111": "Ada Lovelace", "A222": "Grace Hopper"}
	if len(got) != len(want) || got["A111"] != want["A111"] || got["A222"] != want["A222"] {
		t.Fatalf("trackedResearcherIDs = %+v, want %+v", got, want)
	}
}

func TestTrackedResearcherIDs_EmptyOrErrorIsCleanMiss(t *testing.T) {
	orig := firestoreListDocsHook
	defer func() { firestoreListDocsHook = orig }()

	firestoreListDocsHook = func(context.Context, string, int) ([]map[string]any, error) { return nil, nil }
	if got := trackedResearcherIDs(context.Background(), "u1"); got != nil {
		t.Errorf("empty collection → %+v, want nil", got)
	}

	firestoreListDocsHook = func(context.Context, string, int) ([]map[string]any, error) {
		return nil, fmt.Errorf("boom")
	}
	if got := trackedResearcherIDs(context.Background(), "u1"); got != nil {
		t.Errorf("firestore error → %+v, want nil", got)
	}
}

// ── trackedTopicIDs / trackedTopicActivity (decisions/0022 topic-follow gap) ─

func TestTrackedTopicIDs_BuildsMapFromFirestoreDocs(t *testing.T) {
	orig := firestoreListDocsHook
	defer func() { firestoreListDocsHook = orig }()

	var gotCollection string
	firestoreListDocsHook = func(_ context.Context, collection string, limit int) ([]map[string]any, error) {
		gotCollection = collection
		return []map[string]any{
			{"topicId": "https://openalex.org/T111", "name": "Quantum computing", "trackedAt": int64(1)},
			{"topicId": "", "name": "Should be skipped"},
			{"topicId": "t222", "name": "Neural coding"},
		}, nil
	}

	got := trackedTopicIDs(context.Background(), "u1")
	if gotCollection != "users/u1/tracked_topics" {
		t.Errorf("collection = %q, want users/u1/tracked_topics", gotCollection)
	}
	want := map[string]string{"T111": "Quantum computing", "T222": "Neural coding"}
	if len(got) != len(want) || got["T111"] != want["T111"] || got["T222"] != want["T222"] {
		t.Fatalf("trackedTopicIDs = %+v, want %+v", got, want)
	}
}

func TestTrackedTopicIDs_EmptyOrErrorIsCleanMiss(t *testing.T) {
	orig := firestoreListDocsHook
	defer func() { firestoreListDocsHook = orig }()

	firestoreListDocsHook = func(context.Context, string, int) ([]map[string]any, error) { return nil, nil }
	if got := trackedTopicIDs(context.Background(), "u1"); got != nil {
		t.Errorf("empty collection → %+v, want nil", got)
	}

	firestoreListDocsHook = func(context.Context, string, int) ([]map[string]any, error) {
		return nil, fmt.Errorf("boom")
	}
	if got := trackedTopicIDs(context.Background(), "u1"); got != nil {
		t.Errorf("firestore error → %+v, want nil", got)
	}
}

func TestTrackedTopicActivity_NoTrackedTopicsReturnsNil(t *testing.T) {
	origList := firestoreListDocsHook
	defer func() { firestoreListDocsHook = origList }()
	firestoreListDocsHook = func(context.Context, string, int) ([]map[string]any, error) { return nil, nil }

	if got := trackedTopicActivity(context.Background(), "u1", 75*24*time.Hour); got != nil {
		t.Fatalf("no tracked topics → %+v, want nil", got)
	}
}

func TestTrackedTopicActivity_EmitsHonestCountAndFiltersOutsideWindow(t *testing.T) {
	origList, origFetch := firestoreListDocsHook, fetchWorksByConceptHook
	defer func() { firestoreListDocsHook, fetchWorksByConceptHook = origList, origFetch }()

	firestoreListDocsHook = func(context.Context, string, int) ([]map[string]any, error) {
		return []map[string]any{{"topicId": "T111", "name": "Quantum computing"}}, nil
	}

	now := time.Now()
	inWindow1 := now.AddDate(0, 0, -5).Format("2006-01-02")
	inWindow2 := now.AddDate(0, 0, -10).Format("2006-01-02")
	outOfWindow := now.AddDate(0, 0, -400).Format("2006-01-02")

	fetchWorksByConceptHook = func(_ context.Context, conceptID string, _, _, _ int) ([]openalex.Work, error) {
		if conceptID != "T111" {
			t.Errorf("conceptID = %q, want T111", conceptID)
		}
		return []openalex.Work{
			{ID: "W1", Title: "Recent work one", PublicationDate: inWindow1, CitedByCount: 2},
			{ID: "W2", Title: "Recent work two", PublicationDate: inWindow2, CitedByCount: 1},
			{ID: "W3", Title: "Old work", PublicationDate: outOfWindow, CitedByCount: 9},
		}, nil
	}

	items := trackedTopicActivity(context.Background(), "u1", 75*24*time.Hour)
	if len(items) != 1 {
		t.Fatalf("want exactly one tracked_topic_activity item, got %d: %+v", len(items), items)
	}
	it := items[0]
	if it.Type != "tracked_topic_activity" {
		t.Errorf("type = %q, want tracked_topic_activity", it.Type)
	}
	if it.Count != 2 {
		t.Errorf("count = %d, want 2 (the old work must not be counted)", it.Count)
	}
	if it.Object == nil || it.Object.Kind != "topic" || it.Object.ID != "T111" || it.Object.Title != "Quantum computing" {
		t.Fatalf("object = %+v, want topic T111/Quantum computing", it.Object)
	}
}

func TestTrackedTopicActivity_FetchErrorIsCleanMiss(t *testing.T) {
	origList, origFetch := firestoreListDocsHook, fetchWorksByConceptHook
	defer func() { firestoreListDocsHook, fetchWorksByConceptHook = origList, origFetch }()

	firestoreListDocsHook = func(context.Context, string, int) ([]map[string]any, error) {
		return []map[string]any{{"topicId": "T111", "name": "Quantum computing"}}, nil
	}
	fetchWorksByConceptHook = func(context.Context, string, int, int, int) ([]openalex.Work, error) {
		return nil, fmt.Errorf("openalex down")
	}

	if items := trackedTopicActivity(context.Background(), "u1", 75*24*time.Hour); items != nil {
		t.Fatalf("fetch error should emit nothing, got %+v", items)
	}
}

// ── inboxItems (client-originated mention/invite events) ────────────────────

func TestInboxItems_MapsMentionAndInviteDocsAndDeletesEach(t *testing.T) {
	origList, origDelete := firestoreListDocsWithIDsHook, firestoreDeleteDocHook
	defer func() { firestoreListDocsWithIDsHook, firestoreDeleteDocHook = origList, origDelete }()

	var gotCollection string
	firestoreListDocsWithIDsHook = func(_ context.Context, collection string, limit int) ([]firestore.Doc, error) {
		gotCollection = collection
		return []firestore.Doc{
			{ID: "item1", Data: map[string]any{
				"type":  "mention",
				"actor": map[string]any{"id": "u2", "display_name": "Ada Lovelace"},
				"why":   "hey @You check this out",
				"href":  "/workspace/p1",
				"ts":    "2026-09-10T00:00:00Z",
			}},
			{ID: "item2", Data: map[string]any{
				"type":  "invite",
				"actor": map[string]any{"id": "u3", "display_name": "Grace Hopper"},
				"why":   "Quantum foam",
				"href":  "/workspace/p2",
				"ts":    "2026-09-11T00:00:00Z",
			}},
		}, nil
	}
	var deleted []string
	firestoreDeleteDocHook = func(_ context.Context, _ string, docID string) error {
		deleted = append(deleted, docID)
		return nil
	}

	items := inboxItems(context.Background(), "u1")
	if gotCollection != "users/u1/inbox" {
		t.Errorf("collection = %q, want users/u1/inbox", gotCollection)
	}
	if len(items) != 2 {
		t.Fatalf("want 2 items, got %d: %+v", len(items), items)
	}
	if items[0].Type != "mention" || items[0].Actor == nil || items[0].Actor.DisplayName != "Ada Lovelace" {
		t.Fatalf("item[0] = %+v, want mention from Ada Lovelace", items[0])
	}
	if items[1].Type != "invite" || items[1].Actor == nil || items[1].Actor.DisplayName != "Grace Hopper" {
		t.Fatalf("item[1] = %+v, want invite from Grace Hopper", items[1])
	}
	if len(deleted) != 2 || deleted[0] != "item1" || deleted[1] != "item2" {
		t.Fatalf("deleted = %+v, want both docs drained after being read", deleted)
	}
}

func TestInboxItems_SkipsUnknownType(t *testing.T) {
	origList, origDelete := firestoreListDocsWithIDsHook, firestoreDeleteDocHook
	defer func() { firestoreListDocsWithIDsHook, firestoreDeleteDocHook = origList, origDelete }()

	firestoreListDocsWithIDsHook = func(context.Context, string, int) ([]firestore.Doc, error) {
		return []firestore.Doc{{ID: "item1", Data: map[string]any{"type": "something_else"}}}, nil
	}
	firestoreDeleteDocHook = func(context.Context, string, string) error { return nil }

	if items := inboxItems(context.Background(), "u1"); items != nil {
		t.Fatalf("unknown type should be skipped, got %+v", items)
	}
}

func TestInboxItems_EmptyOrErrorIsCleanMiss(t *testing.T) {
	orig := firestoreListDocsWithIDsHook
	defer func() { firestoreListDocsWithIDsHook = orig }()

	firestoreListDocsWithIDsHook = func(context.Context, string, int) ([]firestore.Doc, error) { return nil, nil }
	if got := inboxItems(context.Background(), "u1"); got != nil {
		t.Errorf("empty collection → %+v, want nil", got)
	}

	firestoreListDocsWithIDsHook = func(context.Context, string, int) ([]firestore.Doc, error) {
		return nil, fmt.Errorf("boom")
	}
	if got := inboxItems(context.Background(), "u1"); got != nil {
		t.Errorf("firestore error → %+v, want nil", got)
	}
}

// ── citationAlert (decisions/0022 citation watermark) ────────────────────────

func TestCitationAlert_FirstCheckSeedsWatermarkAndEmitsNothing(t *testing.T) {
	origFetch, origGet, origSet := fetchAuthorHook, firestoreGetDocHook, firestoreSetDocHook
	defer func() { fetchAuthorHook, firestoreGetDocHook, firestoreSetDocHook = origFetch, origGet, origSet }()

	fetchAuthorHook = func(context.Context, string) (*openalex.Author, error) {
		return &openalex.Author{CitedByCount: 42}, nil
	}
	firestoreGetDocHook = func(context.Context, string, string) (map[string]any, bool, error) {
		return nil, false, nil // no watermark yet
	}
	var setData map[string]any
	firestoreSetDocHook = func(_ context.Context, _, _ string, data map[string]any) error {
		setData = data
		return nil
	}

	items := citationAlert(context.Background(), "u1", "A1")
	if items != nil {
		t.Fatalf("first-ever check should emit nothing (would misreport pre-existing citations), got %+v", items)
	}
	if setData["lastSeenCitationCount"] != 42 {
		t.Fatalf("watermark not seeded to current count: %+v", setData)
	}
}

func TestCitationAlert_IncreaseEmitsCoarseCountAndAdvancesWatermark(t *testing.T) {
	origFetch, origGet, origSet := fetchAuthorHook, firestoreGetDocHook, firestoreSetDocHook
	defer func() { fetchAuthorHook, firestoreGetDocHook, firestoreSetDocHook = origFetch, origGet, origSet }()

	fetchAuthorHook = func(context.Context, string) (*openalex.Author, error) {
		return &openalex.Author{CitedByCount: 50}, nil
	}
	firestoreGetDocHook = func(context.Context, string, string) (map[string]any, bool, error) {
		return map[string]any{"lastSeenCitationCount": int64(47)}, true, nil
	}
	var setData map[string]any
	firestoreSetDocHook = func(_ context.Context, _, _ string, data map[string]any) error {
		setData = data
		return nil
	}

	items := citationAlert(context.Background(), "u1", "A1")
	if len(items) != 1 {
		t.Fatalf("want exactly one citation_received item, got %d", len(items))
	}
	it := items[0]
	if it.Type != "citation_received" || it.Count != 3 {
		t.Fatalf("item = %+v, want type citation_received, count 3", it)
	}
	// Never claims to know which paper — no Object naming a specific work.
	if it.Object != nil {
		t.Fatalf("citation_received must stay coarse (no specific paper), got Object=%+v", it.Object)
	}
	if setData["lastSeenCitationCount"] != 50 {
		t.Fatalf("watermark not advanced to current count: %+v", setData)
	}
}

func TestCitationAlert_NoIncreaseEmitsNothing(t *testing.T) {
	origFetch, origGet, origSet := fetchAuthorHook, firestoreGetDocHook, firestoreSetDocHook
	defer func() { fetchAuthorHook, firestoreGetDocHook, firestoreSetDocHook = origFetch, origGet, origSet }()

	fetchAuthorHook = func(context.Context, string) (*openalex.Author, error) {
		return &openalex.Author{CitedByCount: 50}, nil
	}
	firestoreGetDocHook = func(context.Context, string, string) (map[string]any, bool, error) {
		return map[string]any{"lastSeenCitationCount": int64(50)}, true, nil
	}
	setCalled := false
	firestoreSetDocHook = func(context.Context, string, string, map[string]any) error {
		setCalled = true
		return nil
	}

	if items := citationAlert(context.Background(), "u1", "A1"); items != nil {
		t.Fatalf("no increase should emit nothing, got %+v", items)
	}
	if setCalled {
		t.Fatal("watermark should not be rewritten when the count hasn't moved")
	}
}

func TestCitationAlert_FetchErrorIsCleanMiss(t *testing.T) {
	origFetch := fetchAuthorHook
	defer func() { fetchAuthorHook = origFetch }()

	fetchAuthorHook = func(context.Context, string) (*openalex.Author, error) {
		return nil, fmt.Errorf("openalex down")
	}
	if items := citationAlert(context.Background(), "u1", "A1"); items != nil {
		t.Fatalf("fetch error should emit nothing, got %+v", items)
	}
}
