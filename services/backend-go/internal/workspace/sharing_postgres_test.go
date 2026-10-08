package workspace

import (
	"context"
	"errors"
	"sync"
	"testing"
	"time"

	"github.com/google/uuid"
)

// Integration tests against the real schema (TEST_DATABASE_URL in CI; the
// helpers testPool/newUser live in postgres_test.go).

func newWorkspace(t *testing.T, store Store, owner string) string {
	t.Helper()
	ws, _, err := store.Create(context.Background(), owner, "Shared paper", "", 10)
	if err != nil {
		t.Fatal(err)
	}
	return ws.ID
}

func week() time.Duration { return 7 * 24 * time.Hour }

func TestSharing_InviteAndJoinFlow(t *testing.T) {
	pool := testPool(t)
	ctx := context.Background()
	sharing := NewPostgresSharingStore(pool)
	owner, guest := newUser(t, pool), newUser(t, pool)
	ws := newWorkspace(t, NewPostgresStore(pool), owner)

	inv, err := sharing.CreateInvite(ctx, ws, owner, "editor", week(), nil)
	if err != nil || inv.Token == "" {
		t.Fatalf("create: %+v %v", inv, err)
	}
	var stored string
	_ = pool.QueryRow(ctx, "SELECT token_digest FROM workspace_invites WHERE id = $1", inv.ID).Scan(&stored)
	if stored == inv.Token || stored != digest(inv.Token) {
		t.Fatal("only the token's digest may be stored")
	}

	preview, err := sharing.PreviewInvite(ctx, inv.Token)
	if err != nil || preview.WorkspaceTitle != "Shared paper" || preview.Role != "editor" {
		t.Fatalf("preview: %+v %v", preview, err)
	}
	m, err := sharing.AcceptInvite(ctx, inv.Token, guest)
	if err != nil || !m.Changed || m.Role != "editor" || m.WorkspaceID != ws {
		t.Fatalf("accept: %+v %v", m, err)
	}
	if role, _ := sharing.CallerRole(ctx, ws, guest); role != "editor" {
		t.Fatalf("guest role = %q", role)
	}
	members, err := sharing.ListMembers(ctx, ws, guest)
	if err != nil || len(members) != 2 || members[0].UserID != owner || members[0].Role != "owner" {
		t.Fatalf("members: %+v %v", members, err)
	}
}

func TestSharing_WhoMayManageInvites(t *testing.T) {
	pool := testPool(t)
	ctx := context.Background()
	sharing := NewPostgresSharingStore(pool)
	owner, editor, viewer, stranger := newUser(t, pool), newUser(t, pool), newUser(t, pool), newUser(t, pool)
	ws := newWorkspace(t, NewPostgresStore(pool), owner)
	for user, role := range map[string]string{editor: "editor", viewer: "viewer"} {
		inv, _ := sharing.CreateInvite(ctx, ws, owner, role, week(), nil)
		if _, err := sharing.AcceptInvite(ctx, inv.Token, user); err != nil {
			t.Fatal(err)
		}
	}

	inv, err := sharing.CreateInvite(ctx, ws, editor, "editor", week(), nil)
	if err != nil {
		t.Fatalf("editors may invite: %v", err)
	}
	if _, err := sharing.CreateInvite(ctx, ws, viewer, "viewer", week(), nil); !errors.Is(err, ErrInviteForbidden) {
		t.Fatalf("viewer create err = %v", err)
	}
	if _, err := sharing.CreateInvite(ctx, ws, stranger, "viewer", week(), nil); !errors.Is(err, ErrNotFound) {
		t.Fatalf("stranger create err = %v", err)
	}
	if err := sharing.RevokeInvite(ctx, ws, inv.ID, viewer); !errors.Is(err, ErrInviteForbidden) {
		t.Fatalf("viewer revoke err = %v", err)
	}
	if err := sharing.RevokeInvite(ctx, ws, inv.ID, editor); err != nil {
		t.Fatal(err)
	}
	if _, err := sharing.AcceptInvite(ctx, inv.Token, stranger); !errors.Is(err, ErrNotFound) {
		t.Fatalf("revoked link accepted: %v", err)
	}
	if invites, _ := sharing.ListInvites(ctx, ws, owner); len(invites) != 2 { // the two used, unlimited links
		t.Fatalf("active invites = %d", len(invites))
	}
}

