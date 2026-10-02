// Package workspace is the CoLab workspace resource API:
//
//	POST   /api/v1/workspaces        create (Idempotency-Key aware) -> 201
//	GET    /api/v1/workspaces        list the caller's workspaces (paginated)
//	GET    /api/v1/workspaces/:id    read
//	PATCH  /api/v1/workspaces/:id    rename (owner only)
//	DELETE /api/v1/workspaces/:id    delete (owner only) -> 204
//
// The owner is always the verified caller, never a request field. Visibility
// follows the same rule as WebSocket authorization (owner, or an active
// member), and a workspace the caller cannot see is reported as 404 rather
// than 403 so IDs cannot be probed.
package workspace

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"net/http"
	"os"
	"strconv"
	"strings"
	"time"
	"unicode"
	"unicode/utf8"

	"github.com/gin-gonic/gin"
	"github.com/skolab/backend-go/internal/security"
)

const (
	maxTitleRunes      = 255 // workspaces.title is VARCHAR(255)
	maxRequestIDLength = 100 // workspaces.create_request_id is VARCHAR(100)
	defaultPageSize    = 50
	maxPageSize        = 100
	defaultMaxPerUser  = 100
)

var (
	ErrNotFound            = errors.New("workspace not found")
	ErrForbidden           = errors.New("only the owner may do this")
	ErrLimitReached        = errors.New("workspace limit reached")
	ErrProfileRequired     = errors.New("caller has no synced profile")
	ErrIdempotencyMismatch = errors.New("idempotency key reused with a different request")
	ErrUnavailable         = errors.New("workspace store unavailable")
)

type Workspace struct {
	ID        string    `json:"id"`
	Title     string    `json:"title"`
	OwnerID   string    `json:"owner_id"`
	Role      string    `json:"role"` // the caller's role: owner, editor, commenter or viewer
	CreatedAt time.Time `json:"created_at"`
}

// Cursor is the keyset position after the last item of a page.
type Cursor struct {
	CreatedAt time.Time `json:"t"`
	ID        string    `json:"id"`
}

// Store is deliberately narrow so handlers are tested without PostgreSQL
// while production uses the shared database as the single source of truth.
type Store interface {
	// Create returns replayed=true when requestID matches an earlier create
	// by the same owner; the original workspace is returned unchanged.
	Create(ctx context.Context, ownerID, title, requestID string, maxPerUser int) (ws Workspace, replayed bool, err error)
	List(ctx context.Context, userID string, after *Cursor, limit int) ([]Workspace, error)
	Get(ctx context.Context, id, userID string) (Workspace, error)
	Rename(ctx context.Context, id, userID, title string) (Workspace, error)
	Delete(ctx context.Context, id, userID string) error
}

func maxPerUser() int {
	if n, err := strconv.Atoi(os.Getenv("WORKSPACE_MAX_PER_USER")); err == nil && n > 0 {
		return n
	}
	return defaultMaxPerUser
}

// Register mounts the routes on a group already protected by auth.VerifyUser().
func Register(group *gin.RouterGroup, store Store) {
	h := handlers{store: store, maxPerUser: maxPerUser()}
	group.POST("/workspaces", h.create)
	group.GET("/workspaces", h.list)
	group.GET("/workspaces/:id", h.get)
	group.PATCH("/workspaces/:id", h.rename)
	group.DELETE("/workspaces/:id", h.delete)
}

type handlers struct {
	store      Store
	maxPerUser int
}

type titleRequest struct {
	Title string `json:"title"`
}

func fail(c *gin.Context, status int, code, message string) {
	c.AbortWithStatusJSON(status, gin.H{"error": message, "code": code})
}

// caller returns the verified Firebase uid, or aborts with 401.
func caller(c *gin.Context) (string, bool) {
	c.Header("Cache-Control", "no-store")
	uid := c.GetString("user_id")
	if uid == "" {
		fail(c, http.StatusUnauthorized, "unauthenticated", "Authentication is required")
		return "", false
	}
	return uid, true
}

// storeError maps a store error onto the API's stable error contract.
func storeError(c *gin.Context, err error) {
	if errors.Is(err, ErrNotFound) || errors.Is(err, ErrForbidden) || errors.Is(err, ErrInviteForbidden) {
		security.Record(c, security.Event{Name: security.WorkspaceDenied, Outcome: security.Denied,
			WorkspaceID: c.Param("id"), Reason: c.Request.Method})
	}
	switch {
	case errors.Is(err, ErrNotFound):
		fail(c, http.StatusNotFound, "not_found", "Workspace not found")
	case errors.Is(err, ErrForbidden):
		fail(c, http.StatusForbidden, "owner_required", "Only the workspace owner may do this")
	case errors.Is(err, ErrLimitReached):
		fail(c, http.StatusForbidden, "workspace_limit_reached", "You have reached the maximum number of workspaces")
	case errors.Is(err, ErrProfileRequired):
		fail(c, http.StatusConflict, "profile_required", "Sync your profile before creating a workspace")
	case errors.Is(err, ErrInviteForbidden):
		fail(c, http.StatusForbidden, "invite_forbidden", "Only the owner or an editor may manage invites")
	case errors.Is(err, ErrOwnerCannotLeave):
		fail(c, http.StatusConflict, "owner_cannot_leave", "The owner cannot leave their own workspace")
	case errors.Is(err, ErrOwnerImmutable):
		fail(c, http.StatusConflict, "owner_immutable", "The owner's role cannot be changed or removed")
	case errors.Is(err, ErrIdempotencyMismatch):
		fail(c, http.StatusUnprocessableEntity, "idempotency_key_reused", "Idempotency-Key was already used for a different request")
	default:
		fail(c, http.StatusServiceUnavailable, "unavailable", "Workspaces are temporarily unavailable")
	}
}

