package pubsub

import (
	"context"
	"testing"
	"time"

	"github.com/alicebob/miniredis/v2"
)

func TestNewRedisClientWithoutURLStaysInProcess(t *testing.T) {
	t.Setenv("SHARED_STATE_REDIS_URL", "")
	if c := NewRedisClient(); c != nil {
		t.Fatal("no URL must mean no client")
	}
}

func TestNewRedisClientRejectsInvalidURL(t *testing.T) {
	t.Setenv("SHARED_STATE_REDIS_URL", "not a url")
	if c := NewRedisClient(); c != nil {
		t.Fatal("an invalid URL must fall back to in-process broadcasts")
	}
}

func TestNewRedisClientUnreachable(t *testing.T) {
	server := miniredis.RunT(t)
	addr := server.Addr()
	server.Close()
	t.Setenv("SHARED_STATE_REDIS_URL", "redis://"+addr)
	if c := NewRedisClient(); c != nil {
		t.Fatal("an unreachable Redis must fall back to in-process broadcasts")
	}
}

func TestNilClientIsANoOp(t *testing.T) {
	var c *RedisClient
	if err := c.Publish(context.Background(), "ch", []byte("x")); err != nil {
		t.Fatal(err)
	}
	if err := c.Subscribe(context.Background(), "ch", make(chan []byte)); err != nil {
		t.Fatal(err)
	}
}

func connected(t *testing.T) (*RedisClient, *miniredis.Miniredis) {
	t.Helper()
	server := miniredis.RunT(t)
	t.Setenv("SHARED_STATE_REDIS_URL", "redis://"+server.Addr())
	c := NewRedisClient()
	if c == nil {
		t.Fatal("expected a connected client")
	}
	t.Cleanup(func() { _ = c.Client.Close() })
	return c, server
}

func TestPublishReachesSubscriber(t *testing.T) {
	c, _ := connected(t)
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	if err := c.Ping(ctx); err != nil {
		t.Fatal(err)
	}

	got := make(chan []byte, 1)
	if err := c.Subscribe(ctx, "workspace:a", got); err != nil {
		t.Fatal(err)
	}
	if err := c.Publish(ctx, "workspace:b", []byte("other")); err != nil {
		t.Fatal(err)
	}
	if err := c.Publish(ctx, "workspace:a", []byte("hello")); err != nil {
		t.Fatal(err)
	}
	select {
	case msg := <-got:
		if string(msg) != "hello" {
			t.Fatalf("got %q, want only the subscribed channel's message", msg)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("message not delivered")
	}
}

func TestSubscriptionStopsWithContext(t *testing.T) {
	c, server := connected(t)
	ctx, cancel := context.WithCancel(context.Background())
	got := make(chan []byte) // unbuffered and never read: delivery blocks
	if err := c.Subscribe(ctx, "workspace:a", got); err != nil {
		t.Fatal(err)
	}
	if err := c.Publish(context.Background(), "workspace:a", []byte("stuck")); err != nil {
		t.Fatal(err)
	}
	cancel()
	deadline := time.Now().Add(2 * time.Second)
	for len(server.PubSubChannels("workspace:*")) > 0 {
		if time.Now().After(deadline) {
			t.Fatal("subscription not released after its context ended")
		}
		time.Sleep(10 * time.Millisecond)
	}
}

func TestSubscribeFailsWhenRedisIsDown(t *testing.T) {
	c, server := connected(t)
	server.Close()
	if err := c.Subscribe(context.Background(), "workspace:a", make(chan []byte)); err == nil {
		t.Fatal("subscribing without Redis must fail")
	}
	if err := c.Publish(context.Background(), "workspace:a", []byte("x")); err == nil {
		t.Fatal("publishing without Redis must fail")
	}
}
