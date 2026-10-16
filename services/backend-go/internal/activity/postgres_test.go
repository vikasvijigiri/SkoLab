package activity_test

import (
	"context"
	"os"
	"testing"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/skolab/backend-go/internal/activity"
	"github.com/skolab/backend-go/internal/document"
	"github.com/skolab/backend-go/internal/workspace"
)

// Integration tests against the real schema, through the stores that write
// the events (internal/document, internal/workspace). CI provides
// TEST_DATABASE_URL; elsewhere they skip.
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

func newUser(t *testing.T, pool *pgxpool.Pool, name string) string {
	t.Helper()
	id := "test-" + uuid.NewString()
	if _, err := pool.Exec(context.Background(), "INSERT INTO users (id, display_name) VALUES ($1, $2)", id, name); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _, _ = pool.Exec(context.Background(), "DELETE FROM users WHERE id = $1", id) })
	return id
}

// newWorkspace: owned by owner, created at created, with members (user ->
// role) who joined at joined.
func newWorkspace(t *testing.T, pool *pgxpool.Pool, owner string, created time.Time, members map[string]string, joined time.Time) string {
	t.Helper()
	ctx, id := context.Background(), uuid.NewString()
	if _, err := pool.Exec(ctx, "INSERT INTO workspaces (id, owner_id, title, created_at) VALUES ($1, $2, 'Spin waves', $3)", id, owner, created); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _, _ = pool.Exec(context.Background(), "DELETE FROM workspaces WHERE id = $1", id) })
	for user, role := range members {
		if _, err := pool.Exec(ctx, "INSERT INTO workspace_members (workspace_id, user_id, role, status, created_at) VALUES ($1, $2, $3, 'active', $4)", id, user, role, joined); err != nil {
			t.Fatal(err)
		}
	}
	return id
}

func feed(t *testing.T, store activity.Store, user string) activity.Page {
	t.Helper()
	page, err := store.Feed(context.Background(), user, nil, 50)
	if err != nil {
		t.Fatal(err)
	}
	return page
}

func kinds(page activity.Page) []string {
	out := []string{}
	for _, item := range page.Items {
		out = append(out, item.Kind)
	}
	return out
}

func save(t *testing.T, docs document.Store, ws, user string) {
	t.Helper()
	ctx := context.Background()
	current, err := docs.Get(ctx, ws, user)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := docs.Save(ctx, ws, user, "\\documentclass{article}", nil, current.Version); err != nil {
		t.Fatal(err)
	}
}

func TestPostgres_CoAuthorSavesMergeAndAreReadOnce(t *testing.T) {
	pool := testPool(t)
	ctx, store, docs := context.Background(), activity.NewPostgresStore(pool), document.NewPostgresStore(pool)
	ada, bo := newUser(t, pool, "Ada"), newUser(t, pool, "Bo")
	past := time.Now().UTC().Add(-time.Hour)
	ws := newWorkspace(t, pool, ada, past, map[string]string{bo: "editor"}, past)

	save(t, docs, ws, bo)
	save(t, docs, ws, bo)
	save(t, docs, ws, bo)
	page := feed(t, store, ada)
	if len(page.Items) != 1 || page.Unread != 1 || page.SeenAt != nil {
		t.Fatalf("three saves in a row are one unread event: %+v", page)
	}
	item := page.Items[0]
	if item.Kind != activity.DocumentEdited || item.Count != 3 || item.Actor == nil || item.Actor.DisplayName != "Bo" ||
		item.Workspace.ID != ws || item.Workspace.Title != "Spin waves" || !item.Unread {
		t.Fatalf("%+v", item)
	}
	if got := feed(t, store, bo); len(got.Items) != 0 || got.Unread != 0 {
		t.Fatalf("nobody is told about their own saves: %+v", got)
	}

	seen, err := store.MarkSeen(ctx, ada, time.Now())
	if err != nil || seen == nil {
		t.Fatal(seen, err)
	}
	if page := feed(t, store, ada); page.Unread != 0 || page.Items[0].Unread || page.SeenAt == nil {
		t.Fatalf("after catching up: %+v", page)
	}
	// Marking an earlier time (an older tab) never moves the point back.
	if again, _ := store.MarkSeen(ctx, ada, time.Now().Add(-time.Hour)); !again.Equal(*seen) {
		t.Fatalf("seen moved back: %v -> %v", seen, again)
	}
	// Another save in the same session is new again, still one line.
	time.Sleep(5 * time.Millisecond)
	save(t, docs, ws, bo)
	page = feed(t, store, ada)
	if len(page.Items) != 1 || page.Items[0].Count != 4 || page.Unread != 1 || !page.Items[0].Unread {
		t.Fatalf("%+v", page)
	}
}

