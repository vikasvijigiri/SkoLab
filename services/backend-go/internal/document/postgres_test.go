package document

import (
	"context"
	"errors"
	"os"
	"sync"
	"testing"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5/pgxpool"
)

// Integration tests against the real schema. CI provides TEST_DATABASE_URL
// (the same Postgres the Python suite bootstraps); elsewhere they skip.
func testPool(t *testing.T) *pgxpool.Pool {
	t.Helper()
	url := os.Getenv("TEST_DATABASE_URL")
	if url == "" {
		if os.Getenv("CI") == "true" {
			t.Fatal("TEST_DATABASE_URL must be set in CI")
		}
		t.Skip("TEST_DATABASE_URL not set")
	}
	pool, err := pgxpool.New(context.Background(), url)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(pool.Close)
	return pool
}

func newUser(t *testing.T, pool *pgxpool.Pool) string {
	t.Helper()
	id := "test-" + uuid.NewString()
	if _, err := pool.Exec(context.Background(), "INSERT INTO users (id, display_name) VALUES ($1, 'Test')", id); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _, _ = pool.Exec(context.Background(), "DELETE FROM users WHERE id = $1", id) })
	return id
}

// newWorkspace creates a workspace owned by owner with the given members
// (user -> "role/status").
func newWorkspace(t *testing.T, pool *pgxpool.Pool, owner string, members map[string][2]string) string {
	t.Helper()
	ctx, id := context.Background(), uuid.NewString()
	if _, err := pool.Exec(ctx, "INSERT INTO workspaces (id, owner_id, title, created_at) VALUES ($1, $2, 'Paper', now())", id, owner); err != nil {
		t.Fatal(err)
	}
	for user, rs := range members {
		if _, err := pool.Exec(ctx, "INSERT INTO workspace_members (workspace_id, user_id, role, status, created_at) VALUES ($1, $2, $3, $4, now())", id, user, rs[0], rs[1]); err != nil {
			t.Fatal(err)
		}
	}
	return id
}

func TestPostgres_FirstSaveThenUpdatesBumpTheVersion(t *testing.T) {
	pool := testPool(t)
	store, ctx := NewPostgresStore(pool), context.Background()
	owner := newUser(t, pool)
	ws := newWorkspace(t, pool, owner, nil)

	doc, err := store.Get(ctx, ws, owner)
	if err != nil || doc.Version != 0 || doc.Source != "" || doc.UpdatedAt != nil || doc.Role != "owner" {
		t.Fatalf("unsaved = %+v, %v", doc, err)
	}
	template := "aps-physical-review"
	doc, err = store.Save(ctx, ws, owner, "v1", &template, 0)
	if err != nil || doc.Version != 1 || doc.Source != "v1" || *doc.TemplateID != template || *doc.UpdatedBy != owner {
		t.Fatalf("first save = %+v, %v", doc, err)
	}
	doc, err = store.Save(ctx, ws, owner, "v2", nil, 1)
	if err != nil || doc.Version != 2 || doc.Source != "v2" || doc.TemplateID == nil || *doc.TemplateID != template {
		t.Fatalf("second save must keep the template = %+v, %v", doc, err)
	}
	got, err := store.Get(ctx, ws, owner)
	if err != nil || got.Version != 2 || got.Source != "v2" || got.UpdatedAt == nil {
		t.Fatalf("read back = %+v, %v", got, err)
	}
}

func TestPostgres_StaleSavesConflictWithTheCurrentVersion(t *testing.T) {
	pool := testPool(t)
	store, ctx := NewPostgresStore(pool), context.Background()
	owner := newUser(t, pool)
	ws := newWorkspace(t, pool, owner, nil)

	var conflict *ConflictError
	if _, err := store.Save(ctx, ws, owner, "x", nil, 4); !errors.As(err, &conflict) || conflict.Current != 0 {
		t.Fatalf("update of a never-saved document = %v", err)
	}
	if _, err := store.Save(ctx, ws, owner, "a", nil, 0); err != nil {
		t.Fatal(err)
	}
	if _, err := store.Save(ctx, ws, owner, "b", nil, 0); !errors.As(err, &conflict) || conflict.Current != 1 {
		t.Fatalf("second first-save = %v", err)
	}
	if doc, _ := store.Get(ctx, ws, owner); doc.Source != "a" {
		t.Fatalf("a refused save changed the document: %q", doc.Source)
	}
}

