// Package quota is a Go port of the Postgres tier of Python's
// app/core/quota.py — same table (usage_counters), same bucket-key format
// ("q:<uid>:h:<bucket>" / "q:<uid>:d:<bucket>"), same fixed hourly/daily
// windows, so a request charged from Go and one charged from Python draw
// down the exact same per-user ledger instead of keeping two independent
// budgets for one account.
//
// TODO: Redis L1. Python's quota.py prefers Redis (REDIS_URL) with Postgres
// as the fallback; this port only has the Postgres tier, mirroring
// internal/cache/pgcache.go's own TODO for the same reason (REDIS_URL is
// unset in this deploy — render.yaml). If Redis is ever wired up here, this
// must read/write it too or the two services' quota views can diverge.
package quota

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"os"
	"strconv"
	"sync"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/skolab/backend-go/internal/shared"
)

const (
	hourSeconds = 3600
	daySeconds  = 86400
)

var ErrUnavailable = errors.New("shared quota accounting unavailable")

var upsertSQL = `
	INSERT INTO usage_counters (bucket_key, count, expires_at)
	VALUES ($1, $2, $3)
	ON CONFLICT (bucket_key) DO UPDATE
	SET count = usage_counters.count + EXCLUDED.count
	RETURNING count`

// Exceeded reports which window was exhausted and how long until it resets.
type Exceeded struct {
	Window     string
	Limit      int
	RetryAfter time.Duration
}

func (e *Exceeded) Error() string {
	return fmt.Sprintf("%s quota of %d units exceeded", e.Window, e.Limit)
}

// Limits reads the shared env vars — same names as app/core/quota.py's
// limits() — so both services are tuned from one place.
type Limits struct {
	Enabled bool
	Hourly  int
	Daily   int
}

func ReadLimits() Limits {
	enabled := true
	if v := os.Getenv("USER_QUOTA_ENABLED"); v != "" {
		enabled = v == "true" || v == "1"
	}
	return Limits{
		Enabled: enabled,
		Hourly:  envInt("USER_QUOTA_HOURLY_UNITS", 150),
		Daily:   envInt("USER_QUOTA_DAILY_UNITS", 600),
	}
}

func envInt(key string, def int) int {
	if v := os.Getenv(key); v != "" {
		if n, err := strconv.Atoi(v); err == nil {
			return n
		}
	}
	return def
}

func windowBucket(now time.Time, size int64) (int64, time.Time) {
	sec := now.Unix()
	bucket := sec / size
	end := time.Unix((bucket+1)*size, 0)
	return bucket, end
}

// localFallback is the last-resort, per-process tier used only when Postgres
// itself is unreachable — availability over strict accounting, matching
// Python's identical rationale in app/core/quota.py.
type localFallback struct {
	mu   sync.Mutex
	data map[string]struct {
		count int
		exp   time.Time
	}
}

var local = &localFallback{data: map[string]struct {
	count int
	exp   time.Time
}{}}

func (l *localFallback) incr(key string, cost int, windowEnd, now time.Time) int {
	l.mu.Lock()
	defer l.mu.Unlock()
	entry, ok := l.data[key]
	if !ok || entry.exp.Before(now) {
		entry = struct {
			count int
			exp   time.Time
		}{0, windowEnd}
	}
	entry.count += cost
	l.data[key] = entry
	return entry.count
}

// Consume charges cost units to uid, checked against both the hourly and
// daily budget. Returns (allowed, remaining-hourly, remaining-daily, err).
// err is only a real infra failure; an exhausted budget is *Exceeded, not
// err — callers should errors.As it.
func Consume(ctx context.Context, pool *pgxpool.Pool, uid string, cost int) (int, int, error) {
	limits := ReadLimits()
	if !limits.Enabled {
		return limits.Hourly, limits.Daily, nil
	}
	now := time.Now()

	hBucket, hEnd := windowBucket(now, hourSeconds)
	usedH, err := incr(ctx, pool, fmt.Sprintf("q:%s:h:%d", uid, hBucket), cost, hEnd, now)
	if err != nil {
		return 0, 0, err
	}
	if usedH > limits.Hourly {
		return 0, 0, &Exceeded{"hourly", limits.Hourly, time.Until(hEnd)}
	}

	dBucket, dEnd := windowBucket(now, daySeconds)
	usedD, err := incr(ctx, pool, fmt.Sprintf("q:%s:d:%d", uid, dBucket), cost, dEnd, now)
	if err != nil {
		return 0, 0, err
	}
	if usedD > limits.Daily {
		return 0, 0, &Exceeded{"daily", limits.Daily, time.Until(dEnd)}
	}

	return limits.Hourly - usedH, limits.Daily - usedD, nil
}

func incr(ctx context.Context, pool *pgxpool.Pool, key string, cost int, windowEnd, now time.Time) (int, error) {
	if pool != nil {
		ctx, cancel := context.WithTimeout(ctx, 2*time.Second)
		defer cancel()
		var count int
		err := pool.QueryRow(ctx, upsertSQL, key, cost, windowEnd.Add(60*time.Second)).Scan(&count)
		if err == nil {
			return count, nil
		}
		if shared.Required() {
			return 0, ErrUnavailable
		}
		slog.Warn("quota: postgres unavailable — falling back to local memory", "err", err)
	}
	if shared.Required() {
		return 0, ErrUnavailable
	}
	return local.incr(key, cost, windowEnd, now), nil
}

// AsExceeded is a small errors.As convenience for HTTP handlers.
func AsExceeded(err error) (*Exceeded, bool) {
	var e *Exceeded
	ok := errors.As(err, &e)
	return e, ok
}
