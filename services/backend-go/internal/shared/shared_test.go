package shared

import (
	"context"
	"testing"
	"time"

	"github.com/alicebob/miniredis/v2"
	"github.com/redis/go-redis/v9"
)

func newStore(t *testing.T) (*Store, *miniredis.Miniredis) {
	t.Helper()
	server := miniredis.RunT(t)
	server.SetTime(time.Unix(1_800_000_000, 0))
	return New(redis.NewClient(&redis.Options{Addr: server.Addr()})), server
}

func TestBucketAllowsTheBurstThenRefillsAtTheRate(t *testing.T) {
	store, server := newStore(t)
	ctx := context.Background()
	allowed := 0
	for i := 0; i < 10; i++ {
		if ok, err := store.Allow(ctx, "user:ada", time.Second, 3); err != nil {
			t.Fatal(err)
		} else if ok {
			allowed++
		}
	}
	if allowed != 3 {
		t.Fatalf("burst of 3 allowed %d", allowed)
	}
	server.SetTime(time.Unix(1_800_000_001, 0)) // one interval later: one token
	if ok, _ := store.Allow(ctx, "user:ada", time.Second, 3); !ok {
		t.Fatal("a token should have refilled after one interval")
	}
	if ok, _ := store.Allow(ctx, "user:ada", time.Second, 3); ok {
		t.Fatal("only one token refills per interval")
	}
	if ok, _ := store.Allow(ctx, "user:grace", time.Second, 3); !ok {
		t.Fatal("buckets are per key")
	}
}

func TestAvailableNeverSpends(t *testing.T) {
	store, _ := newStore(t)
	ctx := context.Background()
	for i := 0; i < 5; i++ {
		if ok, _ := store.Available(ctx, "ip:1.2.3.4", 15*time.Second, 2); !ok {
			t.Fatal("looking must not spend tokens")
		}
	}
	store.Allow(ctx, "ip:1.2.3.4", 15*time.Second, 2)
	store.Allow(ctx, "ip:1.2.3.4", 15*time.Second, 2)
	if ok, _ := store.Available(ctx, "ip:1.2.3.4", 15*time.Second, 2); ok {
		t.Fatal("an empty bucket must report unavailable")
	}
}

func TestLockIsExclusiveAndReleasedOnlyByItsHolder(t *testing.T) {
	store, server := newStore(t)
	ctx := context.Background()
	release, ok, err := store.Lock(ctx, "compile:ada", time.Minute)
	if err != nil || !ok {
		t.Fatalf("first lock: %v %v", ok, err)
	}
	if _, ok, _ := store.Lock(ctx, "compile:ada", time.Minute); ok {
		t.Fatal("a held lock must not be taken twice")
	}
	release()
	release2, ok, _ := store.Lock(ctx, "compile:ada", time.Minute)
	if !ok {
		t.Fatal("a released lock must be free")
	}
	// The lock expires, someone else takes it; the stale release is a no-op.
	server.FastForward(2 * time.Minute)
	_, ok, _ = store.Lock(ctx, "compile:ada", time.Minute)
	if !ok {
		t.Fatal("an expired lock must be free")
	}
	release2()
	if _, ok, _ := store.Lock(ctx, "compile:ada", time.Minute); ok {
		t.Fatal("a stale holder must not release someone else's lock")
	}
}

func TestConnectWithoutURLIsNil(t *testing.T) {
	if Connect(context.Background(), "") != nil {
		t.Fatal("no REDIS_URL means in-process limits")
	}
	if Connect(context.Background(), "redis://127.0.0.1:1/0") != nil {
		t.Fatal("an unreachable Redis means in-process limits")
	}
}
