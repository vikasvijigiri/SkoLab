package websocket

import (
	"context"
	"encoding/json"
	"fmt"
	"log/slog"
	"sync"
	"time"

	gorillaws "github.com/gorilla/websocket"
	"github.com/skolab/backend-go/internal/pubsub"
	"github.com/skolab/backend-go/internal/shared"
)

// wsMessage is one broadcast, scoped to a single workspace. It crosses the
// Redis pub/sub boundary JSON-encoded (Redis only carries raw bytes) so a
// multi-instance deployment keeps the same per-workspace scoping a single
// instance gets for free from Hub.Clients being keyed by workspace ID.
type wsMessage struct {
	WorkspaceID string `json:"workspace_id"`
	Payload     []byte `json:"payload"`
}

// Hub maintains the set of active clients, keyed by :workspace_id, and
// broadcasts a message only to OTHER clients connected to that same
// workspace -- not to every connected client regardless of workspace, which
// is what this used to do (2026-09-14 endpoint audit: self-documented as a
// known gap, closed here alongside adding auth to the route in main.go).
type Hub struct {
	// Registered clients, partitioned by workspace_id so a broadcast never
	// has to scan (or accidentally reach) a client in a different workspace.
	Clients map[string]map[*Client]bool

	// Inbound messages awaiting broadcast, each already tagged with the
	// workspace it belongs to.
	Broadcast chan wsMessage

	// Register requests from clients.
	Register chan *Client

	// Unregister requests from clients.
	Unregister chan *Client

	// Redis PubSub
	Redis *pubsub.RedisClient

	// redisRelay carries raw bytes off Redis before they're decoded back
	// into a wsMessage and handed to Broadcast -- Redis itself only ever
	// speaks []byte.
	redisRelay chan []byte
	ctx        context.Context
	cancel     context.CancelFunc
	stopped    chan struct{}
	stopOnce   sync.Once
	initErr    error
}

func NewHub() *Hub {
	return NewHubWithRedis(pubsub.NewRedisClient())
}

// NewHubWithRedis permits deterministic multi-instance integration tests.
func NewHubWithRedis(redis *pubsub.RedisClient) *Hub {
	ctx, cancel := context.WithCancel(context.Background())
	h := &Hub{
		Broadcast:  make(chan wsMessage),
		Register:   make(chan *Client),
		Unregister: make(chan *Client),
		Clients:    make(map[string]map[*Client]bool),
		Redis:      redis,
		redisRelay: make(chan []byte),
		ctx:        ctx, cancel: cancel, stopped: make(chan struct{}),
	}

	// If Redis is connected, start a goroutine to listen to the broadcast
	// channel and decode each message back into its workspace-scoped shape.
	if h.Redis != nil {
		h.initErr = h.Redis.Subscribe(ctx, "ws_broadcast", h.redisRelay)
		if h.initErr != nil && !shared.Required() {
			slog.Warn("ws subscription unavailable; using local broadcasts")
			_ = h.Redis.Client.Close()
			h.Redis, h.initErr = nil, nil
		}
		if h.Redis == nil {
			return h
		}
		go func() {
			for {
				var raw []byte
				select {
				case raw = <-h.redisRelay:
				case <-ctx.Done():
					return
				}
				var m wsMessage
				if err := json.Unmarshal(raw, &m); err != nil {
					slog.Warn("ws hub: dropping malformed redis broadcast", "err", err)
					continue
				}
				select {
				case h.Broadcast <- m:
				case <-ctx.Done():
					return
				}
			}
		}()
	}

	return h
}

func (h *Hub) Run() {
	defer close(h.stopped)
	for {
		select {
		case <-h.ctx.Done():
			var closing sync.WaitGroup
			for _, peers := range h.Clients {
				for client := range peers {
					if client.conn != nil {
						closing.Add(1)
						go func(c *Client) {
							defer closing.Done()
							_ = c.conn.WriteControl(gorillaws.CloseMessage, gorillaws.FormatCloseMessage(gorillaws.CloseServiceRestart, "service restarting; reconnect"), time.Now().Add(time.Second))
							_ = c.conn.Close()
							close(c.send)
						}(client)
					} else {
						close(client.send)
					}
				}
			}
			closing.Wait()
			if h.Redis != nil {
				_ = h.Redis.Client.Close()
			}
			return
		case client := <-h.Register:
			if h.Clients[client.workspaceID] == nil {
				h.Clients[client.workspaceID] = make(map[*Client]bool)
			}
			h.Clients[client.workspaceID][client] = true
			slog.Info("client registered for workspace",
				"workspace_id", client.workspaceID, "clients_in_workspace", len(h.Clients[client.workspaceID]))
		case client := <-h.Unregister:
			if peers, ok := h.Clients[client.workspaceID]; ok {
				if _, ok := peers[client]; ok {
					delete(peers, client)
					close(client.send)
					if len(peers) == 0 {
						delete(h.Clients, client.workspaceID)
					}
					slog.Info("client unregistered from workspace",
						"workspace_id", client.workspaceID, "clients_in_workspace", len(peers))
				}
			}
		case message := <-h.Broadcast:
			// Only the clients registered for this exact workspace ever see
			// the message -- the fix for the broadcast-to-everyone gap.
			for client := range h.Clients[message.WorkspaceID] {
				select {
				case client.send <- message.Payload:
				default:
					close(client.send)
					delete(h.Clients[message.WorkspaceID], client)
				}
			}
		}
	}
}

// Publish is used by clients to send a message to every other client
// connected to the same workspaceID. If Redis is enabled, it pushes to Redis
// (JSON-encoded, so scoping survives a multi-instance deployment); otherwise
// it pushes straight to the local Broadcast channel.
func (h *Hub) Publish(workspaceID string, message []byte) error {
	m := wsMessage{WorkspaceID: workspaceID, Payload: message}
	if h.Redis != nil {
		raw, err := json.Marshal(m)
		if err != nil {
			slog.Warn("ws hub: failed to encode broadcast for redis", "err", err)
			return err
		}
		if pubErr := h.Redis.Publish(h.ctx, "ws_broadcast", raw); pubErr != nil {
			slog.Warn("Redis publish error", "err", pubErr)
			if shared.Required() {
				return pubErr
			}
			return h.localPublish(m)
		}
	} else {
		if shared.Required() {
			return fmt.Errorf("shared broadcasts unavailable")
		}
		return h.localPublish(m)
	}
	return nil
}

func (h *Hub) localPublish(m wsMessage) error {
	select {
	case h.Broadcast <- m:
		return nil
	case <-h.ctx.Done():
		return h.ctx.Err()
	}
}

func (h *Hub) Ready(ctx context.Context) error {
	if h.ctx.Err() != nil {
		return h.ctx.Err()
	}
	if h.initErr != nil {
		return h.initErr
	}
	if h.Redis == nil {
		if shared.Required() {
			return fmt.Errorf("shared broadcasts unavailable")
		}
		return nil
	}
	return h.Redis.Ping(ctx)
}

// Shutdown closes hijacked WebSockets, which http.Server.Shutdown cannot drain.
func (h *Hub) Shutdown(ctx context.Context) error {
	h.stopOnce.Do(h.cancel)
	select {
	case <-h.stopped:
		return nil
	case <-ctx.Done():
		return ctx.Err()
	}
}
