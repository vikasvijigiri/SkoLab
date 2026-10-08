package websocket

import (
	"context"
	"errors"
	"os"
	"testing"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5/pgxpool"
)

// The real ticket store: a ticket opens one socket, for one workspace, once,
// within its minute; only its digest is stored; and the original Firebase
// sign-in time survives the exchange (socket revocation checks depend on it).
func TestPostgresTickets_AreSingleUseScopedAndExpiring(t *testing.T) {
	url := os.Getenv("TEST_DATABASE_URL")
	if url == "" {
		if os.Getenv("CI") == "true" {
			t.Fatal("TEST_DATABASE_URL must be set in CI")
		}
		t.Skip("TEST_DATABASE_URL not set")
	}
	ctx := context.Background()
	pool, err := pgxpool.New(ctx, url)
	if err != nil {
		t.Fatal(err)
	}
	defer pool.Close()
	user := "test-" + uuid.NewString()
	workspace, other := uuid.NewString(), uuid.NewString()
	if _, err := pool.Exec(ctx, "INSERT INTO users (id, display_name) VALUES ($1, 'Test')", user); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _, _ = pool.Exec(ctx, "DELETE FROM users WHERE id = $1", user) })
	for _, id := range []string{workspace, other} {
		if _, err := pool.Exec(ctx, "INSERT INTO workspaces (id, owner_id, title, created_at) VALUES ($1, $2, 'W', now())", id, user); err != nil {
			t.Fatal(err)
		}
	}
	store := NewPostgresWorkspaceTicketStore(pool)
	sessions := store.(sessionTicketStore)

	ticket, err := sessions.IssueSession(ctx, workspace, user, 1_791_000_000)
	if err != nil || len(ticket) < 40 {
		t.Fatalf("issue: %q %v", ticket, err)
	}
	var stored int
	_ = pool.QueryRow(ctx, "SELECT count(*) FROM websocket_tickets WHERE ticket_hash = $1", ticket).Scan(&stored)
	if stored != 0 {
		t.Fatal("the raw ticket was stored; only its digest may be")
	}
	if _, _, err := sessions.ConsumeSession(ctx, other, ticket); !errors.Is(err, ErrInvalidWorkspaceTicket) {
		t.Fatalf("ticket used for another workspace: %v", err)
	}
	uid, authTime, err := sessions.ConsumeSession(ctx, workspace, ticket)
	if err != nil || uid != user || authTime != 1_791_000_000 {
		t.Fatalf("consume: %q %d %v", uid, authTime, err)
	}
	if _, err := store.Consume(ctx, workspace, ticket); !errors.Is(err, ErrInvalidWorkspaceTicket) {
		t.Fatalf("ticket reused: %v", err)
	}

	expired, err := store.Issue(ctx, workspace, user)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := pool.Exec(ctx, "UPDATE websocket_tickets SET expires_at = NOW() - INTERVAL '1 second' WHERE ticket_hash = $1", ticketDigest(expired)); err != nil {
		t.Fatal(err)
	}
	if _, err := store.Consume(ctx, workspace, expired); !errors.Is(err, ErrInvalidWorkspaceTicket) {
		t.Fatalf("expired ticket accepted: %v", err)
	}
	// Issuing purges expired rows, so the table cannot grow without bound.
	if _, err := store.Issue(ctx, workspace, user); err != nil {
		t.Fatal(err)
	}
	var stale int
	_ = pool.QueryRow(ctx, "SELECT count(*) FROM websocket_tickets WHERE expires_at <= NOW()").Scan(&stale)
	if stale != 0 {
		t.Fatalf("%d expired tickets kept", stale)
	}

	closed, _ := pgxpool.New(ctx, url)
	closed.Close()
	broken := NewPostgresWorkspaceTicketStore(closed)
	if _, err := broken.Issue(ctx, workspace, user); !errors.Is(err, ErrWorkspaceTicketUnavailable) {
		t.Fatalf("issue on a dead pool: %v", err)
	}
	if _, err := broken.Consume(ctx, workspace, ticket); !errors.Is(err, ErrWorkspaceTicketUnavailable) {
		t.Fatalf("consume on a dead pool: %v", err)
	}
}