func TestPostgres_SavesOutsideTheWindowStartANewEvent(t *testing.T) {
	pool := testPool(t)
	ctx, store := context.Background(), activity.NewPostgresStore(pool)
	ada, bo := newUser(t, pool, "Ada"), newUser(t, pool, "Bo")
	past := time.Now().UTC().Add(-2 * time.Hour)
	ws := newWorkspace(t, pool, ada, past, map[string]string{bo: "editor"}, past)
	for _, at := range []time.Time{past.Add(time.Minute), past.Add(5 * time.Minute), past.Add(30 * time.Minute)} {
		if err := activity.Record(ctx, pool, activity.Event{Kind: activity.DocumentEdited, WorkspaceID: ws, ActorID: bo, At: at}); err != nil {
			t.Fatal(err)
		}
	}
	page := feed(t, store, ada)
	if len(page.Items) != 2 || page.Items[0].Count != 1 || page.Items[1].Count != 2 {
		t.Fatalf("two sessions, the first of two saves: %+v", page.Items)
	}
}

func TestPostgres_ASoloDocumentRecordsNoSaves(t *testing.T) {
	pool := testPool(t)
	ada := newUser(t, pool, "Ada")
	ws := newWorkspace(t, pool, ada, time.Now().UTC().Add(-time.Hour), nil, time.Time{})
	save(t, document.NewPostgresStore(pool), ws, ada)
	var n int
	if err := pool.QueryRow(context.Background(), "SELECT count(*) FROM workspace_activity WHERE workspace_id = $1", ws).Scan(&n); err != nil || n != 0 {
		t.Fatalf("rows = %d, %v", n, err)
	}
}

func TestPostgres_MembersSeeOnlyWhatHappenedSinceTheyJoined(t *testing.T) {
	pool := testPool(t)
	ctx, store := context.Background(), activity.NewPostgresStore(pool)
	ada, bo, cy := newUser(t, pool, "Ada"), newUser(t, pool, "Bo"), newUser(t, pool, "Cy")
	start := time.Now().UTC().Add(-3 * time.Hour)
	ws := newWorkspace(t, pool, ada, start, map[string]string{bo: "editor"}, start)
	if err := activity.Record(ctx, pool, activity.Event{Kind: activity.DocumentEdited, WorkspaceID: ws, ActorID: bo, At: start.Add(time.Hour)}); err != nil {
		t.Fatal(err)
	}
	// Cy joins after that save.
	if _, err := pool.Exec(ctx, "INSERT INTO workspace_members (workspace_id, user_id, role, status, created_at) VALUES ($1, $2, 'viewer', 'active', $3)", ws, cy, start.Add(2*time.Hour)); err != nil {
		t.Fatal(err)
	}
	if page := feed(t, store, cy); len(page.Items) != 0 {
		t.Fatalf("history from before joining: %+v", page.Items)
	}
	if page := feed(t, store, ada); len(page.Items) != 1 {
		t.Fatalf("the owner sees it: %+v", page.Items)
	}
	// A stranger sees nothing of it.
	if page := feed(t, store, newUser(t, pool, "Eve")); len(page.Items) != 0 || page.Unread != 0 {
		t.Fatalf("%+v", page)
	}
}

