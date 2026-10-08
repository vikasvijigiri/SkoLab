package workspace

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/gin-gonic/gin"
)

// fakeStore records calls and returns canned results.
type fakeStore struct {
	created   []string // "owner|title|requestID|max"
	replay    bool
	err       error
	items     []Workspace
	listAfter *Cursor
	listLimit int
}

func (f *fakeStore) Create(_ context.Context, owner, title, requestID string, max int) (Workspace, bool, error) {
	f.created = append(f.created, fmt.Sprintf("%s|%s|%s|%d", owner, title, requestID, max))
	if f.err != nil {
		return Workspace{}, false, f.err
	}
	return Workspace{ID: "ws-1", Title: title, OwnerID: owner, Role: "owner"}, f.replay, nil
}

func (f *fakeStore) List(_ context.Context, _ string, after *Cursor, limit int) ([]Workspace, error) {
	f.listAfter, f.listLimit = after, limit
	if len(f.items) > limit {
		return f.items[:limit], f.err
	}
	return f.items, f.err
}

func (f *fakeStore) Get(_ context.Context, id, user string) (Workspace, error) {
	return Workspace{ID: id, OwnerID: user, Role: "owner"}, f.err
}

func (f *fakeStore) Rename(_ context.Context, id, user, title string) (Workspace, error) {
	return Workspace{ID: id, Title: title, OwnerID: user, Role: "owner"}, f.err
}

func (f *fakeStore) Delete(context.Context, string, string) error { return f.err }

func serve(t *testing.T, store Store, user, method, path, body string, headers map[string]string) *httptest.ResponseRecorder {
	t.Helper()
	gin.SetMode(gin.TestMode)
	r := gin.New()
	group := r.Group("/api/v1", func(c *gin.Context) {
		if user != "" {
			c.Set("user_id", user)
		}
	})
	Register(group, store)
	req := httptest.NewRequest(method, path, strings.NewReader(body))
	for k, v := range headers {
		req.Header.Set(k, v)
	}
	w := httptest.NewRecorder()
	r.ServeHTTP(w, req)
	return w
}

func errorCode(t *testing.T, w *httptest.ResponseRecorder) string {
	t.Helper()
	var body struct{ Code string }
	_ = json.Unmarshal(w.Body.Bytes(), &body)
	return body.Code
}

func TestCreate_OwnerComesFromTokenNotBody(t *testing.T) {
	store := &fakeStore{}
	w := serve(t, store, "ada", "POST", "/api/v1/workspaces",
		`{"title":"  Thesis  ","owner_id":"mallory"}`, map[string]string{"Idempotency-Key": "req-1"})
	if w.Code != http.StatusCreated {
		t.Fatalf("status = %d: %s", w.Code, w.Body)
	}
	if want := "ada|Thesis|req-1|100"; store.created[0] != want {
		t.Fatalf("store got %q, want %q (owner from token, trimmed title)", store.created[0], want)
	}
	if got := w.Header().Get("Location"); got != "/api/v1/workspaces/ws-1" {
		t.Fatalf("Location = %q", got)
	}
	if w.Header().Get("Cache-Control") != "no-store" || w.Header().Get("Idempotent-Replayed") != "" {
		t.Fatalf("headers = %v", w.Header())
	}
}

func TestCreate_ReplayIsMarked(t *testing.T) {
	w := serve(t, &fakeStore{replay: true}, "ada", "POST", "/api/v1/workspaces", `{"title":"T"}`,
		map[string]string{"Idempotency-Key": "req-1"})
	if w.Code != http.StatusCreated || w.Header().Get("Idempotent-Replayed") != "true" {
		t.Fatalf("status = %d, headers = %v", w.Code, w.Header())
	}
}

