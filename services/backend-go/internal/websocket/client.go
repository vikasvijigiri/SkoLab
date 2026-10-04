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
	"github.com/skolab/backend-go/internal/security"
	"golang.org/x/time/rate"
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
	release     func()
	authTime    int64
	queuedBytes atomic.Int64
}

func (c *Client) enqueue(message []byte) bool {
	n := int64(len(message))
	if c.queuedBytes.Add(n) > 1024*1024 {
		c.queuedBytes.Add(-n)
		return false
	}
	if c.hub.queuedBytes.Add(n) > 16*1024*1024 {
		c.hub.queuedBytes.Add(-n)
		c.queuedBytes.Add(-n)
		return false
	}
	select {
	case c.send <- message:
		return true
	default:
		c.releaseBytes(message)
		return false
	}
}

func (c *Client) releaseBytes(message []byte) {
	c.queuedBytes.Add(-int64(len(message)))
	c.hub.queuedBytes.Add(-int64(len(message)))
}

// Called only by the hub, which owns channel sends and closure.
func (c *Client) closeQueue() {
	close(c.send)
	for message := range c.send {
		c.releaseBytes(message)
	}
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
	security.RecordContext(context.Background(), security.Event{Name: security.SocketClosed, Outcome: security.Denied,
		UserID: c.userID, WorkspaceID: c.workspaceID, Reason: reason})
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
		if c.hub.CheckSession != nil {
			if err := c.hub.CheckSession(ctx, c.userID, c.authTime); err != nil {
				cancel()
				c.closePolicy("session unavailable or revoked")
				return
			}
		}
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
	if c.release != nil {
		defer c.release()
	}
	defer close(c.done)
	defer func() {
		select {
		case c.hub.Unregister <- c:
		case <-c.hub.ctx.Done():
		}
		c.conn.Close()
	}()
	c.conn.SetReadLimit(maxMessageSize)
	c.conn.SetReadDeadline(time.Now().Add(pongWait))
	c.conn.SetPongHandler(func(string) error { c.conn.SetReadDeadline(time.Now().Add(pongWait)); return nil })
	messages := rate.NewLimiter(30, 60)
	bytes := rate.NewLimiter(256*1024, maxMessageSize)
	lifetime := time.AfterFunc(15*time.Minute, func() { c.closePolicy("session renewal required") })
	defer lifetime.Stop()
	for {
		_, message, err := c.conn.ReadMessage()
		if err != nil {
			if websocket.IsUnexpectedCloseError(err, websocket.CloseGoingAway, websocket.CloseAbnormalClosure) {
				slog.Warn("websocket read error", "err", err)
			}
			break
		}
		if !messages.Allow() || !bytes.AllowN(time.Now(), len(message)) {
			c.closePolicy("message rate exceeded")
			break
		}
		if !CanPublish(c.currentRole()) {
			// Read-only roles receive updates but never change the document.
			slog.Warn("websocket closed: write from read-only role", "workspace_id", c.workspaceID,
				"user_id", c.userID, "role", c.currentRole())
			c.closePolicy("read-only role")
			break
		}
		if err := c.hub.Publish(c.workspaceID, message); err != nil {
			_ = c.conn.WriteControl(websocket.CloseMessage, websocket.FormatCloseMessage(websocket.CloseTryAgainLater, "collaboration temporarily unavailable; reconnect"), time.Now().Add(writeWait))
			break
		}
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
			c.releaseBytes(message)

			w, err := c.conn.NextWriter(websocket.TextMessage)
			if err != nil {
				return
			}
			w.Write(message)

			// Add queued chat messages to the current websocket message.
			n := len(c.send)
			for i := 0; i < n; i++ {
				w.Write([]byte{'\n'})
				queued := <-c.send
				c.releaseBytes(queued)
				w.Write(queued)
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
	if hub.ctx.Err() != nil {
		c.AbortWithStatusJSON(http.StatusServiceUnavailable, gin.H{"error": "Service restarting"})
		return
	}
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
		security.Record(c, security.Event{Name: security.WorkspaceDenied, Outcome: security.Denied, WorkspaceID: workspaceID, Reason: "socket"})
		c.AbortWithStatusJSON(http.StatusForbidden, gin.H{"error": "You do not have access to this workspace"})
		return
	}

	if hub.CheckSession != nil {
		ctx, cancel := context.WithTimeout(c.Request.Context(), 5*time.Second)
		err := hub.CheckSession(ctx, userID, c.GetInt64("session_auth_time"))
		cancel()
		if err != nil {
			c.AbortWithStatusJSON(http.StatusUnauthorized, gin.H{"error": "Session unavailable or revoked"})
			return
		}
	}
	release, admitted := hub.reserve(userID, workspaceID)
	if !admitted {
		c.Header("Retry-After", "5")
		c.AbortWithStatusJSON(http.StatusTooManyRequests, gin.H{"error": "Collaboration connection limit reached"})
		return
	}
	conn, err := upgrader.Upgrade(c.Writer, c.Request, nil)
	if err != nil {
		release()
		slog.Warn("websocket upgrade error", "err", err)
		return
	}
	client := &Client{hub: hub, conn: conn, send: make(chan []byte, 8), workspaceID: workspaceID,
		userID: userID, authorizer: authorizer, done: make(chan struct{}), release: release,
		authTime: c.GetInt64("session_auth_time")}
	client.role.Store(&role)
	select {
	case client.hub.Register <- client:
	case <-hub.ctx.Done():
		release()
		_ = conn.Close()
		return
	}

	// Allow collection of memory referenced by the caller by doing all work in new goroutines.
	go client.writePump()
	go client.readPump()
	go client.reauthorize(reauthorizeEvery)
}