func TestSharing_SingleUseLinkSurvivesConcurrentAccepts(t *testing.T) {
	pool := testPool(t)
	ctx := context.Background()
	sharing := NewPostgresSharingStore(pool)
	owner := newUser(t, pool)
	ws := newWorkspace(t, NewPostgresStore(pool), owner)
	one := 1
	inv, err := sharing.CreateInvite(ctx, ws, owner, "viewer", week(), &one)
	if err != nil {
		t.Fatal(err)
	}

	var wg sync.WaitGroup
	var mu sync.Mutex
	joined, refused := 0, 0
	for i := 0; i < 6; i++ {
		user := newUser(t, pool)
		wg.Add(1)
		go func() {
			defer wg.Done()
			_, err := sharing.AcceptInvite(ctx, inv.Token, user)
			mu.Lock()
			defer mu.Unlock()
			switch {
			case err == nil:
				joined++
			case errors.Is(err, ErrNotFound):
				refused++
			default:
				t.Error(err)
			}
		}()
	}
	wg.Wait()
	if joined != 1 || refused != 5 {
		t.Fatalf("joined = %d refused = %d; a single-use link must admit exactly one", joined, refused)
	}
}

func TestSharing_ExpiredLinksAndAcceptRules(t *testing.T) {
	pool := testPool(t)
	ctx := context.Background()
	sharing := NewPostgresSharingStore(pool)
	owner, editor, nobody := newUser(t, pool), newUser(t, pool), "no-profile-"+uuid.NewString()
	ws := newWorkspace(t, NewPostgresStore(pool), owner)

	editorLink, _ := sharing.CreateInvite(ctx, ws, owner, "editor", week(), nil)
	if _, err := sharing.AcceptInvite(ctx, editorLink.Token, editor); err != nil {
		t.Fatal(err)
	}
	// Never downgrade, and do not spend a use on it.
	viewerLink, _ := sharing.CreateInvite(ctx, ws, owner, "viewer", week(), nil)
	m, err := sharing.AcceptInvite(ctx, viewerLink.Token, editor)
	if err != nil || m.Changed || m.Role != "editor" {
		t.Fatalf("downgrade attempt: %+v %v", m, err)
	}
	var uses int
	_ = pool.QueryRow(ctx, "SELECT uses FROM workspace_invites WHERE id = $1", viewerLink.ID).Scan(&uses)
	if uses != 0 {
		t.Fatalf("uses = %d after a no-op accept", uses)
	}
	// The owner accepting their own link is a no-op.
	if m, err := sharing.AcceptInvite(ctx, viewerLink.Token, owner); err != nil || m.Role != "owner" || m.Changed {
		t.Fatalf("owner accept: %+v %v", m, err)
	}
	// Removal, then re-joining through a link, restores access.
	if err := sharing.RemoveMember(ctx, ws, owner, editor); err != nil {
		t.Fatal(err)
	}
	if role, _ := sharing.CallerRole(ctx, ws, editor); role != "" {
		t.Fatalf("removed member still has %q", role)
	}
	if m, err := sharing.AcceptInvite(ctx, viewerLink.Token, editor); err != nil || !m.Changed || m.Role != "viewer" {
		t.Fatalf("rejoin: %+v %v", m, err)
	}
	// A caller without a synced profile cannot join.
	if _, err := sharing.AcceptInvite(ctx, viewerLink.Token, nobody); !errors.Is(err, ErrProfileRequired) {
		t.Fatalf("no profile: %v", err)
	}
	// Expired links are unusable and invisible.
	expired, _ := sharing.CreateInvite(ctx, ws, owner, "viewer", week(), nil)
	_, _ = pool.Exec(ctx, "UPDATE workspace_invites SET expires_at = $2 WHERE id = $1", expired.ID, time.Now().UTC().Add(-time.Minute))
	if _, err := sharing.PreviewInvite(ctx, expired.Token); !errors.Is(err, ErrNotFound) {
		t.Fatalf("expired preview: %v", err)
	}
	if _, err := sharing.PreviewInvite(ctx, "inv_unknown"); !errors.Is(err, ErrNotFound) {
		t.Fatalf("unknown token: %v", err)
	}
}