func TestCreate_RejectsInvalidInputBeforeTouchingTheStore(t *testing.T) {
	cases := map[string]struct {
		body, key, code string
	}{
		"not json":         {`title`, "", "invalid_body"},
		"blank title":      {`{"title":"   "}`, "", "invalid_title"},
		"too long":         {`{"title":"` + strings.Repeat("é", 256) + `"}`, "", "invalid_title"},
		"padded too long":  {`{"title":"` + strings.Repeat(" ", 255) + `x"}`, "", "invalid_title"},
		"control chars":    {`{"title":"a\u0007b"}`, "", "invalid_title"},
		"trailing newline": {`{"title":"quarter\n"}`, "", "invalid_title"},
		"leading tab":      {`{"title":"\tquarter"}`, "", "invalid_title"},
		"long key":         {`{"title":"T"}`, strings.Repeat("k", 101), "invalid_idempotency_key"},
		"key with a space": {`{"title":"T"}`, "a b", "invalid_idempotency_key"},
	}
	for name, tc := range cases {
		t.Run(name, func(t *testing.T) {
			store := &fakeStore{}
			headers := map[string]string{}
			if tc.key != "" {
				headers["Idempotency-Key"] = tc.key
			}
			w := serve(t, store, "ada", "POST", "/api/v1/workspaces", tc.body, headers)
			if w.Code != http.StatusBadRequest || errorCode(t, w) != tc.code {
				t.Fatalf("status = %d code = %q, want 400 %q", w.Code, errorCode(t, w), tc.code)
			}
			if len(store.created) != 0 {
				t.Fatal("invalid input reached the store")
			}
		})
	}
	// Exactly 255 characters (multi-byte) is allowed.
	w := serve(t, &fakeStore{}, "ada", "POST", "/api/v1/workspaces", `{"title":"`+strings.Repeat("é", 255)+`"}`, nil)
	if w.Code != http.StatusCreated {
		t.Fatalf("255-rune title: status = %d", w.Code)
	}
}

func TestEveryRouteRequiresAuthentication(t *testing.T) {
	for _, route := range [][2]string{{"POST", "/api/v1/workspaces"}, {"GET", "/api/v1/workspaces"},
		{"GET", "/api/v1/workspaces/w"}, {"PATCH", "/api/v1/workspaces/w"}, {"DELETE", "/api/v1/workspaces/w"}} {
		w := serve(t, &fakeStore{}, "", route[0], route[1], `{"title":"T"}`, nil)
		if w.Code != http.StatusUnauthorized {
			t.Fatalf("%s %s: status = %d, want 401", route[0], route[1], w.Code)
		}
	}
}

func TestStoreErrorsMapToStableContract(t *testing.T) {
	cases := []struct {
		err    error
		status int
		code   string
	}{
		{ErrNotFound, 404, "not_found"},
		{ErrForbidden, 403, "owner_required"},
		{ErrLimitReached, 403, "workspace_limit_reached"},
		{ErrProfileRequired, 409, "profile_required"},
		{ErrIdempotencyMismatch, 422, "idempotency_key_reused"},
		{errors.Join(ErrUnavailable, errors.New("pg down")), 503, "unavailable"},
	}
	for _, tc := range cases {
		w := serve(t, &fakeStore{err: tc.err}, "ada", "PATCH", "/api/v1/workspaces/w", `{"title":"T"}`, nil)
		if w.Code != tc.status || errorCode(t, w) != tc.code {
			t.Fatalf("%v: status = %d code = %q, want %d %q", tc.err, w.Code, errorCode(t, w), tc.status, tc.code)
		}
		if strings.Contains(w.Body.String(), "pg down") {
			t.Fatal("internal error detail leaked to the client")
		}
	}
}

func TestDeleteReturnsNoContent(t *testing.T) {
	if w := serve(t, &fakeStore{}, "ada", "DELETE", "/api/v1/workspaces/w", "", nil); w.Code != http.StatusNoContent {
		t.Fatalf("status = %d", w.Code)
	}
}

