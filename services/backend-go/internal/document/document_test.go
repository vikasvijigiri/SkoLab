package document

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/gin-gonic/gin"
)

type fakeStore struct {
	err        error
	saved      []string // "workspace|user|base|template"
	lastSource string
}

func (f *fakeStore) Get(_ context.Context, ws, _ string) (Document, error) {
	if f.err != nil {
		return Document{}, f.err
	}
	return Document{WorkspaceID: ws, Source: "\\documentclass{article}", Version: 3, Role: "viewer"}, nil
}

func (f *fakeStore) Save(_ context.Context, ws, user, source string, templateID *string, base int) (Document, error) {
	t := "-"
	if templateID != nil {
		t = *templateID
	}
	f.saved = append(f.saved, ws+"|"+user+"|"+string(rune('0'+base))+"|"+t)
	f.lastSource = source
	if f.err != nil {
		return Document{}, f.err
	}
	return Document{WorkspaceID: ws, Source: source, TemplateID: templateID, Version: base + 1, Role: "editor"}, nil
}

func serve(t *testing.T, store Store, user, method, path, body string) *httptest.ResponseRecorder {
	t.Helper()
	gin.SetMode(gin.TestMode)
	r := gin.New()
	group := r.Group("/api/v1", func(c *gin.Context) {
		if user != "" {
			c.Set("user_id", user)
		}
	})
	Register(group, store, func(id string) bool { return id == "aps-physical-review" })
	w := httptest.NewRecorder()
	r.ServeHTTP(w, httptest.NewRequest(method, path, strings.NewReader(body)))
	return w
}

func body(t *testing.T, w *httptest.ResponseRecorder) map[string]any {
	t.Helper()
	var out map[string]any
	if err := json.Unmarshal(w.Body.Bytes(), &out); err != nil {
		t.Fatalf("not JSON: %s", w.Body)
	}
	return out
}

const path = "/api/v1/workspaces/ws-1/document"

func TestGetReturnsTheDocumentWithItsVersionAsETag(t *testing.T) {
	w := serve(t, &fakeStore{}, "alice", http.MethodGet, path, "")
	if w.Code != http.StatusOK || w.Header().Get("ETag") != `"v3"` || w.Header().Get("Cache-Control") != "no-store" {
		t.Fatalf("%d %v", w.Code, w.Header())
	}
	if got := body(t, w); got["version"] != float64(3) || got["role"] != "viewer" || got["workspace_id"] != "ws-1" {
		t.Fatalf("%v", got)
	}
}

func TestEveryRouteNeedsAVerifiedCaller(t *testing.T) {
	for _, method := range []string{http.MethodGet, http.MethodPut} {
		w := serve(t, &fakeStore{}, "", method, path, `{"source":"x","base_version":0}`)
		if w.Code != http.StatusUnauthorized || body(t, w)["code"] != "unauthenticated" {
			t.Fatalf("%s: %d %s", method, w.Code, w.Body)
		}
	}
}

func TestPutSavesAsTheCallerFromTheToken(t *testing.T) {
	store := &fakeStore{}
	w := serve(t, store, "alice", http.MethodPut, path, `{"source":"\\documentclass{revtex4-2}","base_version":2,"template_id":"aps-physical-review"}`)
	if w.Code != http.StatusOK || w.Header().Get("ETag") != `"v3"` {
		t.Fatalf("%d %s", w.Code, w.Body)
	}
	if len(store.saved) != 1 || store.saved[0] != "ws-1|alice|2|aps-physical-review" || store.lastSource != `\documentclass{revtex4-2}` {
		t.Fatalf("saved %v %q", store.saved, store.lastSource)
	}
}

// JSON Schema counts 344.0 as the integer 344, so the API must too.
func TestPutAcceptsAnIntegralVersionWrittenWithAFraction(t *testing.T) {
	store := &fakeStore{}
	w := serve(t, store, "alice", http.MethodPut, path, `{"source":"x","base_version":2.0}`)
	if w.Code != http.StatusOK || len(store.saved) != 1 || store.saved[0] != "ws-1|alice|2|-" {
		t.Fatalf("%d %s %v", w.Code, w.Body, store.saved)
	}
}

