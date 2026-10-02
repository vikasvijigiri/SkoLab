package websocket

import (
	"context"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"errors"
	"net/http"
	"strings"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/skolab/backend-go/internal/security"
)

const websocketTicketTTL = time.Minute

var (
	ErrWorkspaceTicketUnavailable = errors.New("workspace ticket store unavailable")
	ErrInvalidWorkspaceTicket     = errors.New("invalid or expired workspace ticket")
)

// WorkspaceTicketStore persists only SHA-256 ticket digests. A Firebase bearer
// token is exchanged over HTTPS, while the WebSocket URL carries only the
// single-use opaque ticket.
type WorkspaceTicketStore interface {
	Issue(context.Context, string, string) (string, error)
	Consume(context.Context, string, string) (string, error)
}

type postgresWorkspaceTicketStore struct {
	pool *pgxpool.Pool
}

func NewPostgresWorkspaceTicketStore(pool *pgxpool.Pool) WorkspaceTicketStore {
	return &postgresWorkspaceTicketStore{pool: pool}
}

func ticketDigest(ticket string) string {
	digest := sha256.Sum256([]byte(ticket))
	return hex.EncodeToString(digest[:])
}

func newTicket() (string, error) {
	bytes := make([]byte, 32)
	if _, err := rand.Read(bytes); err != nil {
		return "", err
	}
	return base64.RawURLEncoding.EncodeToString(bytes), nil
}

func (s *postgresWorkspaceTicketStore) Issue(ctx context.Context, workspaceID, userID string) (string, error) {
	if s == nil || s.pool == nil {
		return "", ErrWorkspaceTicketUnavailable
	}
	ticket, err := newTicket()
	if err != nil {
		return "", errors.Join(ErrWorkspaceTicketUnavailable, err)
	}

	ctx, cancel := context.WithTimeout(ctx, 2*time.Second)
	defer cancel()
	if _, err := s.pool.Exec(ctx, `DELETE FROM websocket_tickets WHERE expires_at <= NOW()`); err != nil {
		return "", errors.Join(ErrWorkspaceTicketUnavailable, err)
	}
	_, err = s.pool.Exec(ctx, `
		INSERT INTO websocket_tickets (ticket_hash, workspace_id, user_id, expires_at)
		VALUES ($1, $2, $3, NOW() + ($4 * INTERVAL '1 second'))`,
		ticketDigest(ticket), workspaceID, userID, int(websocketTicketTTL.Seconds()))
	if err != nil {
		return "", errors.Join(ErrWorkspaceTicketUnavailable, err)
	}
	return ticket, nil
}

func (s *postgresWorkspaceTicketStore) Consume(ctx context.Context, workspaceID, ticket string) (string, error) {
	if s == nil || s.pool == nil {
		return "", ErrWorkspaceTicketUnavailable
	}
	ctx, cancel := context.WithTimeout(ctx, 2*time.Second)
	defer cancel()

	var userID string
	err := s.pool.QueryRow(ctx, `
		DELETE FROM websocket_tickets
		WHERE ticket_hash = $1 AND workspace_id = $2 AND expires_at > NOW()
		RETURNING user_id`, ticketDigest(ticket), workspaceID).Scan(&userID)
	if errors.Is(err, pgx.ErrNoRows) {
		return "", ErrInvalidWorkspaceTicket
	}
	if err != nil {
		return "", errors.Join(ErrWorkspaceTicketUnavailable, err)
	}
	return userID, nil
}

// IssueTicket exchanges a Firebase-authenticated session for a short-lived
// connection ticket after checking workspace membership. Mount behind
// auth.VerifyUser.
func IssueTicket(authorizer WorkspaceAuthorizer, tickets WorkspaceTicketStore) gin.HandlerFunc {
	return func(c *gin.Context) {
		workspaceID := strings.TrimSpace(c.Param("workspace_id"))
		userID := strings.TrimSpace(c.GetString("user_id"))
		if workspaceID == "" {
			c.AbortWithStatusJSON(http.StatusBadRequest, gin.H{"error": "workspace_id is required"})
			return
		}
		if userID == "" {
			c.AbortWithStatusJSON(http.StatusUnauthorized, gin.H{"error": "Authentication is required"})
			return
		}
		if authorizer == nil || tickets == nil {
			c.AbortWithStatusJSON(http.StatusServiceUnavailable, gin.H{"error": "Workspace authorization is temporarily unavailable"})
			return
		}
		role, err := authorizer.Role(c.Request.Context(), workspaceID, userID)
		if err != nil {
			c.AbortWithStatusJSON(http.StatusServiceUnavailable, gin.H{"error": "Workspace authorization is temporarily unavailable"})
			return
		}
		if role == "" {
			security.Record(c, security.Event{Name: security.WorkspaceDenied, Outcome: security.Denied, WorkspaceID: workspaceID, Reason: "ticket"})
			c.AbortWithStatusJSON(http.StatusForbidden, gin.H{"error": "You do not have access to this workspace"})
			return
		}
		ticket, err := tickets.Issue(c.Request.Context(), workspaceID, userID)
		if err != nil {
			c.AbortWithStatusJSON(http.StatusServiceUnavailable, gin.H{"error": "WebSocket tickets are temporarily unavailable"})
			return
		}
		c.Header("Cache-Control", "no-store")
		c.JSON(http.StatusCreated, gin.H{"ticket": ticket, "expires_in_seconds": int(websocketTicketTTL.Seconds())})
	}
}

// VerifyTicket consumes the one-time ticket in the WebSocket URL and restores
// the user identity for ServeWs. It must be mounted before ServeWs.
func VerifyTicket(tickets WorkspaceTicketStore) gin.HandlerFunc {
	return func(c *gin.Context) {
		workspaceID := strings.TrimSpace(c.Param("workspace_id"))
		ticket := strings.TrimSpace(c.Query("ticket"))
		if workspaceID == "" || ticket == "" {
			c.AbortWithStatusJSON(http.StatusUnauthorized, gin.H{"error": "Missing WebSocket ticket"})
			return
		}
		if tickets == nil {
			c.AbortWithStatusJSON(http.StatusServiceUnavailable, gin.H{"error": "WebSocket tickets are temporarily unavailable"})
			return
		}
		userID, err := tickets.Consume(c.Request.Context(), workspaceID, ticket)
		if errors.Is(err, ErrInvalidWorkspaceTicket) {
			security.Record(c, security.Event{Name: security.SocketTicketInvalid, Outcome: security.Denied, WorkspaceID: workspaceID})
			c.AbortWithStatusJSON(http.StatusUnauthorized, gin.H{"error": "Invalid or expired WebSocket ticket"})
			return
		}
		if err != nil {
			c.AbortWithStatusJSON(http.StatusServiceUnavailable, gin.H{"error": "WebSocket tickets are temporarily unavailable"})
			return
		}
		c.Set("user_id", userID)
		c.Next()
	}
}