func TestListPaginatesWithOpaqueTokens(t *testing.T) {
	base := time.Date(2026, 10, 1, 0, 0, 0, 0, time.UTC)
	store := &fakeStore{}
	for i := 0; i < 3; i++ {
		store.items = append(store.items, Workspace{ID: fmt.Sprintf("w%d", i), CreatedAt: base.Add(-time.Duration(i) * time.Hour)})
	}
	w := serve(t, store, "ada", "GET", "/api/v1/workspaces?page_size=2", "", nil)
	var page struct {
		Workspaces    []Workspace `json:"workspaces"`
		NextPageToken string      `json:"next_page_token"`
	}
	if err := json.Unmarshal(w.Body.Bytes(), &page); err != nil || w.Code != 200 {
		t.Fatalf("status = %d: %s", w.Code, w.Body)
	}
	if store.listLimit != 3 || len(page.Workspaces) != 2 || page.NextPageToken == "" {
		t.Fatalf("limit = %d, items = %d, token = %q", store.listLimit, len(page.Workspaces), page.NextPageToken)
	}
	serve(t, store, "ada", "GET", "/api/v1/workspaces?page_token="+page.NextPageToken, "", nil)
	if store.listAfter == nil || store.listAfter.ID != "w1" || !store.listAfter.CreatedAt.Equal(base.Add(-time.Hour)) {
		t.Fatalf("cursor = %+v, want after w1", store.listAfter)
	}

	empty := serve(t, &fakeStore{}, "ada", "GET", "/api/v1/workspaces", "", nil)
	if !strings.Contains(empty.Body.String(), `"workspaces":[]`) || !strings.Contains(empty.Body.String(), `"next_page_token":""`) {
		t.Fatalf("empty list body = %s", empty.Body)
	}
	nulCursor := encodeCursor(Cursor{CreatedAt: time.Date(2026, 10, 1, 0, 0, 0, 0, time.UTC), ID: "w\x00"})
	for _, query := range []string{"page_size=0", "page_size=101", "page_size=x", "page_token=not*base64", "page_token=e30", "page_token=" + nulCursor} {
		if w := serve(t, &fakeStore{}, "ada", "GET", "/api/v1/workspaces?"+query, "", nil); w.Code != http.StatusBadRequest {
			t.Fatalf("%s: status = %d, want 400", query, w.Code)
		}
	}
}

func TestMaxPerUserIsConfigurable(t *testing.T) {
	t.Setenv("WORKSPACE_MAX_PER_USER", "3")
	if maxPerUser() != 3 {
		t.Fatal("WORKSPACE_MAX_PER_USER ignored")
	}
	t.Setenv("WORKSPACE_MAX_PER_USER", "-1")
	if maxPerUser() != defaultMaxPerUser {
		t.Fatal("invalid cap must fall back to the default")
	}
}

func TestListRefusesUnknownQueryParameters(t *testing.T) {
	for query, want := range map[string]int{
		"":                        http.StatusOK,
		"?page_size=5":            http.StatusOK,
		"?pagesize=5":             http.StatusBadRequest, // a typo is caught, not ignored
		"?page_size=5&debug=true": http.StatusBadRequest,
	} {
		w := serve(t, &fakeStore{}, "ada", "GET", "/api/v1/workspaces"+query, "", nil)
		if w.Code != want {
			t.Fatalf("%q: status %d, want %d", query, w.Code, want)
		}
		if want == http.StatusBadRequest && errorCode(t, w) != "unknown_parameter" {
			t.Fatalf("%q: code %q", query, errorCode(t, w))
		}
	}
}

func TestListRefusesAmbiguousPageSize(t *testing.T) {
	for query, code := range map[string]string{
		"?page_size=&page_size=junk": "duplicate_parameter",
		"?page_size=5&page_size=6":   "duplicate_parameter",
		"?page_token=a&page_token=b": "duplicate_parameter",
		"?page_size=":                "invalid_page_size",
	} {
		w := serve(t, &fakeStore{}, "ada", "GET", "/api/v1/workspaces"+query, "", nil)
		if w.Code != http.StatusBadRequest || errorCode(t, w) != code {
			t.Fatalf("%q: got %d %q, want 400 %s", query, w.Code, errorCode(t, w), code)
		}
	}
}
