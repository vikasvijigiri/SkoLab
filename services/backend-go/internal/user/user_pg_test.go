package user

import (
	"context"
	"errors"
	"net/http"
	"net/http/httptest"
	"os"
	"strings"
	"testing"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/skolab/backend-go/internal/db"
	"github.com/skolab/backend-go/internal/workspace"
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

// sharedWorkspaceFixture: oldOwner owns ws, with target and collab as active
// editors. Returns the workspace id.
func sharedWorkspaceFixture(t *testing.T, pool *pgxpool.Pool, prefix string) (ws, oldOwner, target, collab string) {
	t.Helper()
	ctx := context.Background()
	ws, oldOwner, target, collab = prefix+"-ws", prefix+"-old-owner", prefix+"-target", prefix+"-collab"
	t.Cleanup(func() {
		_, _ = pool.Exec(ctx, "DELETE FROM workspaces WHERE id = $1", ws)
		_, _ = pool.Exec(ctx, "DELETE FROM users WHERE id = ANY($1)", []string{oldOwner, target, collab})
	})
	for _, stmt := range []struct {
		sql  string
		args []any
	}{
		{"INSERT INTO users (id, display_name) VALUES ($1, 'O'), ($2, 'T'), ($3, 'C')", []any{oldOwner, target, collab}},
		{"INSERT INTO workspaces (id, owner_id, title, created_at) VALUES ($1, $2, 'Shared', NOW())", []any{ws, oldOwner}},
		{`INSERT INTO workspace_members (workspace_id, user_id, role, status, created_at) VALUES
			($1, $2, 'owner', 'active', NOW()), ($1, $3, 'editor', 'active', NOW()), ($1, $4, 'editor', 'active', NOW())`,
			[]any{ws, oldOwner, target, collab}},
	} {
		if _, err := pool.Exec(ctx, stmt.sql, stmt.args...); err != nil {
			t.Fatal(err)
		}
	}
	return ws, oldOwner, target, collab
}

// A transfer TO an account that commits while that account's deletion is
// in flight must not let the cascade take the collaborators' workspace: the
// deletion waits for the transfer, sees the new ownership, and answers 409.
// The transfer below takes TransferOwnership's locks in its order and is
// held open while the deletion starts.
func TestDeleteWaitsForAConcurrentTransferToTheAccount(t *testing.T) {
	pool := withDatabase(t)
	ctx := context.Background()
	ws, oldOwner, target, _ := sharedWorkspaceFixture(t, pool, "race-a")

	tx, err := pool.Begin(ctx)
	if err != nil {
		t.Fatal(err)
	}
	defer tx.Rollback(ctx) //nolint:errcheck // no-op after Commit
	for _, stmt := range []struct {
		sql  string
		args []any
	}{
		{"SELECT owner_id FROM workspaces WHERE id = $1 FOR UPDATE", []any{ws}},
		{"SELECT role FROM workspace_members WHERE workspace_id = $1 AND user_id = $2 AND status = 'active' FOR UPDATE", []any{ws, target}},
		{"UPDATE workspaces SET owner_id = $2 WHERE id = $1", []any{ws, target}},
		{"UPDATE workspace_members SET role = 'owner' WHERE workspace_id = $1 AND user_id = $2", []any{ws, target}},
		{"UPDATE workspace_members SET role = 'editor' WHERE workspace_id = $1 AND user_id = $2", []any{ws, oldOwner}},
	} {
		if _, err := tx.Exec(ctx, stmt.sql, stmt.args...); err != nil {
			t.Fatal(err)
		}
	}
	done := make(chan *httptest.ResponseRecorder, 1)
	go func() { done <- call(signedIn(target), http.MethodDelete, "/api/v1/users/"+target, "") }()
	time.Sleep(300 * time.Millisecond) // the deletion is now blocked behind the transfer
	if err := tx.Commit(ctx); err != nil {
		t.Fatal(err)
	}
	w := <-done
	if w.Code != http.StatusConflict || !strings.Contains(w.Body.String(), `"id":"`+ws+`"`) {
		t.Fatalf("deletion during transfer: %d %s, want 409 listing %s", w.Code, w.Body, ws)
	}
	var owner string
	if err := pool.QueryRow(ctx, "SELECT owner_id FROM workspaces WHERE id = $1", ws).Scan(&owner); err != nil || owner != target {
		t.Fatalf("shared workspace after the race: owner %q, err %v", owner, err)
	}
}

// The other order: once the deletion holds the account's membership rows, a
// transfer TO it waits, then finds no active editor and is refused; the
// workspace stays with its owner.
func TestTransferToAnAccountBeingDeletedIsRefused(t *testing.T) {
	pool := withDatabase(t)
	ctx := context.Background()
	ws, oldOwner, target, _ := sharedWorkspaceFixture(t, pool, "race-b")

	tx, err := pool.Begin(ctx)
	if err != nil {
		t.Fatal(err)
	}
	defer tx.Rollback(ctx) //nolint:errcheck // no-op after Commit
	// deleteUnlessSharing's first lock, then its delete, held open.
	if _, err := tx.Exec(ctx, "SELECT 1 FROM workspace_members WHERE user_id = $1 FOR UPDATE", target); err != nil {
		t.Fatal(err)
	}
	done := make(chan error, 1)
	go func() {
		done <- workspace.NewPostgresSharingStore(pool).TransferOwnership(ctx, ws, oldOwner, target, 100)
	}()
	time.Sleep(300 * time.Millisecond) // the transfer is now blocked behind the deletion
	if _, err := tx.Exec(ctx, "DELETE FROM users WHERE id = $1", target); err != nil {
		t.Fatal(err)
	}
	if err := tx.Commit(ctx); err != nil {
		t.Fatal(err)
	}
	if err := <-done; !errors.Is(err, workspace.ErrTransferTarget) {
		t.Fatalf("transfer to a deleted account: %v, want ErrTransferTarget", err)
	}
	var owner string
	if err := pool.QueryRow(ctx, "SELECT owner_id FROM workspaces WHERE id = $1", ws).Scan(&owner); err != nil || owner != oldOwner {
		t.Fatalf("workspace after refused transfer: owner %q, err %v", owner, err)
	}
}