func TestPostgres_MembershipChangesReachEveryoneConcerned(t *testing.T) {
	pool := testPool(t)
	ctx, store, sharing := context.Background(), activity.NewPostgresStore(pool), workspace.NewPostgresSharingStore(pool)
	ada, bo, cy := newUser(t, pool, "Ada"), newUser(t, pool, "Bo"), newUser(t, pool, "Cy")
	past := time.Now().UTC().Add(-time.Hour)
	ws := newWorkspace(t, pool, ada, past, map[string]string{bo: "viewer", cy: "editor"}, past)

	if err := sharing.ChangeRole(ctx, ws, ada, bo, "editor"); err != nil {
		t.Fatal(err)
	}
	if err := sharing.RemoveMember(ctx, ws, ada, bo); err != nil {
		t.Fatal(err)
	}
	// Bo lost access but is still told, about both.
	page := feed(t, store, bo)
	if got := kinds(page); len(got) != 2 || got[0] != activity.MemberRemoved || got[1] != activity.RoleChanged {
		t.Fatalf("bo: %v", got)
	}
	if page.Items[0].Subject == nil || page.Items[0].Subject.ID != bo || page.Items[0].Actor.ID != ada {
		t.Fatalf("%+v", page.Items[0])
	}
	if role := page.Items[1].Role; role == nil || *role != "editor" {
		t.Fatalf("role %v", role)
	}
	// Cy, still a member, sees the same; Ada sees none of her own actions.
	if got := kinds(feed(t, store, cy)); len(got) != 2 {
		t.Fatalf("cy: %v", got)
	}
	if got := kinds(feed(t, store, ada)); len(got) != 0 {
		t.Fatalf("ada: %v", got)
	}

	if err := sharing.RemoveMember(ctx, ws, cy, cy); err != nil {
		t.Fatal(err)
	}
	if got := kinds(feed(t, store, ada)); len(got) != 1 || got[0] != activity.MemberLeft {
		t.Fatalf("ada after cy left: %v", got)
	}
}

func TestPostgres_JoiningAndOwnershipAreAnnounced(t *testing.T) {
	pool := testPool(t)
	ctx, store, sharing := context.Background(), activity.NewPostgresStore(pool), workspace.NewPostgresSharingStore(pool)
	ada, bo := newUser(t, pool, "Ada"), newUser(t, pool, "Bo")
	ws := newWorkspace(t, pool, ada, time.Now().UTC().Add(-time.Hour), nil, time.Time{})

	invite, err := sharing.CreateInvite(ctx, ws, ada, "editor", time.Hour, nil)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := sharing.AcceptInvite(ctx, invite.Token, bo); err != nil {
		t.Fatal(err)
	}
	page := feed(t, store, ada)
	if got := kinds(page); len(got) != 1 || got[0] != activity.MemberJoined || *page.Items[0].Role != "editor" || page.Items[0].Actor.ID != bo {
		t.Fatalf("ada: %+v", page.Items)
	}
	if err := sharing.TransferOwnership(ctx, ws, ada, bo, 100); err != nil {
		t.Fatal(err)
	}
	page = feed(t, store, bo)
	if got := kinds(page); len(got) != 1 || got[0] != activity.OwnershipTransferred || page.Items[0].Subject.ID != bo {
		t.Fatalf("bo: %+v", page.Items)
	}
}

func TestPostgres_PagesDoNotRepeatOrSkip(t *testing.T) {
	pool := testPool(t)
	ctx, store := context.Background(), activity.NewPostgresStore(pool)
	ada, bo := newUser(t, pool, "Ada"), newUser(t, pool, "Bo")
	start := time.Now().UTC().Add(-time.Hour)
	ws := newWorkspace(t, pool, ada, start, map[string]string{bo: "editor"}, start)
	for i := 0; i < 5; i++ {
		// Two events share each instant, so the id breaks ties.
		at := start.Add(time.Duration(i/2) * time.Minute)
		if err := activity.Record(ctx, pool, activity.Event{Kind: activity.MemberJoined, WorkspaceID: ws, ActorID: bo, Role: "viewer", At: at}); err != nil {
			t.Fatal(err)
		}
	}
	seen := map[string]bool{}
	var after *activity.Cursor
	pages := 0
	for {
		page, err := store.Feed(ctx, ada, after, 2)
		if err != nil {
			t.Fatal(err)
		}
		pages++
		for _, item := range page.Items {
			if seen[item.ID] {
				t.Fatalf("repeated %s", item.ID)
			}
			seen[item.ID] = true
		}
		if page.Next == nil {
			break
		}
		after = page.Next
	}
	if len(seen) != 5 || pages != 3 {
		t.Fatalf("%d items over %d pages", len(seen), pages)
	}
}

