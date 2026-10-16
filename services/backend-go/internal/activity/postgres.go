package activity

import (
	"context"
	"errors"
	"strconv"
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

func unavailable(err error) error {
	return errors.Join(ErrUnavailable, err)
}

// visible is every event $1 may see since $2: in workspaces they own (all of
// it) or are an active member of (from when they joined), plus events about
// them wherever they happened. Their own actions are left out.
const visible = `
	WITH mine AS (
		SELECT w.id, w.created_at AS since FROM workspaces w WHERE w.owner_id = $1
		UNION ALL
		SELECT m.workspace_id, m.created_at
		FROM workspace_members m JOIN workspaces w ON w.id = m.workspace_id
		WHERE m.user_id = $1 AND m.status = 'active' AND w.owner_id <> $1
	),
	visible AS (
		SELECT a.* FROM workspace_activity a JOIN mine ON mine.id = a.workspace_id
		WHERE a.occurred_at >= mine.since AND a.occurred_at > $2
		UNION
		SELECT a.* FROM workspace_activity a WHERE a.subject_id = $1 AND a.occurred_at > $2
	)`

const page = visible + `
	SELECT v.id, v.kind, v.workspace_id, w.title, v.actor_id, au.display_name,
	       v.subject_id, su.display_name, v.role, v.count, v.occurred_at
	FROM visible v
	JOIN workspaces w ON w.id = v.workspace_id
	LEFT JOIN users au ON au.id = v.actor_id
	LEFT JOIN users su ON su.id = v.subject_id
	WHERE v.actor_id IS DISTINCT FROM $1
	  AND ($3::timestamp IS NULL OR (v.occurred_at, v.id) < ($3::timestamp, $4::bigint))
	ORDER BY v.occurred_at DESC, v.id DESC
	LIMIT $5`

const unread = visible + `
	SELECT count(*) FROM (
		SELECT 1 FROM visible v
		WHERE v.actor_id IS DISTINCT FROM $1 AND ($3::timestamp IS NULL OR v.occurred_at > $3::timestamp)
		LIMIT $4
	) unread`

func (s *postgresStore) Feed(ctx context.Context, userID string, after *Cursor, limit int) (Page, error) {
	if s.pool == nil {
		return Page{}, ErrUnavailable
	}
	ctx, cancel := context.WithTimeout(ctx, queryTimeout)
	defer cancel()
	// A reader with no users row yet (profile not synced) has no workspaces:
	// an empty feed, not an error.
	var seen *time.Time
	err := s.pool.QueryRow(ctx, `SELECT activity_seen_at FROM users WHERE id = $1`, userID).Scan(&seen)
	if errors.Is(err, pgx.ErrNoRows) {
		return Page{}, nil
	}
	if err != nil {
		return Page{}, unavailable(err)
	}
	if seen != nil {
		utc := seen.UTC()
		seen = &utc
	}
	since := time.Now().UTC().Add(-Retention)

	var afterAt *time.Time
	var afterID int64
	if after != nil {
		at := after.At.UTC()
		afterAt, afterID = &at, after.ID
	}
	// One extra row tells whether another page exists.
	rows, err := s.pool.Query(ctx, page, userID, since, afterAt, afterID, limit+1)
	if err != nil {
		return Page{}, unavailable(err)
	}
	defer rows.Close()
	result := Page{Items: []Item{}, SeenAt: seen}
	for rows.Next() {
		var (
			id                            int64
			item                          Item
			actorID, actorName, subjectID *string
			subjectName, role             *string
		)
		if err := rows.Scan(&id, &item.Kind, &item.Workspace.ID, &item.Workspace.Title, &actorID, &actorName,
			&subjectID, &subjectName, &role, &item.Count, &item.OccurredAt); err != nil {
			return Page{}, unavailable(err)
		}
		item.ID = strconv.FormatInt(id, 10)
		item.OccurredAt = item.OccurredAt.UTC()
		item.Actor = person(actorID, actorName)
		item.Subject = person(subjectID, subjectName)
		item.Role = role
		item.Unread = seen == nil || item.OccurredAt.After(*seen)
		result.Items = append(result.Items, item)
	}
	if err := rows.Err(); err != nil {
		return Page{}, unavailable(err)
	}
	if len(result.Items) > limit {
		result.Items = result.Items[:limit]
		last := result.Items[limit-1]
		id, _ := strconv.ParseInt(last.ID, 10, 64)
		result.Next = &Cursor{At: last.OccurredAt, ID: id}
	}
	if err := s.pool.QueryRow(ctx, unread, userID, since, seen, unreadCap).Scan(&result.Unread); err != nil {
		return Page{}, unavailable(err)
	}
	return result, nil
}

func person(id, name *string) *Person {
	if id == nil {
		return nil
	}
	p := &Person{ID: *id}
	if name != nil {
		p.DisplayName = *name
	}
	return p
}

// MarkSeen moves the reader's catch-up point to at, never backwards (two
// tabs catching up out of order). A reader with no users row has nothing to
// mark; that is not an error.
func (s *postgresStore) MarkSeen(ctx context.Context, userID string, at time.Time) (*time.Time, error) {
	if s.pool == nil {
		return nil, ErrUnavailable
	}
	ctx, cancel := context.WithTimeout(ctx, queryTimeout)
	defer cancel()
	var seen time.Time
	err := s.pool.QueryRow(ctx, `
		UPDATE users SET activity_seen_at = GREATEST(COALESCE(activity_seen_at, $2), $2)
		WHERE id = $1
		RETURNING activity_seen_at`, userID, at.UTC().Truncate(time.Microsecond)).Scan(&seen)
	if errors.Is(err, pgx.ErrNoRows) {
		return nil, nil
	}
	if err != nil {
		return nil, unavailable(err)
	}
	seen = seen.UTC()
	return &seen, nil
}
