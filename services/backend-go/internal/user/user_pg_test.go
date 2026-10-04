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
