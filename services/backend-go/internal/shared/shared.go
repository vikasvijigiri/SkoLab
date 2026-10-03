// Package shared keeps rate-limit buckets and per-user locks in Redis, so
// every gateway instance enforces the same limits. It is optional: with no
// SHARED_STATE_REDIS_URL (one instance, as on Render's free tier today) callers keep
// their in-process state. SHARED_STATE_REQUIRED=true requires Redis and
// prevents local fallbacks in a scaled deployment.
package shared

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"fmt"
	"log/slog"
	"os"
	"strings"
	"time"

	"github.com/redis/go-redis/v9"
)

// Store is a Redis-backed limit store. A nil *Store means "not configured".
type Store struct {
	client *redis.Client
}

// Required enables fail-closed shared state for a multi-instance deployment.
func Required() bool { return strings.EqualFold(os.Getenv("SHARED_STATE_REQUIRED"), "true") }

func (s *Store) Ping(ctx context.Context) error { return s.client.Ping(ctx).Err() }
func (s *Store) Close() error                   { return s.client.Close() }

// ValidateConfiguration refuses unsafe scaled deployments at startup.
func ValidateConfiguration(store *Store) error {
	if Required() && store == nil {
		return fmt.Errorf("SHARED_STATE_REQUIRED=true requires reachable SHARED_STATE_REDIS_URL")
	}
	return nil
}

// Connect returns a Store for url, or nil when url is empty or Redis cannot
// be reached (the caller then uses in-process state).
func Connect(ctx context.Context, url string) *Store {
	if url == "" {
		return nil
	}
	opt, err := redis.ParseURL(url)
	if err != nil {
		// Never log the URL: it can carry a password.
		slog.Warn("shared limits: invalid REDIS_URL; using in-process limits", "err", err)
		return nil
	}
	// Bound outages instead of allowing Redis calls to queue indefinitely.
	opt.DialTimeout = time.Second
	opt.ReadTimeout = time.Second
	opt.WriteTimeout = time.Second
	opt.MaxRetries = 1
	opt.ContextTimeoutEnabled = true
	client := redis.NewClient(opt)
	pingCtx, cancel := context.WithTimeout(ctx, 3*time.Second)
	defer cancel()
	if err := client.Ping(pingCtx).Err(); err != nil {
		slog.Warn("shared limits: Redis unreachable; using in-process limits", "addr", opt.Addr, "err", err)
		_ = client.Close()
		return nil
	}
	slog.Info("shared limits: using Redis", "addr", opt.Addr)
	return &Store{client: client}
}

// New wraps an existing client (tests).
func New(client *redis.Client) *Store { return &Store{client: client} }

// GCRA (generic cell rate algorithm): the token bucket of
// golang.org/x/time/rate expressed as one timestamp per key, so a check is a
// single atomic script. Time comes from the Redis server, so instances with
// skewed clocks still agree. KEYS[1]: bucket. ARGV: interval between tokens
// (µs), burst, and 1 to spend a token or 0 to only look.
var gcra = redis.NewScript(`
local t = redis.call('TIME')
local now = tonumber(t[1]) * 1000000 + tonumber(t[2])
local interval = tonumber(ARGV[1])
local burst = tonumber(ARGV[2])
local tat = tonumber(redis.call('GET', KEYS[1]) or now)
if tat < now then tat = now end
local new_tat = tat + interval
if new_tat - now > interval * burst then
  return 0
end
if ARGV[3] == '1' then
  redis.call('SET', KEYS[1], new_tat, 'PX', math.ceil((new_tat - now) / 1000) + 1)
end
return 1
`)

func (s *Store) bucket(ctx context.Context, key string, every time.Duration, burst int, spend bool) (bool, error) {
	flag := "0"
	if spend {
		flag = "1"
	}
	n, err := gcra.Run(ctx, s.client, []string{"skolab:rl:" + key}, every.Microseconds(), burst, flag).Int()
	return n == 1, err
}

// Allow spends one token from key's bucket (one token per `every`, at most
// `burst` saved up) and reports whether one was available.
func (s *Store) Allow(ctx context.Context, key string, every time.Duration, burst int) (bool, error) {
	return s.bucket(ctx, key, every, burst, true)
}

// Available reports whether Allow would succeed, without spending a token.
func (s *Store) Available(ctx context.Context, key string, every time.Duration, burst int) (bool, error) {
	return s.bucket(ctx, key, every, burst, false)
}

// Deletes the lock only if it still holds this holder's token, so a lock
// that expired and was re-taken is never released by its former holder.
var unlock = redis.NewScript(`
if redis.call('GET', KEYS[1]) == ARGV[1] then
  return redis.call('DEL', KEYS[1])
end
return 0
`)

// Lock takes key for at most ttl. It returns a release function when the
// lock was taken, or ok=false when someone else holds it.
func (s *Store) Lock(ctx context.Context, key string, ttl time.Duration) (release func(), ok bool, err error) {
	raw := make([]byte, 16)
	if _, err := rand.Read(raw); err != nil {
		return nil, false, err
	}
	token, name := hex.EncodeToString(raw), "skolab:lock:"+key
	ok, err = s.client.SetNX(ctx, name, token, ttl).Result()
	if err != nil || !ok {
		return nil, ok, err
	}
	return func() {
		ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
		defer cancel()
		_ = unlock.Run(ctx, s.client, []string{name}, token).Err()
	}, true, nil
}
