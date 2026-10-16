// Package activity is what co-authors did in the workspaces a person
// belongs to: the feed behind the web app's notifications.
//
// Events are written by the packages that make the change (internal/document
// for saves, internal/workspace for membership), inside the same transaction,
// so a feed never shows something that did not happen. A person's saves to one
// document within MergeWindow share a row whose count grows. The feed shows a
// reader the events in workspaces they own or belong to (from when they
// joined), plus events about them (removed, role changed, made owner) even
// after they lost access, never their own actions, and nothing older than
// Retention.
//
// Routes:
//
//	GET  /api/v1/activity       newest first: items, unread count, next_page_token
//	POST /api/v1/activity/seen  everything up to now is read
package activity

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"net/http"
	"strconv"
	"sync/atomic"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/jackc/pgx/v5/pgconn"
	"go.opentelemetry.io/otel/attribute"
	"go.opentelemetry.io/otel/metric"

	"github.com/skolab/backend-go/internal/apierror"
)

// Kinds of event. The database's check constraint lists the same values.
const (
	DocumentEdited       = "document_edited"
	MemberJoined         = "member_joined"
	MemberLeft           = "member_left"
	MemberRemoved        = "member_removed"
	RoleChanged          = "role_changed"
	OwnershipTransferred = "ownership_transferred"
)

const (
	// MergeWindow: saves by one person to one document this close together
	// are one event, so a writing session is one line, not hundreds.
	MergeWindow = 10 * time.Minute
	// Retention: older events are not shown and are pruned as new ones arrive.
	Retention = 90 * 24 * time.Hour
	// unreadCap bounds the unread count; the badge shows 99+ long before.
	unreadCap       = 100
	defaultPageSize = 20
	maxPageSize     = 50
)

var (
	ErrUnavailable = errors.New("activity store unavailable")
)

// Event is one change to record. Subject is who it is about, when that is
// not the actor (the member removed, the new owner); Role is the role given.
type Event struct {
	Kind        string
	WorkspaceID string
	ActorID     string
	SubjectID   string
	Role        string
	At          time.Time
}

// Execer is the transaction an event is written in.
type Execer interface {
	Exec(ctx context.Context, sql string, args ...any) (pgconn.CommandTag, error)
}

var counter atomic.Pointer[metric.Int64Counter]

// UseMeter counts recorded events by kind (skolab.activity.events). Until
// called, events are recorded but not counted.
func UseMeter(meter metric.Meter) error {
	c, err := meter.Int64Counter("skolab.activity.events", metric.WithUnit("{event}"),
		metric.WithDescription("Activity feed events written, by kind (merged saves count once per save)"))
	if err != nil {
		return err
	}
	counter.Store(&c)
	return nil
}

func count(ctx context.Context, kind string) {
	if c := counter.Load(); c != nil {
		(*c).Add(ctx, 1, metric.WithAttributes(attribute.String("kind", kind)))
	}
}

// A save joins the writer's latest edit event when it is recent enough,
// else starts one, but only when someone else can see the workspace: a
// document nobody shares has no one to tell.
const recordEdit = `
	WITH merged AS (
		UPDATE workspace_activity SET count = count + 1, occurred_at = $3
		WHERE id = (
			SELECT id FROM workspace_activity
			WHERE workspace_id = $1 AND actor_id = $2 AND kind = 'document_edited'
			  AND occurred_at > $3::timestamp - make_interval(secs => $4::double precision)
			ORDER BY occurred_at DESC, id DESC
			LIMIT 1
		)
		RETURNING id
	)
	INSERT INTO workspace_activity (workspace_id, kind, actor_id, occurred_at)
	SELECT $1, 'document_edited', $2, $3
	WHERE NOT EXISTS (SELECT 1 FROM merged)
	  AND (EXISTS (SELECT 1 FROM workspaces WHERE id = $1 AND owner_id <> $2)
	       OR EXISTS (SELECT 1 FROM workspace_members
	                  WHERE workspace_id = $1 AND status = 'active' AND user_id <> $2))`

const recordOther = `
	INSERT INTO workspace_activity (workspace_id, kind, actor_id, subject_id, role, occurred_at)
	VALUES ($1, $2, $3, NULLIF($4, ''), NULLIF($5, ''), $6)`

const prune = `DELETE FROM workspace_activity WHERE workspace_id = $1 AND occurred_at < $2`

// Record writes e in tx, the transaction making the change, and prunes the
// workspace's events older than Retention. An error should fail the change:
// the caller's transaction then rolls back with nothing recorded.
func Record(ctx context.Context, tx Execer, e Event) error {
	at := e.At
	if at.IsZero() {
		at = time.Now()
	}
	at = at.UTC().Truncate(time.Microsecond)
	var err error
	switch e.Kind {
	case DocumentEdited:
		_, err = tx.Exec(ctx, recordEdit, e.WorkspaceID, e.ActorID, at, MergeWindow.Seconds())
	case MemberJoined, MemberLeft, MemberRemoved, RoleChanged, OwnershipTransferred:
		_, err = tx.Exec(ctx, recordOther, e.WorkspaceID, e.Kind, e.ActorID, e.SubjectID, e.Role, at)
	default:
		return errors.New("activity: unknown kind " + strconv.Quote(e.Kind))
	}
	if err != nil {
		return err
	}
	if _, err := tx.Exec(ctx, prune, e.WorkspaceID, at.Add(-Retention)); err != nil {
		return err
	}
	count(ctx, e.Kind)
	return nil
}

