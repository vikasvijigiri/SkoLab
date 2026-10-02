package websocket

import (
	"context"
	"log/slog"
	"net/http"
	"strings"
	"sync/atomic"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/gorilla/websocket"
	"github.com/skolab/backend-go/internal/middleware"
)

const (
	writeWait      = 10 * time.Second
	pongWait       = 60 * time.Second
	pingPeriod     = (pongWait * 9) / 10
	maxMessageSize = 512000

	// A removed member's open socket must not outlive their access, so the
	// role is re-checked periodically, not only at upgrade.
	maxReauthorizeFailures = 3
	reauthorizeInterval    = 30 * time.Second
)

var upgrader = websocket.Upgrader{
	ReadBufferSize:  1024,
	WriteBufferSize: 1024,
	// Reuse the gateway's own CORS allow-list (2026-09-12 endpoint audit):
	// accepting every origin let any page on the internet open a socket
	// here. A missing Origin header (native apps, curl, most non-browser
	// clients) is allowed through, same as CORS() does for non-browser
	// callers -- Origin is a browser-enforced header, so its absence isn't
	// itself suspicious.
	//
	// The route also now requires a verified Firebase token (?token=, see
	// auth.VerifyQueryToken in main.go) and the Hub only broadcasts a message
	// to other clients registered for the same :workspace_id (see hub.go) --
	// 2026-09-14 endpoint audit closed both gaps together.
	CheckOrigin: func(r *http.Request) bool {
		origin := r.Header.Get("Origin")
		return origin == "" || middleware.IsAllowedOrigin(origin)
	},
}

// Client is a middleman between the websocket connection and the hub.
type Client struct {
	hub         *Hub
	conn        *websocket.Conn
	send        chan []byte
	workspaceID string
	userID      string
	authorizer  WorkspaceAuthorizer
	role        atomic.Pointer[string]
	done        chan struct{} // closed when readPump exits
}

func (c *Client) currentRole() string {
	if role := c.role.Load(); role != nil {
		return *role
	}
	return ""
}

// closePolicy ends the connection with RFC 6455 code 1008 (policy
// violation). WriteControl and Close are safe to call concurrently with the
// pumps; readPump then exits and unregisters the client.
func (c *Client) closePolicy(reason string) {
	_ = c.conn.WriteControl(websocket.CloseMessage,
		websocket.FormatCloseMessage(websocket.ClosePolicyViolation, reason), time.Now().Add(writeWait))
	_ = c.conn.Close()
}

// reauthorize re-reads the caller's role on a timer: a revoked member is
// disconnected, a changed role takes effect, and authorization that stays
// unavailable for several checks fails closed.
func (c *Client) reauthorize(interval time.Duration) {
	ticker := time.NewTicker(interval)
	defer ticker.Stop()
	failures := 0
	for {
		select {
		case <-c.done:
			return
		case <-ticker.C:
		}
		ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		role, err := c.authorizer.Role(ctx, c.workspaceID, c.userID)
		cancel()
		if err != nil {
			if failures++; failures >= maxReauthorizeFailures {
				slog.Warn("websocket closed: authorization unavailable", "workspace_id", c.workspaceID, "err", err)
				c.closePolicy("authorization unavailable")
				return
			}
			continue
		}
		failures = 0
		if role == "" {
			slog.Info("websocket closed: workspace access revoked", "workspace_id", c.workspaceID, "user_id", c.userID)
			c.closePolicy("access revoked")
			return
		}
		c.role.Store(&role)
	}
}

func (c *Client) readPump() {
	defer close(c.done)
	defer func() {
		c.hub.Unregister <- c
		c.conn.Close()
	}()
	c.conn.SetReadLimit(maxMessageSize)
	c.conn.SetReadDeadline(time.Now().Add(pongWait))
	c.conn.SetPongHandler(func(string) error { c.conn.SetReadDeadline(time.Now().Add(pongWait)); return nil })
	for {
		_, message, err := c.conn.ReadMessage()
		if err != nil {
			if websocket.IsUnexpectedCloseError(err, websocket.CloseGoingAway, websocket.CloseAbnormalClosure) {
				slog.Warn("websocket read error", "err", err)
			}
			break
		}
		if !CanPublish(c.currentRole()) {
			// Read-only roles receive updates but never change the document.
			slog.Warn("websocket closed: write from read-only role", "workspace_id", c.workspaceID,
				"user_id", c.userID, "role", c.currentRole())
			c.closePolicy("read-only role")
			break
		}
		c.hub.Publish(c.workspaceID, message)
	}
}

