package workspace

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
		t.Skip("TEST_DATABASE_URL not set")
	}
	pool, err := pgxpool.New(context.Background(), url)
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