// Person is who did something, or who it was about. Nil when the account
// has since been deleted.
type Person struct {
	ID          string `json:"id"`
	DisplayName string `json:"display_name"`
}

// WorkspaceRef names the workspace an item happened in.
type WorkspaceRef struct {
	ID    string `json:"id"`
	Title string `json:"title"`
}

// Item is one line of the feed.
type Item struct {
	ID         string       `json:"id"`
	Kind       string       `json:"kind"`
	Workspace  WorkspaceRef `json:"workspace"`
	Actor      *Person      `json:"actor"`
	Subject    *Person      `json:"subject"`
	Role       *string      `json:"role"`
	Count      int          `json:"count"`
	OccurredAt time.Time    `json:"occurred_at"`
	Unread     bool         `json:"unread"`
}

// Cursor is where a page ends: the last item's time and id.
type Cursor struct {
	At time.Time `json:"at"`
	ID int64     `json:"id"`
}

// Page is a stretch of the feed.
type Page struct {
	Items  []Item
	Unread int
	SeenAt *time.Time
	Next   *Cursor
}

// Store reads the feed and records where the reader caught up.
type Store interface {
	Feed(ctx context.Context, userID string, after *Cursor, limit int) (Page, error)
	MarkSeen(ctx context.Context, userID string, at time.Time) (*time.Time, error)
}

// Register mounts the routes on an authenticated group (user_id set).
func Register(r gin.IRoutes, store Store) {
	h := handlers{store: store}
	r.GET("/activity", h.feed)
	r.POST("/activity/seen", h.seen)
}

type handlers struct {
	store Store
}

func fail(c *gin.Context, status int, code, message string) {
	apierror.Abort(c, status, code, message)
}

func caller(c *gin.Context) (string, bool) {
	c.Header("Cache-Control", "no-store")
	uid := c.GetString("user_id")
	if uid == "" {
		fail(c, http.StatusUnauthorized, "unauthenticated", "Authentication is required")
		return "", false
	}
	return uid, true
}

func (h handlers) feed(c *gin.Context) {
	uid, ok := caller(c)
	if !ok {
		return
	}
	// Unknown and repeated parameters are refused, as everywhere in the API.
	for name, values := range c.Request.URL.Query() {
		if name != "page_size" && name != "page_token" {
			fail(c, http.StatusBadRequest, "unknown_parameter", "Unknown query parameter: "+name)
			return
		}
		if len(values) > 1 {
			fail(c, http.StatusBadRequest, "duplicate_parameter", "Query parameter given more than once: "+name)
			return
		}
	}
	size := defaultPageSize
	if raw, given := c.GetQuery("page_size"); given {
		n, err := strconv.Atoi(raw)
		if err != nil || n < 1 || n > maxPageSize {
			fail(c, http.StatusBadRequest, "invalid_page_size", "page_size must be between 1 and 50")
			return
		}
		size = n
	}
	var after *Cursor
	if raw, given := c.GetQuery("page_token"); given {
		cursor, err := decodeCursor(raw)
		if err != nil {
			fail(c, http.StatusBadRequest, "invalid_page_token", "page_token is invalid")
			return
		}
		after = &cursor
	}
	page, err := h.store.Feed(c.Request.Context(), uid, after, size)
	if err != nil {
		fail(c, http.StatusServiceUnavailable, "unavailable", "Activity is temporarily unavailable")
		return
	}
	next := ""
	if page.Next != nil {
		next = encodeCursor(*page.Next)
	}
	items := page.Items
	if items == nil {
		items = []Item{}
	}
	c.JSON(http.StatusOK, gin.H{"items": items, "unread": page.Unread, "seen_at": page.SeenAt, "next_page_token": next})
}

func (h handlers) seen(c *gin.Context) {
	uid, ok := caller(c)
	if !ok {
		return
	}
	// No body is expected; anything sent is ignored rather than parsed.
	at, err := h.store.MarkSeen(c.Request.Context(), uid, time.Now())
	if err != nil {
		fail(c, http.StatusServiceUnavailable, "unavailable", "Activity is temporarily unavailable")
		return
	}
	c.JSON(http.StatusOK, gin.H{"seen_at": at, "unread": 0})
}

func encodeCursor(cursor Cursor) string {
	raw, _ := json.Marshal(cursor)
	return base64.RawURLEncoding.EncodeToString(raw)
}

func decodeCursor(token string) (Cursor, error) {
	var cursor Cursor
	if token == "" || len(token) > 200 {
		return cursor, errors.New("bad cursor")
	}
	raw, err := base64.RawURLEncoding.DecodeString(token)
	if err != nil {
		return cursor, err
	}
	if err := json.Unmarshal(raw, &cursor); err != nil {
		return cursor, err
	}
	if cursor.ID < 1 || cursor.At.IsZero() {
		return cursor, errors.New("incomplete cursor")
	}
	return cursor, nil
}
