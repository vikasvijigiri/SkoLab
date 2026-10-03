package user

import (
	"net/http"
	"net/http/httptest"
	"strconv"
	"strings"
	"testing"

	"github.com/gin-gonic/gin"

	"github.com/skolab/backend-go/internal/auth"
)

// db.Pool is nil in a unit test (no Postgres service container here), so the
// handler tests below cover input-validation and the no-DB guard paths.

func router() *gin.Engine {
	gin.SetMode(gin.TestMode)
	r := gin.New()

	usersAPI := r.Group("/api/v1/users")
	usersAPI.Use(auth.VerifyUser())
	{
		usersAPI.POST("/profile/sync", SyncUserProfile)
		usersAPI.DELETE("/:userId", DeleteUser)
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

func TestSyncUserProfile_InvalidNameIs400(t *testing.T) {
	for _, name := range []string{"   ", strings.Repeat("a", 256), "Ada\nLovelace"} {
		body := `{"uid":"dev_user","name":` + strconv.Quote(name) + `}`
		w := do(router(), http.MethodPost, "/api/v1/users/profile/sync", body, devAuth)
		if w.Code != http.StatusBadRequest || !strings.Contains(w.Body.String(), `"invalid_name"`) {
			t.Fatalf("name %q: status = %d %s, want 400 invalid_name", name, w.Code, w.Body)
		}
	}
}

func TestSyncUserProfile_NoDBIs503(t *testing.T) {
	// uid must be "dev_user" (what auth.VerifyUser() sets in dev/CI mode) or
	// the ownership check below would 403 first.
	w := do(router(), http.MethodPost, "/api/v1/users/profile/sync",
		`{"uid":"dev_user","name":"Ada Lovelace"}`, devAuth)
	if w.Code != http.StatusServiceUnavailable {
		t.Fatalf("status = %d, want 503 (db.Pool nil in unit test)", w.Code)
	}
}

func TestSyncUserProfile_MismatchedUIDIs403(t *testing.T) {
	// req.UID is client-controlled JSON, not derived from the verified
	// token -- without this check any authenticated caller could overwrite
	// another user's display_name (2026-09-12 endpoint audit).
	w := do(router(), http.MethodPost, "/api/v1/users/profile/sync",
		`{"uid":"someone_else","name":"Ada Lovelace"}`, devAuth)
	if w.Code != http.StatusForbidden {
		t.Fatalf("status = %d, want 403", w.Code)
	}
}

// ── DeleteUser ───────────────────────────────────────────────────────────────

func TestDeleteUser_MismatchedUserIs403(t *testing.T) {
	w := do(router(), http.MethodDelete, "/api/v1/users/someone_else", "", devAuth)
	if w.Code != http.StatusForbidden {
		t.Fatalf("status = %d, want 403", w.Code)
	}
}

func TestDeleteUser_NoDBIs503(t *testing.T) {
	w := do(router(), http.MethodDelete, "/api/v1/users/dev_user", "", devAuth)
	if w.Code != http.StatusServiceUnavailable {
		t.Fatalf("status = %d, want 503 (db.Pool nil in unit test)", w.Code)
	}
}
