package cache

import (
	"sync"
	"time"
)

type entry struct {
	value     any
	expiresAt time.Time
}

// Cache is a thread-safe in-memory store with per-key TTL and background cleanup.
type Cache struct {
	mu    sync.RWMutex
	items map[string]entry
}

// New creates a Cache that runs a cleanup sweep every cleanupInterval.
func New(cleanupInterval time.Duration) *Cache {
	c := &Cache{items: make(map[string]entry)}
	go c.runCleanup(cleanupInterval)
	return c
}

func (c *Cache) Set(key string, value any, ttl time.Duration) {
	c.mu.Lock()
	c.items[key] = entry{value: value, expiresAt: time.Now().Add(ttl)}
	c.mu.Unlock()
}

func (c *Cache) Get(key string) (any, bool) {
	c.mu.RLock()
	e, ok := c.items[key]
	c.mu.RUnlock()
	if !ok || time.Now().After(e.expiresAt) {
		return nil, false
	}
	return e.value, true
}

func (c *Cache) Delete(key string) {
	c.mu.Lock()
	delete(c.items, key)
	c.mu.Unlock()
}

func (c *Cache) Flush() {
	c.mu.Lock()
	c.items = make(map[string]entry)
	c.mu.Unlock()
}

func (c *Cache) runCleanup(interval time.Duration) {
	ticker := time.NewTicker(interval)
	defer ticker.Stop()
	for range ticker.C {
		now := time.Now()
		c.mu.Lock()
		for k, e := range c.items {
			if now.After(e.expiresAt) {
				delete(c.items, k)
			}
		}
		c.mu.Unlock()
	}
}
