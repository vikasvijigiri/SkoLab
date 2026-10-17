package workspace

import (
	"context"
	"errors"
	"os"
	"sync"
	"testing"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

// Integration tests against the real schema. CI provides TEST_DATABASE_URL
// (the same Postgres the Python suite bootstraps); elsewhere they skip.
func testPool(t *testing.T) *pgxpool.Pool {
	t.Helper()
	url := os.Getenv("TEST_DATABASE_URL")
	if url == "" {
		if os.Getenv("CI") == "true" {
			// A silent skip in CI would report green without testing anything.
			t.Fatal("TEST_DATABASE_URL must be set in CI")
		}
		t.Skip("TEST_DATABASE_URL not set")
	}
	config, err := pgxpool.ParseConfig(url)
	if err != nil {
		t.Fatal(err)
	}
	// As in production (internal/db), where the pooler forbids prepared statements.
	config.ConnConfig.DefaultQueryExecMode = pgx.QueryExecModeExec
	pool, err := pgxpool.NewWithConfig(context.Background(), config)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(pool.Close)
	return pool
}

// newUser inserts a users row that is removed (with its workspaces, via
// cascade) when the test ends.
func newUser(t *testing.T, pool *pgxpool.Pool) string {
	t.Helper()
	id := "test-" + uuid.NewString()
	if _, err := pool.Exec(context.Background(), "INSERT INTO users (id, display_name) VALUES ($1, 'Test')", id); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _, _ = pool.Exec(context.Background(), "DELETE FROM users WHERE id = $1", id) })
	return id
}

func TestPostgres_CreateIsVisibleOnlyToOwnerAndActiveMembers(t *testing.T) {
	pool := testPool(t)
	store, ctx := NewPostgresStore(pool), context.Background()
	owner, member, stranger := newUser(t, pool), newUser(t, pool), newUser(t, pool)

	ws, replayed, err := store.Create(ctx, owner, "Thesis", "", 10)
	if err != nil || replayed || ws.Role != "owner" {
		t.Fatalf("create = %+v, %v, %v", ws, replayed, err)
	}
	var ownerRows int
	_ = pool.QueryRow(ctx, "SELECT count(*) FROM workspace_members WHERE workspace_id = $1 AND user_id = $2 AND role = 'owner' AND status = 'active'", ws.ID, owner).Scan(&ownerRows)
	if ownerRows != 1 {
		t.Fatalf("owner membership rows = %d, want 1 (same transaction)", ownerRows)
	}
	if _, err := pool.Exec(ctx, "INSERT INTO workspace_members (workspace_id, user_id, role, status, created_at) VALUES ($1, $2, 'editor', 'active', now())", ws.ID, member); err != nil {
		t.Fatal(err)
	}

	if got, err := store.Get(ctx, ws.ID, member); err != nil || got.Role != "editor" || got.OwnerID != owner {
		t.Fatalf("member get = %+v, %v", got, err)
	}
	if _, err := store.Get(ctx, ws.ID, stranger); !errors.Is(err, ErrNotFound) {
		t.Fatalf("stranger get err = %v, want not found", err)
	}
	if _, err := store.Rename(ctx, ws.ID, member, "Hijack"); !errors.Is(err, ErrForbidden) {
		t.Fatalf("member rename err = %v, want forbidden", err)
	}
	if _, err := store.Rename(ctx, ws.ID, stranger, "Hijack"); !errors.Is(err, ErrNotFound) {
		t.Fatalf("stranger rename err = %v, want not found", err)
	}
	if err := store.Delete(ctx, ws.ID, member); !errors.Is(err, ErrForbidden) {
		t.Fatalf("member delete err = %v, want forbidden", err)
	}
	if got, err := store.Rename(ctx, ws.ID, owner, "Thesis v2"); err != nil || got.Title != "Thesis v2" {
		t.Fatalf("owner rename = %+v, %v", got, err)
	}
	if err := store.Delete(ctx, ws.ID, owner); err != nil {
		t.Fatal(err)
	}
	var remaining int
	_ = pool.QueryRow(ctx, "SELECT count(*) FROM workspace_members WHERE workspace_id = $1", ws.ID).Scan(&remaining)
	if remaining != 0 {
		t.Fatalf("memberships after delete = %d, want cascade to 0", remaining)
	}
	if _, err := store.Get(ctx, ws.ID, owner); !errors.Is(err, ErrNotFound) {
		t.Fatalf("get after delete err = %v", err)
	}
}