func TestPostgres_OldEventsAreHiddenAndPruned(t *testing.T) {
	pool := testPool(t)
	ctx, store := context.Background(), activity.NewPostgresStore(pool)
	ada, bo := newUser(t, pool, "Ada"), newUser(t, pool, "Bo")
	long := time.Now().UTC().Add(-200 * 24 * time.Hour)
	ws := newWorkspace(t, pool, ada, long, map[string]string{bo: "editor"}, long)
	if _, err := pool.Exec(ctx, "INSERT INTO workspace_activity (workspace_id, kind, actor_id, occurred_at) VALUES ($1, 'member_joined', $2, $3)", ws, bo, long.Add(time.Hour)); err != nil {
		t.Fatal(err)
	}
	if page := feed(t, store, ada); len(page.Items) != 0 || page.Unread != 0 {
		t.Fatalf("older than retention: %+v", page)
	}
	if err := activity.Record(ctx, pool, activity.Event{Kind: activity.DocumentEdited, WorkspaceID: ws, ActorID: bo}); err != nil {
		t.Fatal(err)
	}
	var old int
	if err := pool.QueryRow(ctx, "SELECT count(*) FROM workspace_activity WHERE workspace_id = $1 AND kind = 'member_joined'", ws).Scan(&old); err != nil || old != 0 {
		t.Fatalf("not pruned: %d %v", old, err)
	}
}

func TestPostgres_ADeletedAccountLeavesSomeone(t *testing.T) {
	pool := testPool(t)
	ctx, store := context.Background(), activity.NewPostgresStore(pool)
	ada := newUser(t, pool, "Ada")
	gone := "test-" + uuid.NewString()
	if _, err := pool.Exec(ctx, "INSERT INTO users (id, display_name) VALUES ($1, 'Gone')", gone); err != nil {
		t.Fatal(err)
	}
	past := time.Now().UTC().Add(-time.Hour)
	ws := newWorkspace(t, pool, ada, past, map[string]string{gone: "editor"}, past)
	if err := activity.Record(ctx, pool, activity.Event{Kind: activity.DocumentEdited, WorkspaceID: ws, ActorID: gone}); err != nil {
		t.Fatal(err)
	}
	if _, err := pool.Exec(ctx, "DELETE FROM users WHERE id = $1", gone); err != nil {
		t.Fatal(err)
	}
	page := feed(t, store, ada)
	if len(page.Items) != 1 || page.Items[0].Actor != nil {
		t.Fatalf("%+v", page.Items)
	}
}

func TestPostgres_AReaderWithoutAProfileHasAnEmptyFeed(t *testing.T) {
	pool := testPool(t)
	store := activity.NewPostgresStore(pool)
	page, err := store.Feed(context.Background(), "test-no-such-user", nil, 10)
	if err != nil || len(page.Items) != 0 || page.Unread != 0 {
		t.Fatalf("%+v %v", page, err)
	}
	seen, err := store.MarkSeen(context.Background(), "test-no-such-user", time.Now())
	if err != nil || seen != nil {
		t.Fatalf("%v %v", seen, err)
	}
}

func TestPostgres_TheUnreadCountIsCapped(t *testing.T) {
	pool := testPool(t)
	ctx, store := context.Background(), activity.NewPostgresStore(pool)
	ada, bo := newUser(t, pool, "Ada"), newUser(t, pool, "Bo")
	past := time.Now().UTC().Add(-time.Hour)
	ws := newWorkspace(t, pool, ada, past, map[string]string{bo: "editor"}, past)
	if _, err := pool.Exec(ctx, `INSERT INTO workspace_activity (workspace_id, kind, actor_id, occurred_at)
		SELECT $1, 'member_joined', $2, $3::timestamp + n * interval '1 second' FROM generate_series(1, 120) n`, ws, bo, past); err != nil {
		t.Fatal(err)
	}
	if page := feed(t, store, ada); page.Unread != 100 || len(page.Items) != 50 || page.Next == nil {
		t.Fatalf("unread %d, %d items", page.Unread, len(page.Items))
	}
}
