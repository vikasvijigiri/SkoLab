// Package document stores the LaTeX source behind a CoLab workspace, one
// main.tex per workspace, so a manuscript follows its author between
// browsers and is shared with the workspace's members:
//
//	GET /api/v1/workspaces/:id/document  any active member -> 200
//	PUT /api/v1/workspaces/:id/document  owner or editor   -> 200
//
// A workspace that has never been saved reads as version 0 with an empty
// source. Every save names the version it was based on (base_version); a
// save based on anything but the current version is refused with 409 and
// the current version, so one editor never silently overwrites another.
// Access follows the workspace rules (internal/workspace): a workspace the
// caller cannot see is 404, never 403, so IDs cannot be probed.
package document

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"math"
	"net/http"
	"strconv"
	"strings"
	"time"
	"unicode"
	"unicode/utf8"

	"github.com/gin-gonic/gin"
	"github.com/skolab/backend-go/internal/apierror"
	"github.com/skolab/backend-go/internal/security"
)

// MaxSourceRunes matches the compiler's limit (latex_source max_length) and
// the workspace_documents.source CHECK constraint.
const MaxSourceRunes = 100_000

const maxTemplateIDLength = 64 // workspace_documents.template_id is VARCHAR(64)

var (
	ErrNotFound    = errors.New("workspace not found")
	ErrReadOnly    = errors.New("only the owner or an editor may edit")
	ErrUnavailable = errors.New("document store unavailable")
)

// ConflictError reports that the document moved on since base_version.
type ConflictError struct{ Current int }

func (e *ConflictError) Error() string {
	return fmt.Sprintf("document is at version %d", e.Current)
}

type Document struct {
	WorkspaceID string     `json:"workspace_id"`
	Source      string     `json:"source"`
	TemplateID  *string    `json:"template_id"`
	Version     int        `json:"version"`
	UpdatedAt   *time.Time `json:"updated_at"`
	UpdatedBy   *string    `json:"updated_by"`
	Role        string     `json:"role"` // the caller's role, so a client knows whether it may save
}

// Store is narrow so handlers are tested without PostgreSQL.
type Store interface {
	Get(ctx context.Context, workspaceID, userID string) (Document, error)
	// Save writes source when the stored version equals baseVersion (0 for a
	// document never saved). templateID is kept as is when nil.
	Save(ctx context.Context, workspaceID, userID, source string, templateID *string, baseVersion int) (Document, error)
}

// Register mounts the routes on a group already protected by auth.VerifyUser().
// knownTemplate reports whether a template_id names a template in the catalog.
func Register(group *gin.RouterGroup, store Store, knownTemplate func(string) bool) {
	h := handlers{store: store, knownTemplate: knownTemplate}
	group.GET("/workspaces/:id/document", h.get)
	group.PUT("/workspaces/:id/document", h.put)
}

type handlers struct {
	store         Store
	knownTemplate func(string) bool
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

func etag(version int) string { return `"v` + strconv.Itoa(version) + `"` }

func storeError(c *gin.Context, err error) {
	var conflict *ConflictError
	switch {
	case errors.As(err, &conflict):
		c.Header("ETag", etag(conflict.Current))
		apierror.AbortWith(c, http.StatusConflict, "version_conflict",
			"The document was changed since you opened it. Reload it to see the latest version.",
			gin.H{"current_version": conflict.Current})
	case errors.Is(err, ErrNotFound):
		security.Record(c, security.Event{Name: security.WorkspaceDenied, Outcome: security.Denied, WorkspaceID: c.Param("id"), Reason: c.Request.Method})
		fail(c, http.StatusNotFound, "not_found", "Not found, or you do not have access to it")
	case errors.Is(err, ErrReadOnly):
		security.Record(c, security.Event{Name: security.WorkspaceDenied, Outcome: security.Denied, WorkspaceID: c.Param("id"), Reason: c.Request.Method})
		fail(c, http.StatusForbidden, "edit_forbidden", "Only the workspace owner or an editor may change the document")
	default:
		fail(c, http.StatusServiceUnavailable, "unavailable", "Documents are temporarily unavailable")
	}
}

func (h handlers) get(c *gin.Context) {
	uid, ok := caller(c)
	if !ok {
		return
	}
	doc, err := h.store.Get(c.Request.Context(), c.Param("id"), uid)
	if err != nil {
		storeError(c, err)
		return
	}
	c.Header("ETag", etag(doc.Version))
	c.JSON(http.StatusOK, doc)
}

type saveRequest struct {
	Source      *string `json:"source"`
	BaseVersion *int    `json:"base_version"`
	TemplateID  *string `json:"template_id"`
}

// validSource allows what a .tex file holds: any printable text plus tab,
// newline and carriage return. Other control characters (NUL above all)
// have no place in LaTeX source and Postgres text refuses NUL outright.
func validSource(s string) bool {
	return utf8.ValidString(s) && strings.IndexFunc(s, func(r rune) bool {
		return unicode.IsControl(r) && r != '\t' && r != '\n' && r != '\r'
	}) < 0
}

func (h handlers) put(c *gin.Context) {
	uid, ok := caller(c)
	if !ok {
		return
	}
	var req saveRequest
	decoder := json.NewDecoder(c.Request.Body)
	decoder.DisallowUnknownFields() // a misspelt field must not be dropped silently
	if err := decoder.Decode(&req); err != nil || decoder.More() {
		var tooLarge *http.MaxBytesError
		if errors.As(err, &tooLarge) {
			fail(c, http.StatusRequestEntityTooLarge, "document_too_large", "The document must be at most 100,000 characters")
			return
		}
		fail(c, http.StatusBadRequest, "invalid_body", "Request body must be JSON with source and base_version")
		return
	}
	switch {
	case req.Source == nil || req.BaseVersion == nil:
		fail(c, http.StatusBadRequest, "invalid_body", "Request body must be JSON with source and base_version")
		return
	case *req.BaseVersion < 0 || *req.BaseVersion > math.MaxInt32: // workspace_documents.version is INTEGER
		fail(c, http.StatusBadRequest, "invalid_base_version", "base_version must be between 0 and 2147483647")
		return
	case utf8.RuneCountInString(*req.Source) > MaxSourceRunes:
		fail(c, http.StatusRequestEntityTooLarge, "document_too_large", "The document must be at most 100,000 characters")
		return
	case !validSource(*req.Source):
		fail(c, http.StatusBadRequest, "invalid_source", "The document contains characters LaTeX source cannot hold")
		return
	}
	if req.TemplateID != nil {
		id := *req.TemplateID
		if id == "" || len(id) > maxTemplateIDLength || !h.knownTemplate(id) {
			fail(c, http.StatusBadRequest, "unknown_template", "template_id does not name a template in the catalog")
			return
		}
	}
	doc, err := h.store.Save(c.Request.Context(), c.Param("id"), uid, *req.Source, req.TemplateID, *req.BaseVersion)
	if err != nil {
		storeError(c, err)
		return
	}
	c.Header("ETag", etag(doc.Version))
	c.JSON(http.StatusOK, doc)
}