func TestPostgres_IdempotencyKeySurvivesConcurrentRetries(t *testing.T) {
	pool := testPool(t)
	store, ctx := NewPostgresStore(pool), context.Background()
	owner, other := newUser(t, pool), newUser(t, pool)

	var wg sync.WaitGroup
	ids := make(chan string, 10)
	for i := 0; i < 10; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			if ws, _, err := store.Create(ctx, owner, "Paper", "key-1", 10); err == nil {
				ids <- ws.ID
			} else {
				t.Error(err)
			}
		}()
	}
	wg.Wait()
	close(ids)
	seen := map[string]bool{}
	for id := range ids {
		seen[id] = true
	}
	if len(seen) != 1 {
		t.Fatalf("10 retries produced %d workspaces, want 1", len(seen))
	}
	if _, replayed, err := store.Create(ctx, owner, "Paper", "key-1", 10); err != nil || !replayed {
		t.Fatalf("replay = %v, %v", replayed, err)
	}
	if _, _, err := store.Create(ctx, owner, "Different", "key-1", 10); !errors.Is(err, ErrIdempotencyMismatch) {
		t.Fatalf("reused key err = %v", err)
	}
	// Keys are scoped per owner: another user's identical key is independent.
	if _, replayed, err := store.Create(ctx, other, "Paper", "key-1", 10); err != nil || replayed {
		t.Fatalf("other owner = %v, %v", replayed, err)
	}
}

func TestPostgres_CapHoldsUnderConcurrentCreates(t *testing.T) {
	pool := testPool(t)
	store, ctx := NewPostgresStore(pool), context.Background()
	owner := newUser(t, pool)

	var wg sync.WaitGroup
	var mu sync.Mutex
	created, limited := 0, 0
	for i := 0; i < 8; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			_, _, err := store.Create(ctx, owner, "W", "", 3)
			mu.Lock()
			defer mu.Unlock()
			switch {
			case err == nil:
				created++
			case errors.Is(err, ErrLimitReached):
				limited++
			default:
				t.Error(err)
			}
		}()
	}
	wg.Wait()
	if created != 3 || limited != 5 {
		t.Fatalf("created = %d limited = %d, want 3 and 5", created, limited)
	}
}

func TestPostgres_CreateRequiresASyncedProfile(t *testing.T) {
	pool := testPool(t)
	_, _, err := NewPostgresStore(pool).Create(context.Background(), "no-such-user-"+uuid.NewString(), "W", "", 10)
	if !errors.Is(err, ErrProfileRequired) {
		t.Fatalf("err = %v, want profile required", err)
	}
}

func TestPostgres_ListPagesNewestFirstWithoutGapsOrDuplicates(t *testing.T) {
	pool := testPool(t)
	store, ctx := NewPostgresStore(pool), context.Background()
	owner := newUser(t, pool)
	for i := 0; i < 5; i++ {
		if _, _, err := store.Create(ctx, owner, "W", "", 10); err != nil {
			t.Fatal(err)
		}
	}
	var seen []Workspace
	var after *Cursor
	for page := 0; page < 10; page++ {
		items, err := store.List(ctx, owner, after, 2)
		if err != nil {
			t.Fatal(err)
		}
		if len(items) == 0 {
			break
		}
		seen = append(seen, items...)
		last := items[len(items)-1]
		after = &Cursor{CreatedAt: last.CreatedAt, ID: last.ID}
	}
	if len(seen) != 5 {
		t.Fatalf("paged %d workspaces, want 5", len(seen))
	}
	ids := map[string]bool{}
	for i, ws := range seen {
		ids[ws.ID] = true
		if i > 0 && ws.CreatedAt.After(seen[i-1].CreatedAt) {
			t.Fatal("not newest first")
		}
	}
	if len(ids) != 5 {
		t.Fatal("duplicate workspace across pages")
	}
}

func TestPostgres_NilPoolIsUnavailable(t *testing.T) {
	store := NewPostgresStore(nil)
	if _, _, err := store.Create(context.Background(), "u", "W", "", 1); !errors.Is(err, ErrUnavailable) {
		t.Fatalf("err = %v", err)
	}
	if _, err := store.List(context.Background(), "u", nil, 1); !errors.Is(err, ErrUnavailable) {
		t.Fatalf("err = %v", err)
	}
}

