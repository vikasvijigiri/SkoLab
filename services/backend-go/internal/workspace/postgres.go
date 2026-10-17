package workspace

import (
	"context"
	"errors"
	"strconv"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgconn"
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

// visible selects workspaces the user owns or is an active member of, with
// the caller's role. $1 is always the caller. It mirrors the WebSocket
// authorizer (internal/websocket/authorization.go) so the REST API and the
// socket can never disagree about access.
const visible = `
	SELECT w.id, w.title, w.owner_id, w.created_at,
	       CASE WHEN w.owner_id = $1 THEN 'owner' ELSE m.role END
	FROM workspaces w
	LEFT JOIN workspace_members m
	       ON m.workspace_id = w.id AND m.user_id = $1 AND m.status = 'active'
	WHERE (w.owner_id = $1 OR m.user_id IS NOT NULL)`

type scanner interface {
	Scan(dest ...any) error
}

func scanWorkspace(row scanner) (Workspace, error) {
	var ws Workspace
	err := row.Scan(&ws.ID, &ws.Title, &ws.OwnerID, &ws.CreatedAt, &ws.Role)
	ws.CreatedAt = ws.CreatedAt.UTC()
	return ws, err
}

func unavailable(err error) error {
	return errors.Join(ErrUnavailable, err)
}

func (s *postgresStore) Create(ctx context.Context, ownerID, title, requestID string, maxPerUser int) (Workspace, bool, error) {
	if s.pool == nil {
		return Workspace{}, false, ErrUnavailable
	}
	ctx, cancel := context.WithTimeout(ctx, queryTimeout)
	defer cancel()
	return create(ctx, s.pool, ownerID, title, requestID, maxPerUser, nil)
}

// Filler adds content to a workspace inside the transaction that creates it.
type Filler func(ctx context.Context, tx pgx.Tx, ws Workspace) error

// CreateFilled creates a workspace the way POST /workspaces does (the
// per-owner cap, the owner's membership) and runs fill in the same
// transaction, so an imported project appears whole or not at all. An error
// from fill is returned as is.
func CreateFilled(ctx context.Context, pool *pgxpool.Pool, ownerID, title string, maxPerUser int, timeout time.Duration, fill Filler) (Workspace, error) {
	if pool == nil {
		return Workspace{}, ErrUnavailable
	}
	ctx, cancel := context.WithTimeout(ctx, timeout)
	defer cancel()
	ws, _, err := create(ctx, pool, ownerID, title, "", maxPerUser, fill)
	return ws, err
}

// MaxPerUser is the per-owner workspace cap in force.
func MaxPerUser() int { return maxPerUser() }

func create(ctx context.Context, pool *pgxpool.Pool, ownerID, title, requestID string, maxPerUser int, fill Filler) (Workspace, bool, error) {
	tx, err := pool.Begin(ctx)
	if err != nil {
		return Workspace{}, false, unavailable(err)
	}
	defer tx.Rollback(ctx) //nolint:errcheck // no-op after Commit

	// Serialize one owner's creates so the cap and the idempotency lookup
	// cannot race between concurrent requests or gateway replicas.
	if _, err := tx.Exec(ctx, "SELECT pg_advisory_xact_lock(hashtext('workspace-create:' || $1))", ownerID); err != nil {
		return Workspace{}, false, unavailable(err)
	}
	if requestID != "" {
		ws, err := scanWorkspace(tx.QueryRow(ctx, visible+` AND w.owner_id = $1 AND w.create_request_id = $2`, ownerID, requestID))
		switch {
		case err == nil && ws.Title != title:
			return Workspace{}, false, ErrIdempotencyMismatch
		case err == nil:
			return ws, true, nil
		case !errors.Is(err, pgx.ErrNoRows):
			return Workspace{}, false, unavailable(err)
		}
	}
	var owned int
	if err := tx.QueryRow(ctx, "SELECT count(*) FROM workspaces WHERE owner_id = $1", ownerID).Scan(&owned); err != nil {
		return Workspace{}, false, unavailable(err)
	}
	if owned >= maxPerUser {
		return Workspace{}, false, ErrLimitReached
	}

	ws := Workspace{ID: uuid.NewString(), Title: title, OwnerID: ownerID, Role: "owner",
		CreatedAt: time.Now().UTC().Truncate(time.Microsecond)} // Postgres timestamp precision
	var request any
	if requestID != "" {
		request = requestID
	}
	if _, err := tx.Exec(ctx,
		`INSERT INTO workspaces (id, owner_id, title, created_at, create_request_id) VALUES ($1, $2, $3, $4, $5)`,
		ws.ID, ownerID, title, ws.CreatedAt, request); err != nil {
		var pgErr *pgconn.PgError
		if errors.As(err, &pgErr) && pgErr.Code == "23503" { // foreign_key_violation: no users row
			return Workspace{}, false, ErrProfileRequired
		}
		return Workspace{}, false, unavailable(err)
	}
	// The owner's membership commits with the workspace or not at all.
	if _, err := tx.Exec(ctx,
		`INSERT INTO workspace_members (workspace_id, user_id, role, status, created_at) VALUES ($1, $2, 'owner', 'active', $3)`,
		ws.ID, ownerID, ws.CreatedAt); err != nil {
		return Workspace{}, false, unavailable(err)
	}
	if fill != nil {
		if err := fill(ctx, tx, ws); err != nil {
			return Workspace{}, false, err
		}
	}
	if err := tx.Commit(ctx); err != nil {
		return Workspace{}, false, unavailable(err)
	}
	return ws, false, nil
}

func (s *postgresStore) List(ctx context.Context, userID string, after *Cursor, limit int) ([]Workspace, error) {
	if s.pool == nil {
		return nil, ErrUnavailable
	}
	ctx, cancel := context.WithTimeout(ctx, queryTimeout)
	defer cancel()
	query, args := visible, []any{userID}
	if after != nil {
		// Keyset pagination: stable under concurrent inserts, O(page) cost.
		query += ` AND (w.created_at, w.id) < ($2, $3)`
		args = append(args, after.CreatedAt, after.ID)
	}
	args = append(args, limit)
	query += ` ORDER BY w.created_at DESC, w.id DESC LIMIT $` + strconv.Itoa(len(args))
	rows, err := s.pool.Query(ctx, query, args...)
	if err != nil {
		return nil, unavailable(err)
	}
	defer rows.Close()
	var items []Workspace
	for rows.Next() {
		ws, err := scanWorkspace(rows)
		if err != nil {
			return nil, unavailable(err)
		}
		items = append(items, ws)
	}
	if err := rows.Err(); err != nil {
		return nil, unavailable(err)
	}
	rows.Close()
	if err := summarize(ctx, s.pool, items); err != nil {
		return nil, unavailable(err)
	}
	return items, nil
}

// summarize fills in each listed workspace's Summary with two queries for
// the whole page, whatever its size.
func summarize(ctx context.Context, pool *pgxpool.Pool, items []Workspace) error {
	if len(items) == 0 {
		return nil
	}
	ids := make([]string, len(items))
	byID := make(map[string]*Summary, len(items))
	for i := range items {
		ids[i] = items[i].ID
		items[i].Summary = &Summary{Authors: []Person{}}
		byID[items[i].ID] = items[i].Summary
	}

	rows, err := pool.Query(ctx, `
		SELECT w.id, COALESCE(d.words, 0), COALESCE(d.progress, 0), o.compiled_at, e.edited_at, e.editor, u.display_name
		FROM workspaces w
		LEFT JOIN workspace_documents d ON d.workspace_id = w.id
		LEFT JOIN workspace_outputs o ON o.workspace_id = w.id
		LEFT JOIN LATERAL (
			SELECT edited_at, editor FROM (
				SELECT d.updated_at AS edited_at, d.updated_by AS editor WHERE d.workspace_id IS NOT NULL
				UNION ALL
				(SELECT f.updated_at, f.updated_by FROM workspace_files f WHERE f.workspace_id = w.id ORDER BY f.updated_at DESC LIMIT 1)
			) edits ORDER BY edited_at DESC LIMIT 1
		) e ON true
		LEFT JOIN users u ON u.id = e.editor
		WHERE w.id = ANY($1)`, ids)
	if err != nil {
		return err
	}
	for rows.Next() {
		var id string
		var words, progress int
		var compiled, at *time.Time
		var by, name *string
		if err := rows.Scan(&id, &words, &progress, &compiled, &at, &by, &name); err != nil {
			rows.Close()
			return err
		}
		s := byID[id]
		s.Words, s.Progress = words, progress
		if compiled != nil {
			t := compiled.UTC()
			s.CompiledAt = &t
		}
		if at != nil {
			t := at.UTC()
			s.UpdatedAt = &t
		}
		if by != nil {
			s.UpdatedBy = &Person{ID: *by}
			if name != nil {
				s.UpdatedBy.DisplayName = *name
			}
		}
	}
	rows.Close()
	if err := rows.Err(); err != nil {
		return err
	}

	rows, err = pool.Query(ctx, `
		SELECT workspace_id, user_id, display_name, role, total FROM (
			SELECT a.workspace_id, a.user_id, u.display_name, a.role,
			       row_number() OVER (PARTITION BY a.workspace_id ORDER BY a.rank, a.joined, a.user_id) AS n,
			       count(*) OVER (PARTITION BY a.workspace_id) AS total
			FROM (
				SELECT w.id AS workspace_id, w.owner_id AS user_id, 'owner' AS role, 0 AS rank, w.created_at AS joined
				FROM workspaces w WHERE w.id = ANY($1)
				UNION ALL
				SELECT m.workspace_id, m.user_id, m.role, 1, m.created_at
				FROM workspace_members m JOIN workspaces w ON w.id = m.workspace_id
				WHERE m.workspace_id = ANY($1) AND m.status = 'active' AND m.user_id <> w.owner_id
			) a JOIN users u ON u.id = a.user_id
		) ranked WHERE n <= $2 ORDER BY workspace_id, n`, ids, MaxAuthors)
	if err != nil {
		return err
	}
	defer rows.Close()
	for rows.Next() {
		var id string
		var p Person
		var total int
		if err := rows.Scan(&id, &p.ID, &p.DisplayName, &p.Role, &total); err != nil {
			return err
		}
		s := byID[id]
		s.Authors = append(s.Authors, p)
		s.AuthorsTotal = total
	}
	return rows.Err()
}

func (s *postgresStore) Get(ctx context.Context, id, userID string) (Workspace, error) {
	if s.pool == nil {
		return Workspace{}, ErrUnavailable
	}
	ctx, cancel := context.WithTimeout(ctx, queryTimeout)
	defer cancel()
	ws, err := scanWorkspace(s.pool.QueryRow(ctx, visible+` AND w.id = $2`, userID, id))
	if errors.Is(err, pgx.ErrNoRows) {
		return Workspace{}, ErrNotFound
	}
	if err != nil {
		return Workspace{}, unavailable(err)
	}
	return ws, nil
}

// ownerOnly turns "no row changed" into NotFound (cannot see it) or
// Forbidden (can see it, but is not the owner).
func (s *postgresStore) ownerOnly(ctx context.Context, id, userID string) error {
	if _, err := s.Get(ctx, id, userID); err != nil {
		return err
	}
	return ErrForbidden
}

func (s *postgresStore) Rename(ctx context.Context, id, userID, title string) (Workspace, error) {
	if s.pool == nil {
		return Workspace{}, ErrUnavailable
	}
	ctx, cancel := context.WithTimeout(ctx, queryTimeout)
	defer cancel()
	ws := Workspace{ID: id, Title: title, OwnerID: userID, Role: "owner"}
	err := s.pool.QueryRow(ctx,
		`UPDATE workspaces SET title = $3 WHERE id = $1 AND owner_id = $2 RETURNING created_at`,
		id, userID, title).Scan(&ws.CreatedAt)
	if errors.Is(err, pgx.ErrNoRows) {
		return Workspace{}, s.ownerOnly(ctx, id, userID)
	}
	if err != nil {
		return Workspace{}, unavailable(err)
	}
	ws.CreatedAt = ws.CreatedAt.UTC()
	return ws, nil
}

func (s *postgresStore) Delete(ctx context.Context, id, userID string) error {
	if s.pool == nil {
		return ErrUnavailable
	}
	ctx, cancel := context.WithTimeout(ctx, queryTimeout)
	defer cancel()
	// Members and WebSocket tickets cascade via their foreign keys.
	tag, err := s.pool.Exec(ctx, `DELETE FROM workspaces WHERE id = $1 AND owner_id = $2`, id, userID)
	if err != nil {
		return unavailable(err)
	}
	if tag.RowsAffected() == 0 {
		return s.ownerOnly(ctx, id, userID)
	}
	return nil
}
