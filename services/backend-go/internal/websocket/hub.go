package websocket

import (
	"encoding/json"
	"log"

	"github.com/skolab/backend-go/internal/pubsub"
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
}

func NewHub() *Hub {
	h := &Hub{
		Broadcast:  make(chan wsMessage),
		Register:   make(chan *Client),
		Unregister: make(chan *Client),
		Clients:    make(map[string]map[*Client]bool),
		Redis:      pubsub.NewRedisClient(),
		redisRelay: make(chan []byte),
	}

	// If Redis is connected, start a goroutine to listen to the broadcast
	// channel and decode each message back into its workspace-scoped shape.
	if h.Redis != nil {
		go h.Redis.Subscribe("ws_broadcast", h.redisRelay)
		go func() {
			for raw := range h.redisRelay {
				var m wsMessage
				if err := json.Unmarshal(raw, &m); err != nil {
					log.Printf("ws hub: dropping malformed redis broadcast: %v", err)
					continue
				}
				h.Broadcast <- m
			}
		}()
	}

	return h
}

func (h *Hub) Run() {
	for {
		select {
		case client := <-h.Register:
			if h.Clients[client.workspaceID] == nil {
				h.Clients[client.workspaceID] = make(map[*Client]bool)
			}
			h.Clients[client.workspaceID][client] = true
			log.Printf("Client registered for workspace %q. Clients in workspace: %d\n",
				client.workspaceID, len(h.Clients[client.workspaceID]))
		case client := <-h.Unregister:
			if peers, ok := h.Clients[client.workspaceID]; ok {
				if _, ok := peers[client]; ok {
					delete(peers, client)
					close(client.send)
					if len(peers) == 0 {
						delete(h.Clients, client.workspaceID)
					}
					log.Printf("Client unregistered from workspace %q. Clients in workspace: %d\n",
						client.workspaceID, len(peers))
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
func (h *Hub) Publish(workspaceID string, message []byte) {
	m := wsMessage{WorkspaceID: workspaceID, Payload: message}
	if h.Redis != nil {
		raw, err := json.Marshal(m)
		if err != nil {
			log.Printf("ws hub: failed to encode broadcast for redis: %v", err)
			h.Broadcast <- m // Best-effort local fallback -- still workspace-scoped.
			return
		}
		if pubErr := h.Redis.Publish("ws_broadcast", raw); pubErr != nil {
			log.Printf("Redis publish error: %v", pubErr)
			h.Broadcast <- m // Fallback
		}
	} else {
		h.Broadcast <- m
	}
}
