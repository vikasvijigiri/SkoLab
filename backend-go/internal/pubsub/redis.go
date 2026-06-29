package pubsub

import (
	"context"
	"log"
	"os"

	"github.com/redis/go-redis/v9"
)

var ctx = context.Background()

// RedisClient wraps the go-redis client
type RedisClient struct {
	Client *redis.Client
}

// NewRedisClient creates a new connected Redis client
func NewRedisClient() *RedisClient {
	redisURL := os.Getenv("REDIS_URL")
	if redisURL == "" {
		// Use a local default or fallback
		redisURL = "redis://localhost:6379/0"
	}

	opt, err := redis.ParseURL(redisURL)
	if err != nil {
		log.Printf("Warning: Invalid REDIS_URL '%s'. Falling back to memory-only mode. Error: %v\n", redisURL, err)
		return nil
	}

	client := redis.NewClient(opt)

	// Ping to check connection
	if err := client.Ping(ctx).Err(); err != nil {
		log.Printf("Warning: Cannot connect to Redis at %s. Falling back to memory-only mode. Error: %v\n", redisURL, err)
		return nil
	}

	log.Println("Successfully connected to Redis Pub/Sub backend.")
	return &RedisClient{Client: client}
}

// Subscribe listens to a specific Redis channel and forwards messages to a channel
func (r *RedisClient) Subscribe(channel string, messageChan chan<- []byte) {
	if r == nil || r.Client == nil {
		return
	}

	pubsub := r.Client.Subscribe(ctx, channel)
	defer pubsub.Close()

	ch := pubsub.Channel()
	for msg := range ch {
		messageChan <- []byte(msg.Payload)
	}
}

// Publish broadcasts a message to a specific Redis channel
func (r *RedisClient) Publish(channel string, message []byte) error {
	if r == nil || r.Client == nil {
		return nil
	}
	return r.Client.Publish(ctx, channel, message).Err()
}