func TestPostgres_ConcurrentSavesOfOneVersionLetExactlyOneWin(t *testing.T) {
	pool := testPool(t)
	store, ctx := NewPostgresStore(pool), context.Background()
	owner := newUser(t, pool)
	ws := newWorkspace(t, pool, owner, nil)
	if _, err := store.Save(ctx, ws, owner, "base", nil, 0); err != nil {
		t.Fatal(err)
	}
	const writers = 8
	var wg sync.WaitGroup
	results := make(chan error, writers)
	for i := 0; i < writers; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			_, err := store.Save(ctx, ws, owner, "edit", nil, 1)
			results <- err
		}()
	}
	wg.Wait()
	close(results)
	wins := 0
	for err := range results {
		var conflict *ConflictError
		switch {
		case err == nil:
			wins++
		case !errors.As(err, &conflict):
			t.Fatalf("unexpected error: %v", err)
		}
	}
	if doc, _ := store.Get(ctx, ws, owner); wins != 1 || doc.Version != 2 {
		t.Fatalf("wins = %d, version = %d; want exactly one save", wins, doc.Version)
	}
}

func TestPostgres_AccessFollowsWorkspaceRoles(t *testing.T) {
	pool := testPool(t)
	store, ctx := NewPostgresStore(pool), context.Background()
	owner, editor, viewer, commenter, invited, removed, stranger :=
		newUser(t, pool), newUser(t, pool), newUser(t, pool), newUser(t, pool), newUser(t, pool), newUser(t, pool), newUser(t, pool)
	ws := newWorkspace(t, pool, owner, map[string][2]string{
		editor: {"editor", "active"}, viewer: {"viewer", "active"}, commenter: {"commenter", "active"},
		invited: {"editor", "invited"}, removed: {"editor", "removed"},
	})

	if _, err := store.Save(ctx, ws, editor, "by editor", nil, 0); err != nil {
		t.Fatalf("editor save: %v", err)
	}
	for _, user := range []string{viewer, commenter} {
		if doc, err := store.Get(ctx, ws, user); err != nil || doc.Source != "by editor" {
			t.Fatalf("member read = %+v, %v", doc, err)
		}
		if _, err := store.Save(ctx, ws, user, "nope", nil, 1); !errors.Is(err, ErrReadOnly) {
			t.Fatalf("read-only member save = %v", err)
		}
	}
	for _, user := range []string{invited, removed, stranger} {
		if _, err := store.Get(ctx, ws, user); !errors.Is(err, ErrNotFound) {
			t.Fatalf("outsider read = %v", err)
		}
		if _, err := store.Save(ctx, ws, user, "nope", nil, 1); !errors.Is(err, ErrNotFound) {
			t.Fatalf("outsider save = %v", err)
		}
	}
	if _, err := store.Get(ctx, uuid.NewString(), owner); !errors.Is(err, ErrNotFound) {
		t.Fatalf("missing workspace = %v", err)
	}
}

func TestPostgres_DocumentGoesWithItsWorkspaceAndAuthor(t *testing.T) {
	pool := testPool(t)
	store, ctx := NewPostgresStore(pool), context.Background()
	owner, editor := newUser(t, pool), newUser(t, pool)
	ws := newWorkspace(t, pool, owner, map[string][2]string{editor: {"editor", "active"}})
	if _, err := store.Save(ctx, ws, editor, "x", nil, 0); err != nil {
		t.Fatal(err)
	}
	// The editor's account is deleted: the document stays, unattributed.
	if _, err := pool.Exec(ctx, "DELETE FROM users WHERE id = $1", editor); err != nil {
		t.Fatal(err)
	}
	if doc, err := store.Get(ctx, ws, owner); err != nil || doc.UpdatedBy != nil || doc.Source != "x" {
		t.Fatalf("after author deletion = %+v, %v", doc, err)
	}
	if _, err := pool.Exec(ctx, "DELETE FROM workspaces WHERE id = $1", ws); err != nil {
		t.Fatal(err)
	}
	var left int
	_ = pool.QueryRow(ctx, "SELECT count(*) FROM workspace_documents WHERE workspace_id = $1", ws).Scan(&left)
	if left != 0 {
		t.Fatalf("document outlived its workspace")
	}
}

func TestPostgres_DatabaseRefusesOversizedSource(t *testing.T) {
	pool := testPool(t)
	owner := newUser(t, pool)
	ws := newWorkspace(t, pool, owner, nil)
	big := make([]byte, MaxSourceRunes+1)
	for i := range big {
		big[i] = 'a'
	}
	_, err := pool.Exec(context.Background(),
		"INSERT INTO workspace_documents (workspace_id, source, version, updated_at) VALUES ($1, $2, 1, now())", ws, string(big))
	if err == nil {
		t.Fatal("the CHECK constraint must refuse a source over 100,000 characters")
	}
}