// readTitle validates a title the way the database will (non-blank, at most
// 255 characters) and also rejects control characters, which no UI needs.
func readTitle(c *gin.Context) (string, bool) {
	var req titleRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		fail(c, http.StatusBadRequest, "invalid_body", "Request body must be JSON with a title")
		return "", false
	}
	title := strings.TrimSpace(req.Title)
	switch {
	case title == "":
		fail(c, http.StatusBadRequest, "invalid_title", "Title must not be blank")
	case utf8.RuneCountInString(title) > maxTitleRunes:
		fail(c, http.StatusBadRequest, "invalid_title", "Title must be at most 255 characters")
	case strings.IndexFunc(title, unicode.IsControl) >= 0:
		fail(c, http.StatusBadRequest, "invalid_title", "Title must not contain control characters")
	default:
		return title, true
	}
	return "", false
}

func (h handlers) create(c *gin.Context) {
	uid, ok := caller(c)
	if !ok {
		return
	}
	requestID := c.GetHeader("Idempotency-Key")
	if len(requestID) > maxRequestIDLength || strings.IndexFunc(requestID, func(r rune) bool { return r < 0x21 || r > 0x7e }) >= 0 {
		fail(c, http.StatusBadRequest, "invalid_idempotency_key", "Idempotency-Key must be at most 100 printable ASCII characters")
		return
	}
	title, ok := readTitle(c)
	if !ok {
		return
	}
	ws, replayed, err := h.store.Create(c.Request.Context(), uid, title, requestID, h.maxPerUser)
	if err != nil {
		storeError(c, err)
		return
	}
	if replayed {
		c.Header("Idempotent-Replayed", "true")
	} else {
		security.Audit(c, security.Event{Name: security.WorkspaceCreated, Outcome: security.Allowed, WorkspaceID: ws.ID})
	}
	c.Header("Location", "/api/v1/workspaces/"+ws.ID)
	c.JSON(http.StatusCreated, ws)
}

func (h handlers) list(c *gin.Context) {
	uid, ok := caller(c)
	if !ok {
		return
	}
	size := defaultPageSize
	if raw := c.Query("page_size"); raw != "" {
		n, err := strconv.Atoi(raw)
		if err != nil || n < 1 || n > maxPageSize {
			fail(c, http.StatusBadRequest, "invalid_page_size", "page_size must be between 1 and 100")
			return
		}
		size = n
	}
	var after *Cursor
	if token := c.Query("page_token"); token != "" {
		cursor, err := decodeCursor(token)
		if err != nil {
			fail(c, http.StatusBadRequest, "invalid_page_token", "page_token is invalid")
			return
		}
		after = &cursor
	}
	// Fetch one extra row to learn whether another page exists.
	items, err := h.store.List(c.Request.Context(), uid, after, size+1)
	if err != nil {
		storeError(c, err)
		return
	}
	next := ""
	if len(items) > size {
		items = items[:size]
		last := items[len(items)-1]
		next = encodeCursor(Cursor{CreatedAt: last.CreatedAt, ID: last.ID})
	}
	if items == nil {
		items = []Workspace{}
	}
	c.JSON(http.StatusOK, gin.H{"workspaces": items, "next_page_token": next})
}

func (h handlers) get(c *gin.Context) {
	uid, ok := caller(c)
	if !ok {
		return
	}
	ws, err := h.store.Get(c.Request.Context(), c.Param("id"), uid)
	if err != nil {
		storeError(c, err)
		return
	}
	c.JSON(http.StatusOK, ws)
}

func (h handlers) rename(c *gin.Context) {
	uid, ok := caller(c)
	if !ok {
		return
	}
	title, ok := readTitle(c)
	if !ok {
		return
	}
	ws, err := h.store.Rename(c.Request.Context(), c.Param("id"), uid, title)
	if err != nil {
		storeError(c, err)
		return
	}
	security.Audit(c, security.Event{Name: security.WorkspaceRenamed, Outcome: security.Allowed, WorkspaceID: ws.ID})
	c.JSON(http.StatusOK, ws)
}

func (h handlers) delete(c *gin.Context) {
	uid, ok := caller(c)
	if !ok {
		return
	}
	if err := h.store.Delete(c.Request.Context(), c.Param("id"), uid); err != nil {
		storeError(c, err)
		return
	}
	security.Audit(c, security.Event{Name: security.WorkspaceDeleted, Outcome: security.Allowed, WorkspaceID: c.Param("id")})
	c.Status(http.StatusNoContent)
}

// Page tokens are opaque to clients; the encoding may change without notice.
func encodeCursor(cursor Cursor) string {
	raw, _ := json.Marshal(cursor)
	return base64.RawURLEncoding.EncodeToString(raw)
}

func decodeCursor(token string) (Cursor, error) {
	var cursor Cursor
	raw, err := base64.RawURLEncoding.DecodeString(token)
	if err != nil {
		return cursor, err
	}
	if err := json.Unmarshal(raw, &cursor); err != nil {
		return cursor, err
	}
	if cursor.ID == "" || cursor.CreatedAt.IsZero() {
		return cursor, errors.New("incomplete cursor")
	}
	return cursor, nil
}
