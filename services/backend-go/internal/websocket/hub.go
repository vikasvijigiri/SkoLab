package websocket

import (
	"log"

	"github.com/skolab/backend-go/internal/pubsub"
)

// Hub maintains the set of active clients and broadcasts messages to the clients.
type Hub struct {
	// Registered clients.
	Clients map[*Client]bool

	// Inbound messages from the clients.
	Broadcast chan []byte

	// Register requests from the clients.
	Register chan *Client

	// Unregister requests from clients.
	Unregister chan *Client

	// Redis PubSub
	Redis *pubsub.RedisClient
}

func NewHub() *Hub {
	h := &Hub{
		Broadcast:  make(chan []byte),
		Register:   make(chan *Client),
		Unregister: make(chan *Client),
		Clients:    make(map[*Client]bool),
		Redis:      pubsub.NewRedisClient(),
	}

	// If Redis is connected, start a goroutine to listen to the broadcast channel
	if h.Redis != nil {
		go h.Redis.Subscribe("ws_broadcast", h.Broadcast)
	}

	return h
}

func (h *Hub) Run() {
	for {
		select {
		case client := <-h.Register:
			h.Clients[client] = true
			log.Printf("Client registered. Total clients: %d\n", len(h.Clients))
		case client := <-h.Unregister:
			if _, ok := h.Clients[client]; ok {
				delete(h.Clients, client)
				close(client.send)
				log.Printf("Client unregistered. Total clients: %d\n", len(h.Clients))
			}
		case message := <-h.Broadcast:
			// If a message hits the broadcast channel, send it to all local clients
			for client := range h.Clients {
				select {
				case client.send <- message:
				default:
					close(client.send)
					delete(h.Clients, client)
				}
			}
		}
	}
}

// Publish is used by clients to send messages to the Hub.
// If Redis is enabled, it pushes to Redis. Otherwise, it pushes to the local Broadcast channel.
func (h *Hub) Publish(message []byte) {
	if h.Redis != nil {
		err := h.Redis.Publish("ws_broadcast", message)
		if err != nil {
			log.Printf("Redis publish error: %v", err)
			h.Broadcast <- message // Fallback
		}
	} else {
		h.Broadcast <- message
	}
}