func (c *Client) writePump() {
	ticker := time.NewTicker(pingPeriod)
	defer func() {
		ticker.Stop()
		c.conn.Close()
	}()
	for {
		select {
		case message, ok := <-c.send:
			c.conn.SetWriteDeadline(time.Now().Add(writeWait))
			if !ok {
				c.conn.WriteMessage(websocket.CloseMessage, []byte{})
				return
			}

			w, err := c.conn.NextWriter(websocket.TextMessage)
			if err != nil {
				return
			}
			w.Write(message)

			// Add queued chat messages to the current websocket message.
			n := len(c.send)
			for i := 0; i < n; i++ {
				w.Write([]byte{'\n'})
				w.Write(<-c.send)
			}

			if err := w.Close(); err != nil {
				return
			}
		case <-ticker.C:
			c.conn.SetWriteDeadline(time.Now().Add(writeWait))
			if err := c.conn.WriteMessage(websocket.PingMessage, nil); err != nil {
				return
			}
		}
	}
}

// ServeWs handles websocket requests from the peer. The caller must already
// be authenticated (see auth.VerifyQueryToken, wired in main.go). Before an
// upgrade it verifies that this identity owns or actively belongs to the
// requested workspace; a guessed workspace ID is never sufficient.
func ServeWs(hub *Hub, authorizer WorkspaceAuthorizer, c *gin.Context) {
	serveWs(hub, authorizer, c, reauthorizeInterval)
}

// serveWs takes the re-authorization interval as a parameter (rather than
// reading a mutable package variable) so tests can shorten it race-free.
func serveWs(hub *Hub, authorizer WorkspaceAuthorizer, c *gin.Context, reauthorizeEvery time.Duration) {
	workspaceID := strings.TrimSpace(c.Param("workspace_id"))
	if workspaceID == "" {
		c.AbortWithStatusJSON(http.StatusBadRequest, gin.H{"error": "workspace_id is required"})
		return
	}
	userID := strings.TrimSpace(c.GetString("user_id"))
	if userID == "" {
		c.AbortWithStatusJSON(http.StatusUnauthorized, gin.H{"error": "Authentication is required"})
		return
	}
	if authorizer == nil {
		c.AbortWithStatusJSON(http.StatusServiceUnavailable, gin.H{"error": "Workspace authorization is temporarily unavailable"})
		return
	}
	role, err := authorizer.Role(c.Request.Context(), workspaceID, userID)
	if err != nil {
		slog.Error("websocket workspace authorization failed", "workspace_id", workspaceID, "err", err)
		c.AbortWithStatusJSON(http.StatusServiceUnavailable, gin.H{"error": "Workspace authorization is temporarily unavailable"})
		return
	}
	if role == "" {
		c.AbortWithStatusJSON(http.StatusForbidden, gin.H{"error": "You do not have access to this workspace"})
		return
	}

	conn, err := upgrader.Upgrade(c.Writer, c.Request, nil)
	if err != nil {
		slog.Warn("websocket upgrade error", "err", err)
		return
	}
	client := &Client{hub: hub, conn: conn, send: make(chan []byte, 256), workspaceID: workspaceID,
		userID: userID, authorizer: authorizer, done: make(chan struct{})}
	client.role.Store(&role)
	client.hub.Register <- client

	// Allow collection of memory referenced by the caller by doing all work in new goroutines.
	go client.writePump()
	go client.readPump()
	go client.reauthorize(reauthorizeEvery)
}

// ServeHealthWs handles persistent websocket connections for system health.
func ServeHealthWs(c *gin.Context) {
	conn, err := upgrader.Upgrade(c.Writer, c.Request, nil)
	if err != nil {
		slog.Warn("health ws upgrade error", "err", err)
		return
	}
	defer conn.Close()

	conn.SetReadLimit(maxMessageSize)
	conn.SetReadDeadline(time.Now().Add(pongWait))
	conn.SetPongHandler(func(string) error { conn.SetReadDeadline(time.Now().Add(pongWait)); return nil })

	// Block and keep the connection alive
	for {
		mt, message, err := conn.ReadMessage()
		if err != nil {
			break
		}
		// Optional: echo back any payload (like ping timestamps)
		conn.SetWriteDeadline(time.Now().Add(writeWait))
		conn.WriteMessage(mt, message)
	}
}
