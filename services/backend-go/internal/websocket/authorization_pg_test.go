package websocket

import (
	"context"
	"os"
	"testing"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5/pgxpool"
)

// Runs against the real schema in CI (TEST_DATABASE_URL); skips elsewhere.
func TestPostgresWorkspaceAuthorizer_ResolvesRolesFromTheSchema(t *testing.T) {
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

	user := func() string {
		id := "test-" + uuid.NewString()
		if _, err := pool.Exec(ctx, "INSERT INTO users (id, display_name) VALUES ($1, 'Test')", id); err != nil {
			t.Fatal(err)
		}
		t.Cleanup(func() { _, _ = pool.Exec(ctx, "DELETE FROM users WHERE id = $1", id) })
		return id
	}
	owner, editor, invited, stranger := user(), user(), user(), user()
	workspace := uuid.NewString()
	if _, err := pool.Exec(ctx, "INSERT INTO workspaces (id, owner_id, title, created_at) VALUES ($1, $2, 'W', now())", workspace, owner); err != nil {
		t.Fatal(err)
	}
	for member, status := range map[string]string{editor: "active", invited: "invited"} {
		if _, err := pool.Exec(ctx, "INSERT INTO workspace_members (workspace_id, user_id, role, status, created_at) VALUES ($1, $2, 'editor', $3, now())", workspace, member, status); err != nil {
			t.Fatal(err)
		}
	}

	authorizer := NewPostgresWorkspaceAuthorizer(pool)
	cases := []struct{ name, workspace, user, want string }{
		{"owner", workspace, owner, RoleOwner},
		{"active editor", workspace, editor, RoleEditor},
		{"invited member", workspace, invited, ""},
		{"stranger", workspace, stranger, ""},
		{"missing workspace", uuid.NewString(), owner, ""},
	}
	for _, tc := range cases {
		got, err := authorizer.Role(ctx, tc.workspace, tc.user)
		if err != nil || got != tc.want {
			t.Fatalf("%s: role = %q, err = %v; want %q", tc.name, got, err, tc.want)
		}
	}
}
