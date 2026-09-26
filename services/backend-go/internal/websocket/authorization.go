package websocket

import (
	"context"
	"errors"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"
)

var ErrWorkspaceAuthorizationUnavailable = errors.New("workspace authorization unavailable")

// WorkspaceAuthorizer is deliberately narrow so the upgrade path can be tested
// independently from PostgreSQL while production uses the shared database as
// its single source of truth.
type WorkspaceAuthorizer interface {
	Authorize(ctx context.Context, workspaceID, userID string) (bool, error)
}

type postgresWorkspaceAuthorizer struct {
	pool *pgxpool.Pool
}

func NewPostgresWorkspaceAuthorizer(pool *pgxpool.Pool) WorkspaceAuthorizer {
	return &postgresWorkspaceAuthorizer{pool: pool}
}

// Authorize grants access to a workspace owner or to an explicitly active
// collaborator. Invited and removed members are denied. A missing pool or a
// database error fails closed: a WebSocket must never be upgraded without a
// verified authorization decision.
func (a *postgresWorkspaceAuthorizer) Authorize(ctx context.Context, workspaceID, userID string) (bool, error) {
	if a.pool == nil {
		return false, ErrWorkspaceAuthorizationUnavailable
	}

	ctx, cancel := context.WithTimeout(ctx, 2*time.Second)
	defer cancel()

	const query = `
		SELECT EXISTS (
			SELECT 1 FROM workspaces WHERE id = $1 AND owner_id = $2
			UNION ALL
			SELECT 1 FROM workspace_members
			WHERE workspace_id = $1 AND user_id = $2 AND status = 'active'
		)`

	var allowed bool
	if err := a.pool.QueryRow(ctx, query, workspaceID, userID).Scan(&allowed); err != nil {
		return false, errors.Join(ErrWorkspaceAuthorizationUnavailable, err)
	}
	return allowed, nil
}
