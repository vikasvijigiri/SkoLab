package activity

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/jackc/pgx/v5/pgconn"
	sdkmetric "go.opentelemetry.io/otel/sdk/metric"
	"go.opentelemetry.io/otel/sdk/metric/metricdata"
)

type fakeStore struct {
	page     Page
	err      error
	after    *Cursor
	limit    int
	userID   string
	seenAt   time.Time
	seenErr  error
	feedHits int
}

func (f *fakeStore) Feed(_ context.Context, userID string, after *Cursor, limit int) (Page, error) {
	f.feedHits++
	f.userID, f.after, f.limit = userID, after, limit
	return f.page, f.err
}

func (f *fakeStore) MarkSeen(_ context.Context, userID string, at time.Time) (*time.Time, error) {
	f.userID, f.seenAt = userID, at
	if f.seenErr != nil {
		return nil, f.seenErr
	}
	return &at, nil
}

func serve(t *testing.T, store Store, uid, method, target string) (*httptest.ResponseRecorder, map[string]any) {
	t.Helper()
	gin.SetMode(gin.TestMode)
	r := gin.New()
	g := r.Group("/api/v1")
	g.Use(func(c *gin.Context) {
		if uid != "" {
			c.Set("user_id", uid)
		}
	})
	Register(g, store)
	w := httptest.NewRecorder()
	r.ServeHTTP(w, httptest.NewRequest(method, target, nil))
	var body map[string]any
	_ = json.Unmarshal(w.Body.Bytes(), &body)
	return w, body
}

func TestFeedNeedsACaller(t *testing.T) {
	for _, route := range [][2]string{{"GET", "/api/v1/activity"}, {"POST", "/api/v1/activity/seen"}} {
		w, body := serve(t, &fakeStore{}, "", route[0], route[1])
		if w.Code != http.StatusUnauthorized || body["code"] != "unauthenticated" || w.Header().Get("Cache-Control") != "no-store" {
			t.Fatalf("%v: %d %v", route, w.Code, body)
		}
	}
}

func TestFeedDefaultsAndShape(t *testing.T) {
	at := time.Date(2026, 10, 16, 9, 0, 0, 0, time.UTC)
	role := "editor"
	store := &fakeStore{page: Page{
		Items: []Item{{ID: "7", Kind: RoleChanged, Workspace: WorkspaceRef{ID: "w", Title: "Paper"},
			Actor: &Person{ID: "a", DisplayName: "Ada"}, Subject: &Person{ID: "b", DisplayName: "Bo"}, Role: &role, Count: 1, OccurredAt: at, Unread: true}},
		Unread: 1,
		SeenAt: &at,
		Next:   &Cursor{At: at, ID: 7},
	}}
	w, body := serve(t, store, "alice", "GET", "/api/v1/activity")
	if w.Code != http.StatusOK || store.limit != defaultPageSize || store.after != nil || store.userID != "alice" {
		t.Fatalf("%d %+v", w.Code, store)
	}
	items := body["items"].([]any)
	first := items[0].(map[string]any)
	if body["unread"] != float64(1) || first["kind"] != RoleChanged || first["workspace"].(map[string]any)["title"] != "Paper" ||
		first["actor"].(map[string]any)["display_name"] != "Ada" || first["role"] != "editor" || first["unread"] != true {
		t.Fatalf("%v", body)
	}
	cursor, err := decodeCursor(body["next_page_token"].(string))
	if err != nil || cursor.ID != 7 || !cursor.At.Equal(at) {
		t.Fatalf("next page token %v %v", cursor, err)
	}

	// An empty page is [] and no next token, never null.
	w, body = serve(t, &fakeStore{}, "alice", "GET", "/api/v1/activity")
	if w.Code != http.StatusOK || body["next_page_token"] != "" || len(body["items"].([]any)) != 0 || body["seen_at"] != nil {
		t.Fatalf("%d %v", w.Code, body)
	}
}

