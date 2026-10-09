package document

import (
	"context"
	"errors"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

const queryTimeout = 3 * time.Second

type postgresStore struct {
	pool *pgxpool.Pool
}

// NewPostgresStore returns the production Store. A nil pool (database down
// at startup) makes every call fail with ErrUnavailable -> 503.
func NewPostgresStore(pool *pgxpool.Pool) Store {
	return &postgresStore{pool: pool}
}

// role is the caller's role in a workspace they own or are an active member
// of; no row means they cannot see it. $1 is the caller, $2 the workspace.
// Same rule as internal/workspace's visible query and the WebSocket authorizer.
const role = `
	SELECT CASE WHEN w.owner_id = $1 THEN 'owner' ELSE m.role END
	FROM workspaces w
	LEFT JOIN workspace_members m
	       ON m.workspace_id = w.id AND m.user_id = $1 AND m.status = 'active'
	WHERE w.id = $2 AND (w.owner_id = $1 OR m.user_id IS NOT NULL)`

const columns = `source, template_id, version, updated_at, updated_by`

func unavailable(err error) error {
	return errors.Join(ErrUnavailable, err)
}

type querier interface {
	QueryRow(ctx context.Context, sql string, args ...any) pgx.Row
}

func callerRole(ctx context.Context, q querier, workspaceID, userID string) (string, error) {
	var r string
	err := q.QueryRow(ctx, role, userID, workspaceID).Scan(&r)
	if errors.Is(err, pgx.ErrNoRows) {
		return "", ErrNotFound
	}
	if err != nil {
		return "", unavailable(err)
	}
	return r, nil
}

func scan(row pgx.Row, doc *Document) error {
	var updated time.Time
	if err := row.Scan(&doc.Source, &doc.TemplateID, &doc.Version, &updated, &doc.UpdatedBy); err != nil {
		return err
	}
	updated = updated.UTC()
	doc.UpdatedAt = &updated
	return nil
}

func (s *postgresStore) Get(ctx context.Context, workspaceID, userID string) (Document, error) {
	if s.pool == nil {
		return Document{}, ErrUnavailable
	}
	ctx, cancel := context.WithTimeout(ctx, queryTimeout)
	defer cancel()
	r, err := callerRole(ctx, s.pool, workspaceID, userID)
	if err != nil {
		return Document{}, err
	}
	doc := Document{WorkspaceID: workspaceID, Role: r}
	err = scan(s.pool.QueryRow(ctx, `SELECT `+columns+` FROM workspace_documents WHERE workspace_id = $1`, workspaceID), &doc)
	if errors.Is(err, pgx.ErrNoRows) {
		return doc, nil // never saved: version 0, empty source
	}
	if err != nil {
		return Document{}, unavailable(err)
	}
	return doc, nil
}

func (s *postgresStore) Save(ctx context.Context, workspaceID, userID, source string, templateID *string, baseVersion int) (Document, error) {
	if s.pool == nil {
		return Document{}, ErrUnavailable
	}
	ctx, cancel := context.WithTimeout(ctx, queryTimeout)
	defer cancel()
	tx, err := s.pool.Begin(ctx)
	if err != nil {
		return Document{}, unavailable(err)
	}
	defer tx.Rollback(ctx) //nolint:errcheck // no-op after Commit

	r, err := callerRole(ctx, tx, workspaceID, userID)
	if err != nil {
		return Document{}, err
	}
	if r != "owner" && r != "editor" {
		return Document{}, ErrReadOnly
	}
	doc := Document{WorkspaceID: workspaceID, Role: r}
	now := time.Now().UTC().Truncate(time.Microsecond)
	var row pgx.Row
	if baseVersion == 0 {
		// The first save creates the row; a concurrent first save loses.
		row = tx.QueryRow(ctx, `
			INSERT INTO workspace_documents (workspace_id, source, template_id, version, updated_at, updated_by)
			VALUES ($1, $2, $3, 1, $4, $5)
			ON CONFLICT (workspace_id) DO NOTHING
			RETURNING `+columns, workspaceID, source, templateID, now, userID)
	} else {
		row = tx.QueryRow(ctx, `
			UPDATE workspace_documents
			SET source = $2, template_id = COALESCE($3, template_id), version = version + 1,
			    updated_at = $4, updated_by = $5
			WHERE workspace_id = $1 AND version = $6
			RETURNING `+columns, workspaceID, source, templateID, now, userID, baseVersion)
	}
	err = scan(row, &doc)
	if errors.Is(err, pgx.ErrNoRows) {
		var current int
		err := tx.QueryRow(ctx, `SELECT version FROM workspace_documents WHERE workspace_id = $1`, workspaceID).Scan(&current)
		if err != nil && !errors.Is(err, pgx.ErrNoRows) {
			return Document{}, unavailable(err)
		}
		return Document{}, &ConflictError{Current: current}
	}
	if err != nil {
		return Document{}, unavailable(err)
	}
	if err := tx.Commit(ctx); err != nil {
		return Document{}, unavailable(err)
	}
	return doc, nil
}