func TestSharing_MemberManagementPermissions(t *testing.T) {
	pool := testPool(t)
	ctx := context.Background()
	sharing := NewPostgresSharingStore(pool)
	owner, editor, viewer := newUser(t, pool), newUser(t, pool), newUser(t, pool)
	ws := newWorkspace(t, NewPostgresStore(pool), owner)
	for user, role := range map[string]string{editor: "editor", viewer: "viewer"} {
		inv, _ := sharing.CreateInvite(ctx, ws, owner, role, week(), nil)
		if _, err := sharing.AcceptInvite(ctx, inv.Token, user); err != nil {
			t.Fatal(err)
		}
	}

	if err := sharing.ChangeRole(ctx, ws, editor, viewer, "editor"); !errors.Is(err, ErrForbidden) {
		t.Fatalf("editor changing roles: %v", err)
	}
	if err := sharing.ChangeRole(ctx, ws, owner, owner, "viewer"); !errors.Is(err, ErrOwnerImmutable) {
		t.Fatalf("owner demoting self: %v", err)
	}
	if err := sharing.ChangeRole(ctx, ws, owner, viewer, "commenter"); err != nil {
		t.Fatal(err)
	}
	if role, _ := sharing.CallerRole(ctx, ws, viewer); role != "commenter" {
		t.Fatalf("role after change = %q", role)
	}
	if err := sharing.RemoveMember(ctx, ws, editor, viewer); !errors.Is(err, ErrForbidden) {
		t.Fatalf("editor removing others: %v", err)
	}
	if err := sharing.RemoveMember(ctx, ws, owner, owner); !errors.Is(err, ErrOwnerCannotLeave) {
		t.Fatalf("owner leaving: %v", err)
	}
	if err := sharing.RemoveMember(ctx, ws, viewer, viewer); err != nil {
		t.Fatalf("member leaving: %v", err)
	}
	members, _ := sharing.ListMembers(ctx, ws, owner)
	if len(members) != 2 {
		t.Fatalf("members after leave = %+v", members)
	}
	if _, err := sharing.ListMembers(ctx, ws, viewer); !errors.Is(err, ErrNotFound) {
		t.Fatalf("former member listing: %v", err)
	}
}

// A link is only as good as its creator's current authority: an editor who
// is removed or demoted cannot rejoin (or let anyone in) through a link they
// minted while they could invite, and re-promotion does not revive it.
func TestSharing_LinksDieWithTheirCreatorsAuthority(t *testing.T) {
	pool := testPool(t)
	ctx := context.Background()
	sharing := NewPostgresSharingStore(pool)
	owner, editor, demoted, guest := newUser(t, pool), newUser(t, pool), newUser(t, pool), newUser(t, pool)
	ws := newWorkspace(t, NewPostgresStore(pool), owner)
	for _, user := range []string{editor, demoted} {
		inv, _ := sharing.CreateInvite(ctx, ws, owner, "editor", week(), nil)
		if _, err := sharing.AcceptInvite(ctx, inv.Token, user); err != nil {
			t.Fatal(err)
		}
	}
	removedLink, err := sharing.CreateInvite(ctx, ws, editor, "editor", week(), nil)
	if err != nil {
		t.Fatal(err)
	}
	demotedLink, err := sharing.CreateInvite(ctx, ws, demoted, "viewer", week(), nil)
	if err != nil {
		t.Fatal(err)
	}

	if err := sharing.RemoveMember(ctx, ws, owner, editor); err != nil {
		t.Fatal(err)
	}
	if _, err := sharing.AcceptInvite(ctx, removedLink.Token, editor); !errors.Is(err, ErrNotFound) {
		t.Fatalf("removed editor rejoining through own link: %v", err)
	}
	if role, _ := sharing.CallerRole(ctx, ws, editor); role != "" {
		t.Fatalf("removed editor regained %q", role)
	}

	if err := sharing.ChangeRole(ctx, ws, owner, demoted, "viewer"); err != nil {
		t.Fatal(err)
	}
	if _, err := sharing.PreviewInvite(ctx, demotedLink.Token); !errors.Is(err, ErrNotFound) {
		t.Fatalf("demoted editor's link still previews: %v", err)
	}
	if err := sharing.ChangeRole(ctx, ws, owner, demoted, "editor"); err != nil {
		t.Fatal(err)
	}
	if _, err := sharing.AcceptInvite(ctx, demotedLink.Token, guest); !errors.Is(err, ErrNotFound) {
		t.Fatalf("re-promotion revived a revoked link: %v", err)
	}
	invites, err := sharing.ListInvites(ctx, ws, owner)
	if err != nil {
		t.Fatal(err)
	}
	for _, inv := range invites {
		if inv.ID == removedLink.ID || inv.ID == demotedLink.ID {
			t.Fatalf("stale link still listed: %+v", inv)
		}
	}

	// A link from someone who still has the authority keeps working.
	live, _ := sharing.CreateInvite(ctx, ws, demoted, "viewer", week(), nil)
	if m, err := sharing.AcceptInvite(ctx, live.Token, guest); err != nil || m.Role != "viewer" {
		t.Fatalf("current editor's link: %+v %v", m, err)
	}
}
