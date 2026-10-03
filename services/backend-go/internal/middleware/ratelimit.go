// Package middleware provides Gin middleware for cross-cutting concerns.
package middleware

import (
	"context"
	"fmt"
	"log/slog"
	"net/http"
	"sync"
	"sync/atomic"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/skolab/backend-go/internal/apierror"
	"github.com/skolab/backend-go/internal/security"
	"github.com/skolab/backend-go/internal/shared"
	"golang.org/x/time/rate"
)

type visitor struct {
	limiter  *rate.Limiter
	lastSeen time.Time
}

// RateLimiter holds per-key token buckets: in this process, or in Redis
// when shared (see Share) so every instance enforces one limit.
type RateLimiter struct {
	mu       sync.Mutex
	visitors map[string]*visitor
	r        rate.Limit
	b        int

	shared   *shared.Store
	name     string
	lastWarn atomic.Int64
}

// Share keeps this limiter's buckets in store under name (nil store: stay
// in process). On a Redis error a check falls back to the local bucket.
func (rl *RateLimiter) Share(store *shared.Store, name string) *RateLimiter {
	rl.shared, rl.name = store, name
	return rl
}

func (rl *RateLimiter) every() time.Duration {
	return time.Duration(float64(time.Second) / float64(rl.r))
}

// warnFallback logs a Redis failure at most once a minute per limiter.
func (rl *RateLimiter) warnFallback(err error) {
	now := time.Now().Unix()
	if last := rl.lastWarn.Load(); now-last >= 60 && rl.lastWarn.CompareAndSwap(last, now) {
		slog.Warn("shared limits unavailable; using in-process limits", "limiter", rl.name, "err", err)
	}
}

// Allow spends a token from key's bucket and reports whether one was there.
func (rl *RateLimiter) Allow(ctx context.Context, key string) bool {
	ok, _ := rl.Check(ctx, key)
	return ok
}

// Check distinguishes an exhausted bucket from an unavailable shared store.
func (rl *RateLimiter) Check(ctx context.Context, key string) (bool, error) {
	if shared.Required() && rl.shared == nil {
		return false, fmt.Errorf("shared limits unavailable")
	}
	if rl.shared != nil {
		ok, err := rl.shared.Allow(ctx, rl.name+":"+key, rl.every(), rl.b)
		if err == nil {
			return ok, nil
		}
		if shared.Required() {
			return false, err
		}
		rl.warnFallback(err)
	}
	return rl.getLimiter(key).Allow(), nil
}

// Available reports whether key's bucket has a token, without spending it.
func (rl *RateLimiter) Available(ctx context.Context, key string) bool {
	if shared.Required() && rl.shared == nil {
		return false
	}
	if rl.shared != nil {
		ok, err := rl.shared.Available(ctx, rl.name+":"+key, rl.every(), rl.b)
		if err == nil {
			return ok
		}
		if shared.Required() {
			return false
		}
		rl.warnFallback(err)
	}
	return rl.getLimiter(key).Tokens() >= 1
}

// NewRateLimiter creates a limiter that allows r events/second and bursts of b.
// A background goroutine evicts inactive visitors every minute.
func NewRateLimiter(r rate.Limit, b int) *RateLimiter {
	rl := &RateLimiter{
		visitors: make(map[string]*visitor),
		r:        r,
		b:        b,
	}
	go rl.cleanupLoop()
	return rl
}

// Limiter returns the token bucket for key (an IP, a user ID...), creating
// it on first use. Idle buckets are evicted after three minutes.
func (rl *RateLimiter) Limiter(key string) *rate.Limiter {
	return rl.getLimiter(key)
}

func (rl *RateLimiter) getLimiter(ip string) *rate.Limiter {
	rl.mu.Lock()
	defer rl.mu.Unlock()
	v, ok := rl.visitors[ip]
	if !ok {
		lim := rate.NewLimiter(rl.r, rl.b)
		rl.visitors[ip] = &visitor{limiter: lim, lastSeen: time.Now()}
		return lim
	}
	v.lastSeen = time.Now()
	return v.limiter
}

func (rl *RateLimiter) cleanupLoop() {
	ticker := time.NewTicker(time.Minute)
	defer ticker.Stop()
	for range ticker.C {
		cutoff := time.Now().Add(-3 * time.Minute)
		rl.mu.Lock()
		for ip, v := range rl.visitors {
			if v.lastSeen.Before(cutoff) {
				delete(rl.visitors, ip)
			}
		}
		rl.mu.Unlock()
	}
}

// clientIP uses the audit layer's edge-IP policy. Arbitrary X-Forwarded-For
// and X-Real-IP values never create fresh rate-limit buckets.
func clientIP(c *gin.Context) string {
	return security.ClientIP(c)
}

// Limit returns a Gin handler that enforces the configured rate limit per IP.
func (rl *RateLimiter) Limit() gin.HandlerFunc {
	return func(c *gin.Context) {
		// Health probes must remain available during a shared-state outage.
		if c.Request.URL.Path == "/gateway-health" || c.Request.URL.Path == "/readyz" {
			c.Next()
			return
		}
		ok, err := rl.Check(c.Request.Context(), clientIP(c))
		if err != nil {
			c.Header("Retry-After", "1")
			apierror.Abort(c, http.StatusServiceUnavailable, "shared_state_unavailable", "Service temporarily unavailable")
			return
		}
		if !ok {
			c.Header("Retry-After", "1")
			apierror.Abort(c, http.StatusTooManyRequests, "rate_limit_exceeded", "Too many requests; slow down")
			return
		}
		c.Next()
	}
}

// PerUser enforces the limit per authenticated user. Mount it after
// auth.VerifyUser, so one account cannot exhaust the service from many IPs
// (or many accounts from one IP -- that is the per-IP limit's job). The
// buckets live in this process: exact with one instance per service, as on
// Render today; with several instances each enforces its own share, and a
// shared store (Share, with SHARED_STATE_REDIS_URL) makes it one global limit.
func (rl *RateLimiter) PerUser() gin.HandlerFunc {
	return func(c *gin.Context) {
		uid := c.GetString("user_id")
		if uid == "" {
			c.Next()
			return
		}
		ok, err := rl.Check(c.Request.Context(), uid)
		if err != nil {
			c.Header("Retry-After", "1")
			apierror.Abort(c, http.StatusServiceUnavailable, "shared_state_unavailable", "Service temporarily unavailable")
			return
		}
		if !ok {
			security.Record(c, security.Event{Name: security.RateLimitUser, Outcome: security.Throttled})
			c.Header("Retry-After", "1")
			apierror.Abort(c, http.StatusTooManyRequests, "rate_limit_exceeded", "Too many requests; slow down")
			return
		}
		c.Next()
	}
}