func TestFeedPassesPageSizeAndToken(t *testing.T) {
	at := time.Date(2026, 1, 2, 3, 4, 5, 6000, time.UTC)
	store := &fakeStore{}
	w, _ := serve(t, store, "alice", "GET", "/api/v1/activity?page_size=50&page_token="+encodeCursor(Cursor{At: at, ID: 42}))
	if w.Code != http.StatusOK || store.limit != 50 || store.after == nil || store.after.ID != 42 || !store.after.At.Equal(at) {
		t.Fatalf("%d %+v", w.Code, store.after)
	}
}

func TestFeedRefusesBadParameters(t *testing.T) {
	cases := map[string]string{
		"/api/v1/activity?page_size=0":                                               "invalid_page_size",
		"/api/v1/activity?page_size=51":                                              "invalid_page_size",
		"/api/v1/activity?page_size=ten":                                             "invalid_page_size",
		"/api/v1/activity?page_token=":                                               "invalid_page_token",
		"/api/v1/activity?page_token=" + strings.Repeat("a", 201):                    "invalid_page_token",
		"/api/v1/activity?page_token=" + encodeCursor(Cursor{ID: 1}):                 "invalid_page_token",
		"/api/v1/activity?page_token=" + encodeCursor(Cursor{At: time.Now(), ID: 0}): "invalid_page_token",
		"/api/v1/activity?pagesize=5":                                                "unknown_parameter",
		"/api/v1/activity?page_size=5&page_size=6":                                   "duplicate_parameter",
	}
	for target, code := range cases {
		store := &fakeStore{}
		w, body := serve(t, store, "alice", "GET", target)
		if w.Code != http.StatusBadRequest || body["code"] != code || store.feedHits != 0 {
			t.Errorf("%s: %d %v", target, w.Code, body)
		}
	}
}

func TestStoreFailuresAre503(t *testing.T) {
	w, body := serve(t, &fakeStore{err: ErrUnavailable}, "alice", "GET", "/api/v1/activity")
	if w.Code != http.StatusServiceUnavailable || body["code"] != "unavailable" {
		t.Fatalf("%d %v", w.Code, body)
	}
	w, body = serve(t, &fakeStore{seenErr: ErrUnavailable}, "alice", "POST", "/api/v1/activity/seen")
	if w.Code != http.StatusServiceUnavailable || body["code"] != "unavailable" {
		t.Fatalf("%d %v", w.Code, body)
	}
}

func TestSeenMarksUpToNow(t *testing.T) {
	store := &fakeStore{}
	before := time.Now()
	w, body := serve(t, store, "alice", "POST", "/api/v1/activity/seen")
	if w.Code != http.StatusOK || body["unread"] != float64(0) || body["seen_at"] == nil || store.userID != "alice" || store.seenAt.Before(before) {
		t.Fatalf("%d %v %+v", w.Code, body, store)
	}
}

type call struct {
	sql  string
	args []any
}

type fakeTx struct {
	calls []call
	fail  int // fail the nth call (1-based); 0 never
}

func (f *fakeTx) Exec(_ context.Context, sql string, args ...any) (pgconn.CommandTag, error) {
	f.calls = append(f.calls, call{sql, args})
	if len(f.calls) == f.fail {
		return pgconn.CommandTag{}, errors.New("boom")
	}
	return pgconn.CommandTag{}, nil
}

func TestRecordWritesMergesAndPrunes(t *testing.T) {
	at := time.Date(2026, 10, 16, 9, 30, 0, 123456789, time.UTC)
	tx := &fakeTx{}
	if err := Record(context.Background(), tx, Event{Kind: DocumentEdited, WorkspaceID: "w", ActorID: "a", At: at}); err != nil {
		t.Fatal(err)
	}
	if len(tx.calls) != 2 || tx.calls[0].sql != recordEdit || tx.calls[1].sql != prune {
		t.Fatalf("%+v", tx.calls)
	}
	edit := tx.calls[0].args
	if edit[0] != "w" || edit[1] != "a" || !edit[2].(time.Time).Equal(at.Truncate(time.Microsecond)) || edit[3] != MergeWindow.Seconds() {
		t.Fatalf("edit args %v", edit)
	}
	if cutoff := tx.calls[1].args[1].(time.Time); !cutoff.Equal(at.Truncate(time.Microsecond).Add(-Retention)) {
		t.Fatalf("prune cutoff %v", cutoff)
	}

	for _, kind := range []string{MemberJoined, MemberLeft, MemberRemoved, RoleChanged, OwnershipTransferred} {
		tx := &fakeTx{}
		if err := Record(context.Background(), tx, Event{Kind: kind, WorkspaceID: "w", ActorID: "a", SubjectID: "b", Role: "editor"}); err != nil {
			t.Fatal(err)
		}
		args := tx.calls[0].args
		if tx.calls[0].sql != recordOther || args[1] != kind || args[3] != "b" || args[4] != "editor" || args[5].(time.Time).IsZero() {
			t.Fatalf("%s: %v", kind, args)
		}
	}
}

