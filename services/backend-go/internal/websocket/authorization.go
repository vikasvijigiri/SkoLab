package websocket

import (
	"context"
	"errors"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

var ErrWorkspaceAuthorizationUnavailable = errors.New("workspace authorization unavailable")

// Workspace roles, as stored in workspace_members.role (and implied by
// workspaces.owner_id for the owner).
const (
	RoleOwner     = "owner"
	RoleEditor    = "editor"
	RoleCommenter = "commenter"
	RoleViewer    = "viewer"
)

// CanPublish reports whether a role may send collaboration messages. Viewers
// and commenters receive the live document but may not change it; comments
// are not part of the socket protocol.
func CanPublish(role string) bool {
	return role == RoleOwner || role == RoleEditor
}

// WorkspaceAuthorizer is deliberately narrow so the upgrade path can be tested
// independently from PostgreSQL while production uses the shared database as
// its single source of truth.
type WorkspaceAuthorizer interface {
	// Role returns the caller's role in the workspace, or "" when the caller
	// has no access (not the owner and not an active member).
	Role(ctx context.Context, workspaceID, userID string) (string, error)
}

type postgresWorkspaceAuthorizer struct {
	pool *pgxpool.Pool
}

func NewPostgresWorkspaceAuthorizer(pool *pgxpool.Pool) WorkspaceAuthorizer {
	return &postgresWorkspaceAuthorizer{pool: pool}
}

// Role grants access to a workspace owner or to an explicitly active
// collaborator. Invited and removed members get "". A missing pool or a
// database error fails closed: a WebSocket must never be upgraded without a
// verified authorization decision.
func (a *postgresWorkspaceAuthorizer) Role(ctx context.Context, workspaceID, userID string) (string, error) {
	if a.pool == nil {
		return "", ErrWorkspaceAuthorizationUnavailable
	}

	ctx, cancel := context.WithTimeout(ctx, 2*time.Second)
	defer cancel()

	const query = `
		SELECT CASE WHEN w.owner_id = $2 THEN 'owner' ELSE m.role END
		FROM workspaces w
		LEFT JOIN workspace_members m
		       ON m.workspace_id = w.id AND m.user_id = $2 AND m.status = 'active'
		WHERE w.id = $1`

	var role *string
	err := a.pool.QueryRow(ctx, query, workspaceID, userID).Scan(&role)
	if errors.Is(err, pgx.ErrNoRows) {
		return "", nil
	}
	if err != nil {
		return "", errors.Join(ErrWorkspaceAuthorizationUnavailable, err)
	}
	if role == nil {
		return "", nil
	}
	return *role, nil
}
