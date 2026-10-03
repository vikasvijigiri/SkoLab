package middleware

import (
	"context"
	"testing"

	"github.com/alicebob/miniredis/v2"
	"github.com/redis/go-redis/v9"
	"github.com/skolab/backend-go/internal/shared"
	"golang.org/x/time/rate"
)

// Two limiters sharing one Redis behave like two gateway instances: the
// burst is spent once across both, not once per instance.
func TestSharedLimitSpansInstances(t *testing.T) {
	server := miniredis.RunT(t)
	store := shared.New(redis.NewClient(&redis.Options{Addr: server.Addr()}))
	a := NewRateLimiter(rate.Limit(1), 4).Share(store, "user")
	b := NewRateLimiter(rate.Limit(1), 4).Share(store, "user")
	ctx := context.Background()

	allowed := 0
	for i := 0; i < 4; i++ {
		for _, rl := range []*RateLimiter{a, b} {
			if rl.Allow(ctx, "ada") {
				allowed++
			}
		}
	}
	if allowed != 4 {
		t.Fatalf("a burst of 4 across two instances allowed %d", allowed)
	}
	if a.Available(ctx, "ada") || b.Available(ctx, "ada") {
		t.Fatal("both instances must see the spent bucket")
	}
}

// When Redis fails, each instance keeps limiting with its own buckets
// instead of letting everything through or refusing everything.
func TestRedisOutageFallsBackToInProcessLimits(t *testing.T) {
	server := miniredis.RunT(t)
	store := shared.New(redis.NewClient(&redis.Options{Addr: server.Addr(), MaxRetries: -1}))
	rl := NewRateLimiter(rate.Limit(1), 2).Share(store, "ip")
	server.Close()

	ctx := context.Background()
	got := []bool{rl.Allow(ctx, "1.2.3.4"), rl.Allow(ctx, "1.2.3.4"), rl.Allow(ctx, "1.2.3.4")}
	if !got[0] || !got[1] || got[2] {
		t.Fatalf("in-process fallback should allow the burst of 2, then refuse: %v", got)
	}
}
