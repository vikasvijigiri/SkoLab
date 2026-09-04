package cache

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"log/slog"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/skolab/backend-go/internal/db"
)

// pgcache.go is a parity port of the L2 Postgres tier of Python's
// services/backend/app/db/pg_cache.py (PgBackedCache). Nearly every author/feed
// endpoint reads or writes the cache_entries table, so the Go gateway needs the
// exact same on-disk shape to migrate any of them incrementally.
//
// Shape kept identical to Python:
//   - namespaced key:   "<name>::<key>"
//   - value envelope:   {"v": <value>}  (JSON, stored in cache_entries.data)
//   - TTL:              expires_at = now + ttl; a row is a miss once expired
//   - upsert:           INSERT ... ON CONFLICT (cache_key) DO UPDATE
//
// TODO: Redis L2. Python's PgBackedCache prefers Redis when REDIS_URL is set.
// REDIS_URL is unset for both services in this deploy (render.yaml), so only the
// Postgres tier is ported. If Redis is ever introduced, the writers below must
// publish to it too or the two services will diverge on any Redis-warmed key.

// pgKey reproduces PgBackedCache._prefixed: "<name>::<key>".
func pgKey(name, key string) string {
	return name + "::" + key
}

// envelope is the {"v": ...} wrapper Python stores in cache_entries.data.
type envelope struct {
	V json.RawMessage `json:"v"`
}

// wrapEnvelope marshals value into the {"v": <value>} envelope bytes.
func wrapEnvelope(value any) ([]byte, error) {
	return json.Marshal(map[string]any{"v": value})
}

// unwrapEnvelope returns the raw JSON of the inner "v" field. A miss (no "v",
// or "v" is JSON null) is reported as ok=false so callers treat it like an
// absent row — matching Python returning None.
func unwrapEnvelope(data []byte) (json.RawMessage, bool) {
	var env envelope
	if err := json.Unmarshal(data, &env); err != nil {
		return nil, false
	}
	if len(env.V) == 0 || string(env.V) == "null" {
		return nil, false
	}
	return env.V, true
}

// PgGet reads cache_entries for "<name>::<key>", returning the inner value's raw
// JSON. Miss / expiry / no DB / any error → (nil, false), mirroring Python
// swallowing every cache_entries error and returning None.
func PgGet(ctx context.Context, name, key string) ([]byte, bool) {
	if db.Pool == nil {
		return nil, false
	}
	var data []byte
	err := db.Pool.QueryRow(ctx,
		`SELECT data FROM cache_entries
		 WHERE cache_key = $1 AND expires_at > (now() at time zone 'utc')`,
		pgKey(name, key),
	).Scan(&data)
	if err != nil {
		if !errors.Is(err, pgx.ErrNoRows) {
			slog.Warn("pgcache: get failed", "name", name, "key", key, "err", err)
		}
		return nil, false
	}
	v, ok := unwrapEnvelope(data)
	if !ok {
		return nil, false
	}
	return v, true
}

// PgSet upserts the {"v": value} envelope for "<name>::<key>" with the given TTL.
// SQL shape matches pg_cache.py's pg_insert(...).on_conflict_do_update. A nil
// pool is a silent no-op.
func PgSet(ctx context.Context, name, key string, value any, ttl time.Duration) error {
	if db.Pool == nil {
		return nil
	}
	payload, err := wrapEnvelope(value)
	if err != nil {
		return fmt.Errorf("pgcache: marshal value for %s::%s: %w", name, key, err)
	}
	// data passed as text + explicit ::json cast (cache_entries.data is JSON,
	// not JSONB, and pgx would otherwise send []byte as bytea). TTL seconds go
	// in as float8 for make_interval. last_synced / expires_at are stored as
	// naive UTC to match Python's datetime.now(utc).replace(tzinfo=None).
	_, err = db.Pool.Exec(ctx,
		`INSERT INTO cache_entries (cache_key, data, last_synced, expires_at)
		 VALUES ($1, $2::json,
		         (now() at time zone 'utc'),
		         (now() at time zone 'utc') + make_interval(secs => $3))
		 ON CONFLICT (cache_key) DO UPDATE SET
		         data        = EXCLUDED.data,
		         last_synced = EXCLUDED.last_synced,
		         expires_at  = EXCLUDED.expires_at`,
		pgKey(name, key), string(payload), ttl.Seconds(),
	)
	if err != nil {
		slog.Warn("pgcache: set failed", "name", name, "key", key, "err", err)
		return err
	}
	return nil
}

// PgDelete removes a single "<name>::<key>" row. Nil pool → no-op.
func PgDelete(ctx context.Context, name, key string) error {
	if db.Pool == nil {
		return nil
	}
	_, err := db.Pool.Exec(ctx,
		`DELETE FROM cache_entries WHERE cache_key = $1`, pgKey(name, key))
	if err != nil {
		slog.Warn("pgcache: delete failed", "name", name, "key", key, "err", err)
		return err
	}
	return nil
}

// PgClear removes every row in the "<name>::" namespace — Python's
// PgBackedCache.clear (DELETE ... WHERE cache_key LIKE '<name>::%'). Nil pool → no-op.
func PgClear(ctx context.Context, name string) error {
	if db.Pool == nil {
		return nil
	}
	_, err := db.Pool.Exec(ctx,
		`DELETE FROM cache_entries WHERE cache_key LIKE $1`, name+"::%")
	if err != nil {
		slog.Warn("pgcache: clear failed", "name", name, "err", err)
		return err
	}
	return nil
}
