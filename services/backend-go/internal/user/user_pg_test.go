package user

import (
	"context"
	"net/http"
	"net/http/httptest"
	"os"
	"strings"
	"testing"

	"github.com/gin-gonic/gin"
	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/skolab/backend-go/internal/db"
)

// Runs against the real schema in CI (TEST_DATABASE_URL); skips elsewhere.
func withDatabase(t *testing.T) *pgxpool.Pool {
	t.Helper()
	url := os.Getenv("TEST_DATABASE_URL")
	if url == "" {
		if os.Getenv("CI") != "" {
			t.Fatal("TEST_DATABASE_URL must be set in CI")
		}
		t.Skip("TEST_DATABASE_URL not set")
	}
	pool, err := pgxpool.New(context.Background(), url)
	if err != nil {
		t.Fatal(err)
	}
	previous := db.Pool
	db.Pool = pool
	t.Cleanup(func() {
		db.Pool = previous
		pool.Close()
	})
	return pool
}

// signedIn routes as an already-verified caller.
func signedIn(uid string) *gin.Engine {
	gin.SetMode(gin.TestMode)
	r := gin.New()
	users := r.Group("/api/v1/users", func(c *gin.Context) { c.Set("user_id", uid) })
	users.POST("/profile/sync", SyncUserProfile)
	users.DELETE("/:userId", DeleteUser)
	return r
}

func call(r *gin.Engine, method, path, body string) *httptest.ResponseRecorder {
	req := httptest.NewRequest(method, path, strings.NewReader(body))
	req.Header.Set("Content-Type", "application/json")
	w := httptest.NewRecorder()
	r.ServeHTTP(w, req)
	return w
}

func displayName(t *testing.T, pool *pgxpool.Pool, uid string) (string, bool) {
	t.Helper()
	var name string
	err := pool.QueryRow(context.Background(), "SELECT display_name FROM users WHERE id = $1", uid).Scan(&name)
	if err != nil {
		return "", false
	}
	return name, true
}

func TestSyncCreatesThenUpdatesTheCallersProfile(t *testing.T) {
	pool := withDatabase(t)
	uid := "user-pg-test-sync"
	t.Cleanup(func() { _, _ = pool.Exec(context.Background(), "DELETE FROM users WHERE id = $1", uid) })
	r := signedIn(uid)

	for _, name := range []string{"Ada", "Ada Lovelace"} {
		w := call(r, http.MethodPost, "/api/v1/users/profile/sync", `{"uid":"`+uid+`","name":"  `+name+`  "}`)
		if w.Code != http.StatusOK {
			t.Fatalf("sync %q: %d %s", name, w.Code, w.Body)
		}
		if got, ok := displayName(t, pool, uid); !ok || got != name {
			t.Fatalf("stored %q, want trimmed %q", got, name)
		}
	}
}

func TestDeleteRemovesTheCallersData(t *testing.T) {
	pool := withDatabase(t)
	uid := "user-pg-test-delete"
	if _, err := pool.Exec(context.Background(), "INSERT INTO users (id, display_name) VALUES ($1, 'Grace') ON CONFLICT (id) DO NOTHING", uid); err != nil {
		t.Fatal(err)
	}
	// No Firebase in tests: the identity step is a no-op, so deletion completes.
	if w := call(signedIn(uid), http.MethodDelete, "/api/v1/users/"+uid, ""); w.Code != http.StatusNoContent {
		t.Fatalf("delete: %d %s", w.Code, w.Body)
	}
	if _, ok := displayName(t, pool, uid); ok {
		t.Fatal("the user's row survived deletion")
	}
	// Deleting again is harmless (idempotent retry after a partial failure).
	if w := call(signedIn(uid), http.MethodDelete, "/api/v1/users/"+uid, ""); w.Code != http.StatusNoContent {
		t.Fatalf("repeat delete: %d", w.Code)
	}
}

func TestDatabaseErrorsAreInternalErrors(t *testing.T) {
	pool := withDatabase(t)
	pool.Close() // every statement now fails
	r := signedIn("u1")
	if w := call(r, http.MethodPost, "/api/v1/users/profile/sync", `{"uid":"u1","name":"Ada"}`); w.Code != http.StatusInternalServerError {
		t.Fatalf("sync: %d, want 500", w.Code)
	}
	if w := call(r, http.MethodDelete, "/api/v1/users/u1", ""); w.Code != http.StatusInternalServerError {
		t.Fatalf("delete: %d, want 500", w.Code)
	}
}

// Deleting an account must not silently delete workspaces other people
// actively collaborate in: 409 until they are transferred or deleted.
func TestDeleteIsRefusedWhileOwningSharedWorkspaces(t *testing.T) {
	pool := withDatabase(t)
	ctx := context.Background()
	owner, collaborator := "user-pg-test-owner", "user-pg-test-collaborator"
	t.Cleanup(func() { _, _ = pool.Exec(ctx, "DELETE FROM users WHERE id = ANY($1)", []string{owner, collaborator}) })
	for _, stmt := range []string{
		`INSERT INTO users (id, display_name) VALUES ('` + owner + `', 'Owner'), ('` + collaborator + `', 'Collaborator') ON CONFLICT (id) DO NOTHING`,
		`INSERT INTO workspaces (id, owner_id, title, created_at) VALUES
			('pg-test-shared', '` + owner + `', 'Shared', NOW()), ('pg-test-solo', '` + owner + `', 'Solo', NOW())`,
		`INSERT INTO workspace_members (workspace_id, user_id, role, status, created_at) VALUES
			('pg-test-shared', '` + owner + `', 'owner', 'active', NOW()),
			('pg-test-solo', '` + owner + `', 'owner', 'active', NOW()),
			('pg-test-shared', '` + collaborator + `', 'editor', 'active', NOW())`,
	} {
		if _, err := pool.Exec(ctx, stmt); err != nil {
			t.Fatal(err)
		}
	}

	w := call(signedIn(owner), http.MethodDelete, "/api/v1/users/"+owner, "")
	if w.Code != http.StatusConflict || !strings.Contains(w.Body.String(), `"code":"owns_shared_workspaces"`) ||
		!strings.Contains(w.Body.String(), `"id":"pg-test-shared"`) || strings.Contains(w.Body.String(), "pg-test-solo") {
		t.Fatalf("delete while sharing: %d %s", w.Code, w.Body)
	}
	if _, ok := displayName(t, pool, owner); !ok {
		t.Fatal("refused deletion still deleted the account")
	}

	// Once the shared workspace has a new owner, deletion goes through and
	// the collaborator keeps their workspace; the solo one goes with the account.
	if _, err := pool.Exec(ctx, `UPDATE workspaces SET owner_id = $1 WHERE id = 'pg-test-shared'`, collaborator); err != nil {
		t.Fatal(err)
	}
	if w := call(signedIn(owner), http.MethodDelete, "/api/v1/users/"+owner, ""); w.Code != http.StatusNoContent {
		t.Fatalf("delete after transfer: %d %s", w.Code, w.Body)
	}
	var shared, solo int
	_ = pool.QueryRow(ctx, `SELECT count(*) FILTER (WHERE id = 'pg-test-shared'), count(*) FILTER (WHERE id = 'pg-test-solo') FROM workspaces`).Scan(&shared, &solo)
	if shared != 1 || solo != 0 {
		t.Fatalf("after deletion: shared=%d solo=%d, want 1 and 0", shared, solo)
	}
	_, _ = pool.Exec(ctx, "DELETE FROM workspaces WHERE id = 'pg-test-shared'")
}
