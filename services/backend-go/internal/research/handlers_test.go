package research

import (
	"context"
	"errors"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/gin-gonic/gin"
)

type fakeStore struct {
	doc   Document
	err   error
	saved Document
	base  int
}

func (s *fakeStore) Get(_ context.Context, ws, _ string) (Document, error) {
	d := s.doc
	d.WorkspaceID = ws
	return d, s.err
}
func (s *fakeStore) Save(_ context.Context, ws, _ string, d Document, base int) (Document, error) {
	s.saved = d
	s.base = base
	d.WorkspaceID = ws
	d.Version = base + 1
	return d, s.err
}

func call(t *testing.T, store Store, method, body, uid string) *httptest.ResponseRecorder {
	t.Helper()
	gin.SetMode(gin.TestMode)
	r := gin.New()
	r.Use(func(c *gin.Context) {
		if uid != "" {
			c.Set("user_id", uid)
		}
		c.Next()
	})
	g := r.Group("/api/v1")
	Register(g, store)
	req := httptest.NewRequest(method, "/api/v1/workspaces/w/research", strings.NewReader(body))
	req.Header.Set("Content-Type", "application/json")
	w := httptest.NewRecorder()
	r.ServeHTTP(w, req)
	return w
}

func TestGetResearchRequiresAuthAndReturnsDefaults(t *testing.T) {
	if got := call(t, &fakeStore{}, http.MethodGet, "", ""); got.Code != http.StatusUnauthorized {
		t.Fatalf("unauthenticated: %d %s", got.Code, got.Body)
	}
	w := call(t, &fakeStore{doc: Document{Items: []Item{}, Role: "viewer"}}, http.MethodGet, "", "alice")
	if w.Code != http.StatusOK || !strings.Contains(w.Body.String(), `"items":[]`) || w.Header().Get("Cache-Control") != "no-store" {
		t.Fatalf("unexpected response: %d %s", w.Code, w.Body)
	}
}

func TestPutResearchValidatesAndPreservesAuthorStatus(t *testing.T) {
	store := &fakeStore{}
	body := `{"base_version":0,"items":[{"id":"e1","claim":"A claim","citation_key":"smith2024","quote":"supporting passage","locator":"p. 4","study_type":"RCT","sample":"n=40","findings":"outcome","limitations":"small sample","relevance":"direct","status":"supported_by_author"}],"reproducibility":{"data_availability":"available","code_availability":"repository","software":"R 4.4","analysis_steps":"run analysis.R","preregistration":"OSF","notes":"reviewed by team"}}`
	w := call(t, store, http.MethodPut, body, "alice")
	if w.Code != http.StatusOK || store.base != 0 || len(store.saved.Items) != 1 || store.saved.Items[0].Status != "supported_by_author" {
		t.Fatalf("save failed: %d %s", w.Code, w.Body)
	}
	if got := call(t, store, http.MethodPut, strings.Replace(body, "supported_by_author", "machine_verified", 1), "alice"); got.Code != http.StatusBadRequest {
		t.Fatalf("invalid status accepted: %d %s", got.Code, got.Body)
	}
	if got := call(t, store, http.MethodPut, strings.Replace(body, `"base_version":0`, `"base_version":-1`, 1), "alice"); got.Code != http.StatusBadRequest {
		t.Fatalf("negative version accepted: %d", got.Code)
	}
	if got := call(t, store, http.MethodPut, strings.Replace(body, `"notes":"reviewed by team"`, `"unknown":true`, 1), "alice"); got.Code != http.StatusBadRequest {
		t.Fatalf("unknown field accepted: %d %s", got.Code, got.Body)
	}
}

func TestPutResearchRejectsDuplicateIDsOversizeAndTrailingJSON(t *testing.T) {
	base := `{"base_version":0,"items":[{"id":"x","claim":"c","citation_key":"k","status":"to_review"},{"id":"x","claim":"c","citation_key":"k","status":"to_review"}],"reproducibility":{}}`
	for name, body := range map[string]string{"duplicate": base, "trailing": `{"base_version":0,"items":[],"reproducibility":{}} {}`, "oversize": `{"base_version":0,"items":[],"reproducibility":{"notes":"` + strings.Repeat("x", maxFieldRunes+1) + `"}}`} {
		t.Run(name, func(t *testing.T) {
			if w := call(t, &fakeStore{}, http.MethodPut, body, "alice"); w.Code != http.StatusBadRequest {
				t.Fatalf("%s accepted: %d %s", name, w.Code, w.Body)
			}
		})
	}
}

func TestPutResearchReturns413ForBodiesOverOneMiB(t *testing.T) {
	w := call(t, &fakeStore{}, http.MethodPut, strings.Repeat("x", (1<<20)+1), "alice")
	if w.Code != http.StatusRequestEntityTooLarge || !strings.Contains(w.Body.String(), `"code":"body_too_large"`) {
		t.Fatalf("oversized request: %d %s", w.Code, w.Body)
	}
}

func TestStoreErrorsDoNotLeakInternals(t *testing.T) {
	for _, tc := range []struct {
		err    error
		status int
		code   string
	}{{ErrNotFound, 404, "not_found"}, {ErrReadOnly, 403, "edit_forbidden"}, {&ConflictError{Current: 4}, 409, "version_conflict"}, {errors.New("database password leaked"), 503, "research_unavailable"}} {
		w := call(t, &fakeStore{err: tc.err}, http.MethodGet, "", "alice")
		if w.Code != tc.status || !strings.Contains(w.Body.String(), `"code":"`+tc.code+`"`) || strings.Contains(w.Body.String(), "password") {
			t.Fatalf("%v: %d %s", tc.err, w.Code, w.Body)
		}
	}
}
