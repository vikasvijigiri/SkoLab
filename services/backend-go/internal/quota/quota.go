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
	hourSeconds           = 3600
	daySeconds            = 86400
	maxLocalEntries       = 10_000
	localCapacityExceeded = int(^uint(0) >> 1)
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
	mu        sync.Mutex
	nextSweep time.Time
	data      map[string]struct {
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
	if !now.Before(l.nextSweep) || len(l.data) >= maxLocalEntries {
		for bucket, entry := range l.data {
			if !entry.exp.After(now) {
				delete(l.data, bucket)
			}
		}
		l.nextSweep = now.Add(time.Minute)
	}
	entry, ok := l.data[key]
	if !ok && len(l.data) >= maxLocalEntries {
		return localCapacityExceeded
	}
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

func (l *localFallback) refund(key string, cost int) {
	l.mu.Lock()
	defer l.mu.Unlock()
	if entry, ok := l.data[key]; ok {
		entry.count = max(entry.count-cost, 0)
		l.data[key] = entry
	}
}

// Consume charges cost units to uid, checked against both the hourly and
// daily budget. Returns (allowed, remaining-hourly, remaining-daily, err).
// err is only a real infra failure; an exhausted budget is *Exceeded, not
// err — callers should errors.As it.
func Consume(ctx context.Context, pool *pgxpool.Pool, uid string, cost int) (int, int, error) {
	_, hourly, daily, err := Charge(ctx, pool, uid, cost)
	return hourly, daily, err
}

// Receipt records what one successful Charge spent, so a request that never
// got the service it paid for (backend down, busy or failing) can be
// refunded. The zero Receipt refunds nothing.
type Receipt struct {
	cost    int
	entries []charged
}

type charged struct {
	key   string
	local bool // charged to the in-process fallback, not Postgres
}

// Charge is Consume plus the Receipt needed to undo it.
func Charge(ctx context.Context, pool *pgxpool.Pool, uid string, cost int) (Receipt, int, int, error) {
	limits := ReadLimits()
	if !limits.Enabled {
		return Receipt{}, limits.Hourly, limits.Daily, nil
	}
	now := time.Now()
	receipt := Receipt{cost: cost}

	hBucket, hEnd := windowBucket(now, hourSeconds)
	hKey := fmt.Sprintf("q:%s:h:%d", uid, hBucket)
	usedH, local, err := incr(ctx, pool, hKey, cost, hEnd, now)
	if err != nil {
		return Receipt{}, 0, 0, err
	}
	receipt.entries = append(receipt.entries, charged{hKey, local})
	if usedH > limits.Hourly {
		return Receipt{}, 0, 0, &Exceeded{"hourly", limits.Hourly, time.Until(hEnd)}
	}

	dBucket, dEnd := windowBucket(now, daySeconds)
	dKey := fmt.Sprintf("q:%s:d:%d", uid, dBucket)
	usedD, local, err := incr(ctx, pool, dKey, cost, dEnd, now)
	if err != nil {
		return Receipt{}, 0, 0, err
	}
	receipt.entries = append(receipt.entries, charged{dKey, local})
	if usedD > limits.Daily {
		return Receipt{}, 0, 0, &Exceeded{"daily", limits.Daily, time.Until(dEnd)}
	}

	return receipt, limits.Hourly - usedH, limits.Daily - usedD, nil
}

// Refund gives back what r charged. Best effort: a failed refund only costs
// the user budget, so it is logged, never surfaced.
func (r Receipt) Refund(ctx context.Context, pool *pgxpool.Pool) {
	for _, e := range r.entries {
		if e.local || pool == nil {
			local.refund(e.key, r.cost)
			continue
		}
		ctx, cancel := context.WithTimeout(ctx, 2*time.Second)
		_, err := pool.Exec(ctx, `UPDATE usage_counters SET count = GREATEST(count - $2, 0) WHERE bucket_key = $1`, e.key, r.cost)
		cancel()
		if err != nil {
			slog.Warn("quota: refund failed", "err", err)
		}
	}
}

// incr reports the new count and whether the local fallback tier took it.
func incr(ctx context.Context, pool *pgxpool.Pool, key string, cost int, windowEnd, now time.Time) (int, bool, error) {
	if pool != nil {
		ctx, cancel := context.WithTimeout(ctx, 2*time.Second)
		defer cancel()
		var count int
		err := pool.QueryRow(ctx, upsertSQL, key, cost, windowEnd.Add(60*time.Second)).Scan(&count)
		if err == nil {
			return count, false, nil
		}
		if shared.Required() {
			return 0, false, ErrUnavailable
		}
		slog.Warn("quota: postgres unavailable — falling back to local memory", "err", err)
	}
	if shared.Required() {
		return 0, false, ErrUnavailable
	}
	count := local.incr(key, cost, windowEnd, now)
	if count == localCapacityExceeded {
		return 0, false, ErrUnavailable
	}
	return count, true, nil
}

// Sweep removes a bounded batch, keeping cleanup transactions short. Run once
// per minute from the gateway, independently of whether quotas are consumed.
func Sweep(ctx context.Context, pool *pgxpool.Pool) error {
	if pool == nil {
		return nil
	}
	ctx, cancel := context.WithTimeout(ctx, 2*time.Second)
	defer cancel()
	_, err := pool.Exec(ctx, `DELETE FROM usage_counters WHERE bucket_key IN
		(SELECT bucket_key FROM usage_counters WHERE expires_at <= NOW() LIMIT 1000)`)
	return err
}

// AsExceeded is a small errors.As convenience for HTTP handlers.
func AsExceeded(err error) (*Exceeded, bool) {
	var e *Exceeded
	ok := errors.As(err, &e)
	return e, ok
}