func TestPostgres_ListSummarizesEachDocumentForItsCard(t *testing.T) {
	pool := testPool(t)
	store, ctx := NewPostgresStore(pool), context.Background()
	owner, coauthor, left := newUser(t, pool), newUser(t, pool), newUser(t, pool)
	exec := func(sql string, args ...any) {
		t.Helper()
		if _, err := pool.Exec(ctx, sql, args...); err != nil {
			t.Fatal(err)
		}
	}
	exec("UPDATE users SET display_name = 'Ada' WHERE id = $1", owner)
	exec("UPDATE users SET display_name = 'Grace' WHERE id = $1", coauthor)

	blank, _, err := store.Create(ctx, owner, "Blank", "", 10)
	if err != nil {
		t.Fatal(err)
	}
	paper, _, err := store.Create(ctx, owner, "Paper", "", 10)
	if err != nil {
		t.Fatal(err)
	}
	exec("INSERT INTO workspace_members (workspace_id, user_id, role, status, created_at) VALUES ($1, $2, 'editor', 'active', now())", paper.ID, coauthor)
	exec("INSERT INTO workspace_members (workspace_id, user_id, role, status, created_at) VALUES ($1, $2, 'viewer', 'removed', now())", paper.ID, left)
	edited := time.Date(2026, 10, 1, 9, 0, 0, 0, time.UTC)
	exec("INSERT INTO workspace_documents (workspace_id, source, version, updated_at, updated_by, words, progress, stats_version) VALUES ($1, 'x', 3, $2, $3, 1234, 40, 1)",
		paper.ID, edited, coauthor)
	compiled := edited.Add(time.Hour)
	exec("INSERT INTO workspace_outputs (workspace_id, pdf, size, compiled_at, compiled_by) VALUES ($1, '%PDF', 4, $2, $3)", paper.ID, compiled, owner)

	items, err := store.List(ctx, coauthor, nil, 10)
	if err != nil || len(items) != 1 || items[0].Summary == nil {
		t.Fatalf("%+v %v", items, err)
	}
	s := items[0].Summary
	if s.AuthorsTotal != 2 || len(s.Authors) != 2 ||
		s.Authors[0] != (Person{ID: owner, DisplayName: "Ada", Role: "owner"}) ||
		s.Authors[1] != (Person{ID: coauthor, DisplayName: "Grace", Role: "editor"}) {
		t.Fatalf("authors: %+v", s)
	}
	if s.Words != 1234 || s.Progress != 40 || s.CompiledAt == nil || !s.CompiledAt.Equal(compiled) ||
		s.UpdatedAt == nil || !s.UpdatedAt.Equal(edited) || s.UpdatedBy == nil || *s.UpdatedBy != (Person{ID: coauthor, DisplayName: "Grace"}) {
		t.Fatalf("facts: %+v %+v", s, s.UpdatedBy)
	}

	// A later file edit is the latest edit.
	later := edited.Add(2 * time.Hour)
	exec("INSERT INTO workspace_files (id, workspace_id, path, kind, content, size, content_type, version, created_at, updated_at, updated_by) VALUES ($1, $2, 'refs.bib', 'text', '', 0, 'text/x-bibtex', 1, $3, $3, $4)",
		uuid.NewString(), paper.ID, later, owner)
	items, _ = store.List(ctx, owner, nil, 10)
	if len(items) != 2 || items[0].ID != paper.ID {
		t.Fatalf("%+v", items)
	}
	if s := items[0].Summary; !s.UpdatedAt.Equal(later) || s.UpdatedBy.ID != owner {
		t.Fatalf("%+v", s)
	}
	// A new, untouched document has only its owner.
	if s := items[1].Summary; s == nil || s.AuthorsTotal != 1 || s.Words != 0 || s.UpdatedAt != nil || s.UpdatedBy != nil || s.CompiledAt != nil {
		t.Fatalf("%+v", s)
	}
	if items[1].ID != blank.ID {
		t.Fatal(items[1].ID)
	}
	// Only the list carries a summary.
	if ws, err := store.Get(ctx, paper.ID, owner); err != nil || ws.Summary != nil {
		t.Fatalf("%+v %v", ws, err)
	}
}

func TestPostgres_ListNamesAtMostMaxAuthors(t *testing.T) {
	pool := testPool(t)
	store, ctx := NewPostgresStore(pool), context.Background()
	owner := newUser(t, pool)
	ws, _, err := store.Create(ctx, owner, "Big team", "", 10)
	if err != nil {
		t.Fatal(err)
	}
	for i := 0; i < MaxAuthors+2; i++ {
		if _, err := pool.Exec(ctx, "INSERT INTO workspace_members (workspace_id, user_id, role, status, created_at) VALUES ($1, $2, 'viewer', 'active', now())", ws.ID, newUser(t, pool)); err != nil {
			t.Fatal(err)
		}
	}
	items, err := store.List(ctx, owner, nil, 10)
	if err != nil || len(items) != 1 {
		t.Fatal(err)
	}
	if s := items[0].Summary; len(s.Authors) != MaxAuthors || s.AuthorsTotal != MaxAuthors+3 || s.Authors[0].ID != owner {
		t.Fatalf("%d of %d, first %s", len(s.Authors), s.AuthorsTotal, s.Authors[0].ID)
	}
}
