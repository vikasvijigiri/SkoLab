// Package middleware provides Gin middleware for cross-cutting concerns.
package middleware

import (
	"net/http"
	"strings"
	"sync"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/skolab/backend-go/internal/apierror"
	"github.com/skolab/backend-go/internal/security"
	"golang.org/x/time/rate"
)

type visitor struct {
	limiter  *rate.Limiter
	lastSeen time.Time
}

// RateLimiter holds per-IP token-bucket limiters and evicts stale entries.
type RateLimiter struct {
	mu       sync.Mutex
	visitors map[string]*visitor
	r        rate.Limit
	b        int
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

// clientIP extracts the real client IP, respecting common reverse-proxy
// headers.
//
// CF-Connecting-IP is checked first and is the only one of these that's
// actually safe to trust for rate-limiting: Render's public edge is
// Cloudflare (confirmed live -- every response carries CF-RAY and
// Server: cloudflare), and Cloudflare's edge sets this header from the
// real TCP connection, overwriting any client-supplied value of the same
// name -- an end user cannot spoof it. X-Real-IP / X-Forwarded-For, by
// contrast, are ordinary headers any client can set to an arbitrary value
// on the original request; trusting them without knowing whether the
// specific hop in front of this service actually overwrites (rather than
// merely appends to) a client-supplied value means a request can rotate a
// fake X-Forwarded-For on every call and land in a fresh rate-limit bucket
// each time, defeating the limiter entirely. Kept as a fallback, in that
// order, only for an environment with no Cloudflare in front (local dev,
// docker-compose) where CF-Connecting-IP is never present.
func clientIP(c *gin.Context) string {
	if ip := c.GetHeader("CF-Connecting-IP"); ip != "" {
		return strings.TrimSpace(ip)
	}
	if ip := c.GetHeader("X-Real-IP"); ip != "" {
		return strings.TrimSpace(ip)
	}
	if fwd := c.GetHeader("X-Forwarded-For"); fwd != "" {
		return strings.TrimSpace(strings.SplitN(fwd, ",", 2)[0])
	}
	return c.RemoteIP()
}

// Limit returns a Gin handler that enforces the configured rate limit per IP.
func (rl *RateLimiter) Limit() gin.HandlerFunc {
	return func(c *gin.Context) {
		ip := clientIP(c)
		if !rl.getLimiter(ip).Allow() {
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
// shared store (Redis) would be needed for a global limit.
func (rl *RateLimiter) PerUser() gin.HandlerFunc {
	return func(c *gin.Context) {
		uid := c.GetString("user_id")
		if uid != "" && !rl.getLimiter(uid).Allow() {
			security.Record(c, security.Event{Name: security.RateLimitUser, Outcome: security.Throttled})
			c.Header("Retry-After", "1")
			apierror.Abort(c, http.StatusTooManyRequests, "rate_limit_exceeded", "Too many requests; slow down")
			return
		}
		c.Next()
	}
}