func TestPutRefusesBadBodiesBeforeTouchingTheStore(t *testing.T) {
	cases := []struct {
		name, body string
		status     int
		code       string
	}{
		{"not json", `source=x`, 400, "invalid_body"},
		{"missing base_version", `{"source":"x"}`, 400, "invalid_body"},
		{"missing source", `{"base_version":0}`, 400, "invalid_body"},
		{"unknown field", `{"source":"x","base_version":0,"owner":"mallory"}`, 400, "invalid_body"},
		{"trailing data", `{"source":"x","base_version":0}{}`, 400, "invalid_body"},
		{"negative version", `{"source":"x","base_version":-1}`, 400, "invalid_base_version"},
		{"version past INTEGER", `{"source":"x","base_version":2147483648}`, 400, "invalid_base_version"},
		{"fractional version", `{"source":"x","base_version":1.5}`, 400, "invalid_body"},
		{"version as a string", `{"source":"x","base_version":"1"}`, 400, "invalid_body"},
		{"null version", `{"source":"x","base_version":null}`, 400, "invalid_body"},
		{"huge version", `{"source":"x","base_version":1e400}`, 400, "invalid_body"},
		{"nul byte", `{"source":"a\u0000b","base_version":0}`, 400, "invalid_source"},
		{"bell character", `{"source":"a\u0007b","base_version":0}`, 400, "invalid_source"},
		{"unknown template", `{"source":"x","base_version":0,"template_id":"nope"}`, 400, "unknown_template"},
		{"empty template", `{"source":"x","base_version":0,"template_id":""}`, 400, "unknown_template"},
		{"too long", `{"source":"` + strings.Repeat("é", MaxSourceRunes+1) + `","base_version":0}`, 413, "document_too_large"},
	}
	for _, tc := range cases {
		store := &fakeStore{}
		w := serve(t, store, "alice", http.MethodPut, path, tc.body)
		if w.Code != tc.status || body(t, w)["code"] != tc.code || len(store.saved) != 0 {
			t.Errorf("%s: %d %s (saved %v)", tc.name, w.Code, w.Body, store.saved)
		}
	}
}

func TestPutAcceptsTheLargestDocumentAndOrdinaryWhitespace(t *testing.T) {
	source := strings.Repeat("é", MaxSourceRunes-3) + "\t\r\n"
	raw, _ := json.Marshal(map[string]any{"source": source, "base_version": 0})
	store := &fakeStore{}
	if w := serve(t, store, "alice", http.MethodPut, path, string(raw)); w.Code != http.StatusOK || store.lastSource != source {
		t.Fatalf("%d %s", w.Code, w.Body)
	}
}

func TestStoreErrorsMapToTheContract(t *testing.T) {
	cases := []struct {
		err    error
		status int
		code   string
	}{
		{ErrNotFound, 404, "not_found"},
		{ErrReadOnly, 403, "edit_forbidden"},
		{&ConflictError{Current: 7}, 409, "version_conflict"},
		{ErrUnavailable, 503, "unavailable"},
		{errors.New("boom"), 503, "unavailable"},
	}
	for _, tc := range cases {
		w := serve(t, &fakeStore{err: tc.err}, "alice", http.MethodPut, path, `{"source":"x","base_version":1}`)
		got := body(t, w)
		if w.Code != tc.status || got["code"] != tc.code {
			t.Errorf("%v: %d %v", tc.err, w.Code, got)
		}
		if tc.status == 409 && (got["current_version"] != float64(7) || w.Header().Get("ETag") != `"v7"`) {
			t.Errorf("conflict must carry the current version: %v %v", got, w.Header())
		}
	}
	if w := serve(t, &fakeStore{err: ErrNotFound}, "alice", http.MethodGet, path, ""); w.Code != 404 {
		t.Fatalf("get of an invisible workspace: %d", w.Code)
	}
}