func TestRecordRefusesUnknownKindsAndReportsFailures(t *testing.T) {
	if err := Record(context.Background(), &fakeTx{}, Event{Kind: "made_coffee", WorkspaceID: "w"}); err == nil {
		t.Fatal("unknown kind must fail")
	}
	for n := 1; n <= 2; n++ {
		if err := Record(context.Background(), &fakeTx{fail: n}, Event{Kind: MemberJoined, WorkspaceID: "w", ActorID: "a"}); err == nil {
			t.Fatalf("a failing statement %d must fail the record", n)
		}
	}
}

func TestRecordCountsEventsByKind(t *testing.T) {
	reader := sdkmetric.NewManualReader()
	provider := sdkmetric.NewMeterProvider(sdkmetric.WithReader(reader))
	if err := UseMeter(provider.Meter("test")); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { counter.Store(nil) })
	ctx := context.Background()
	for _, kind := range []string{DocumentEdited, DocumentEdited, MemberJoined} {
		if err := Record(ctx, &fakeTx{}, Event{Kind: kind, WorkspaceID: "w", ActorID: "a"}); err != nil {
			t.Fatal(err)
		}
	}
	// A failed write is not counted.
	_ = Record(ctx, &fakeTx{fail: 1}, Event{Kind: MemberLeft, WorkspaceID: "w", ActorID: "a"})
	var data metricdata.ResourceMetrics
	if err := reader.Collect(ctx, &data); err != nil {
		t.Fatal(err)
	}
	got := map[string]int64{}
	for _, scope := range data.ScopeMetrics {
		for _, m := range scope.Metrics {
			if m.Name != "skolab.activity.events" {
				continue
			}
			for _, point := range m.Data.(metricdata.Sum[int64]).DataPoints {
				kind, _ := point.Attributes.Value("kind")
				got[kind.AsString()] = point.Value
			}
		}
	}
	if got[DocumentEdited] != 2 || got[MemberJoined] != 1 || got[MemberLeft] != 0 {
		t.Fatalf("%v", got)
	}
}

func TestCursorRoundTrip(t *testing.T) {
	want := Cursor{At: time.Date(2026, 10, 16, 1, 2, 3, 4000, time.UTC), ID: 99}
	got, err := decodeCursor(encodeCursor(want))
	if err != nil || got.ID != want.ID || !got.At.Equal(want.At) {
		t.Fatalf("%+v %v", got, err)
	}
	if _, err := decodeCursor("bm90IGpzb24"); err == nil {
		t.Fatal("non-JSON cursor must fail")
	}
}

func TestPersonIsNilForADeletedAccount(t *testing.T) {
	if person(nil, nil) != nil {
		t.Fatal("deleted actor must be nil")
	}
	id, name := "u", "Ada"
	if p := person(&id, &name); p.ID != "u" || p.DisplayName != "Ada" {
		t.Fatalf("%+v", p)
	}
	if p := person(&id, nil); p.DisplayName != "" {
		t.Fatalf("%+v", p)
	}
}

func TestNilPoolIsUnavailable(t *testing.T) {
	store := NewPostgresStore(nil)
	if _, err := store.Feed(context.Background(), "u", nil, 10); !errors.Is(err, ErrUnavailable) {
		t.Fatal(err)
	}
	if _, err := store.MarkSeen(context.Background(), "u", time.Now()); !errors.Is(err, ErrUnavailable) {
		t.Fatal(err)
	}
}
