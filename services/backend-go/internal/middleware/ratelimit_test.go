package middleware

import (
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"github.com/gin-gonic/gin"
	"golang.org/x/time/rate"
)

func newRateLimitedRouter(rl *RateLimiter) *gin.Engine {
	gin.SetMode(gin.TestMode)
	r := gin.New()
	r.Use(rl.Limit())
	r.GET("/ping", func(c *gin.Context) { c.Status(http.StatusOK) })
	return r
}

func doGet(r *gin.Engine, remoteIP string) *httptest.ResponseRecorder {
	req := httptest.NewRequest(http.MethodGet, "/ping", nil)
	req.RemoteAddr = remoteIP + ":12345"
	w := httptest.NewRecorder()
	r.ServeHTTP(w, req)
	return w
}

func TestRateLimiter_AllowsWithinBurst(t *testing.T) {
	rl := NewRateLimiter(rate.Limit(1), 3)
	r := newRateLimitedRouter(rl)

	for i := 0; i < 3; i++ {
		w := doGet(r, "10.0.0.1")
		if w.Code != http.StatusOK {
			t.Fatalf("request %d: status = %d, want %d (within burst of 3)", i, w.Code, http.StatusOK)
		}
	}
}

func TestRateLimiter_BlocksBeyondBurst(t *testing.T) {
	rl := NewRateLimiter(rate.Limit(1), 3)
	r := newRateLimitedRouter(rl)

	for i := 0; i < 3; i++ {
		doGet(r, "10.0.0.2")
	}
	w := doGet(r, "10.0.0.2")
	if w.Code != http.StatusTooManyRequests {
		t.Errorf("4th request within the burst window: status = %d, want %d", w.Code, http.StatusTooManyRequests)
	}
}

func TestRateLimiter_IsolatedPerIP(t *testing.T) {
	rl := NewRateLimiter(rate.Limit(1), 1)
	r := newRateLimitedRouter(rl)

	// Exhaust IP #1's single-token burst.
	if w := doGet(r, "10.0.0.3"); w.Code != http.StatusOK {
		t.Fatalf("first request from IP #1: status = %d, want %d", w.Code, http.StatusOK)
	}
	if w := doGet(r, "10.0.0.3"); w.Code != http.StatusTooManyRequests {
		t.Fatalf("second immediate request from IP #1: status = %d, want %d", w.Code, http.StatusTooManyRequests)
	}
	// A different IP must not be affected by IP #1's exhausted bucket.
	if w := doGet(r, "10.0.0.4"); w.Code != http.StatusOK {
		t.Errorf("first request from IP #2: status = %d, want %d (separate bucket from IP #1)", w.Code, http.StatusOK)
	}
}

func TestRateLimiter_XForwardedForTakesPrecedenceOverRemoteAddr(t *testing.T) {
	rl := NewRateLimiter(rate.Limit(1), 1)
	gin.SetMode(gin.TestMode)
	r := gin.New()
	r.Use(rl.Limit())
	r.GET("/ping", func(c *gin.Context) { c.Status(http.StatusOK) })

	req1 := httptest.NewRequest(http.MethodGet, "/ping", nil)
	req1.RemoteAddr = "192.168.1.1:1111"
	req1.Header.Set("X-Forwarded-For", "203.0.113.5, 10.0.0.1")
	w1 := httptest.NewRecorder()
	r.ServeHTTP(w1, req1)
	if w1.Code != http.StatusOK {
		t.Fatalf("first request: status = %d, want %d", w1.Code, http.StatusOK)
	}

	// Same X-Forwarded-For client IP but a different RemoteAddr (simulating the
	// same client behind different proxy hops) should still hit the same bucket
	// and be rate-limited, since clientIP() must key off X-Forwarded-For.
	req2 := httptest.NewRequest(http.MethodGet, "/ping", nil)
	req2.RemoteAddr = "192.168.1.2:2222"
	req2.Header.Set("X-Forwarded-For", "203.0.113.5, 10.0.0.2")
	w2 := httptest.NewRecorder()
	r.ServeHTTP(w2, req2)
	if w2.Code != http.StatusTooManyRequests {
		t.Errorf("second request sharing X-Forwarded-For client IP: status = %d, want %d", w2.Code, http.StatusTooManyRequests)
	}
}

func TestRateLimiter_RefillsOverTime(t *testing.T) {
	rl := NewRateLimiter(rate.Limit(50), 1) // ~1 token per 20ms
	r := newRateLimitedRouter(rl)

	if w := doGet(r, "10.0.0.5"); w.Code != http.StatusOK {
		t.Fatalf("first request: status = %d, want %d", w.Code, http.StatusOK)
	}
	if w := doGet(r, "10.0.0.5"); w.Code != http.StatusTooManyRequests {
		t.Fatalf("immediate second request: status = %d, want %d", w.Code, http.StatusTooManyRequests)
	}

	time.Sleep(40 * time.Millisecond)

	if w := doGet(r, "10.0.0.5"); w.Code != http.StatusOK {
		t.Errorf("request after refill window: status = %d, want %d", w.Code, http.StatusOK)
	}
}
