package pubsub

import (
	"context"
	"log/slog"
	"os"
	"time"

	"github.com/redis/go-redis/v9"
)

// RedisClient wraps the go-redis client
type RedisClient struct {
	Client *redis.Client
}

// NewRedisClient creates a new connected Redis client
func NewRedisClient() *RedisClient {
	// SHARED_STATE_REDIS_URL, not REDIS_URL: the Python service's cache
	// Redis must not silently route every WebSocket message through itself.
	// Set it only when running more than one gateway instance.
	redisURL := os.Getenv("SHARED_STATE_REDIS_URL")
	if redisURL == "" {
		// One instance: broadcasts stay in process, no dial attempt.
		return nil
	}

	opt, err := redis.ParseURL(redisURL)
	if err != nil {
		// Not logging redisURL itself: a Redis URL can carry a password
		// (redis://user:pass@host:port) and this would otherwise put it in
		// plaintext logs (2026-09-26 reliability audit, found in passing
		// while converting this file's logging).
		slog.Warn("Invalid REDIS_URL — falling back to memory-only mode", "err", err)
		return nil
	}

	opt.DialTimeout = time.Second
	opt.ReadTimeout = time.Second
	opt.WriteTimeout = time.Second
	opt.MaxRetries = 1
	opt.ContextTimeoutEnabled = true
	client := redis.NewClient(opt)

	// Ping to check connection. opt.Addr is host:port only, no credentials.
	pingCtx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()
	if err := client.Ping(pingCtx).Err(); err != nil {
		slog.Warn("Cannot connect to Redis — falling back to memory-only mode",
			"addr", opt.Addr, "err", err)
		_ = client.Close()
		return nil
	}

	slog.Info("Successfully connected to Redis Pub/Sub backend.")
	return &RedisClient{Client: client}
}

// Subscribe listens to a specific Redis channel and forwards messages to a channel
func (r *RedisClient) Subscribe(ctx context.Context, channel string, messageChan chan<- []byte) error {
	if r == nil || r.Client == nil {
		return nil
	}

	pubsub := r.Client.Subscribe(ctx, channel)
	// Wait for the subscription acknowledgement before accepting traffic.
	readyCtx, cancel := context.WithTimeout(ctx, 2*time.Second)
	defer cancel()
	if _, err := pubsub.Receive(readyCtx); err != nil {
		_ = pubsub.Close()
		return err
	}
	go func() {
		defer pubsub.Close()
		ch := pubsub.Channel()
		for {
			select {
			case <-ctx.Done():
				return
			case msg, ok := <-ch:
				if !ok {
					return
				}
				select {
				case messageChan <- []byte(msg.Payload):
				case <-ctx.Done():
					return
				}
			}
		}
	}()
	return nil
}

// Publish broadcasts a message to a specific Redis channel
func (r *RedisClient) Publish(ctx context.Context, channel string, message []byte) error {
	if r == nil || r.Client == nil {
		return nil
	}
	ctx, cancel := context.WithTimeout(ctx, time.Second)
	defer cancel()
	return r.Client.Publish(ctx, channel, message).Err()
}

func (r *RedisClient) Ping(ctx context.Context) error { return r.Client.Ping(ctx).Err() }
