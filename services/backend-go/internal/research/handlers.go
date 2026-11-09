package research

import (
	"bytes"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"strconv"
	"strings"
	"unicode/utf8"

	"github.com/gin-gonic/gin"
	"github.com/skolab/backend-go/internal/apierror"
)

const maxItems = 500
const maxFieldRunes = 10_000

var errBodyTooLarge = errors.New("research request exceeds 1 MiB")

type ConflictError struct{ Current int }

func (e *ConflictError) Error() string { return "research notes changed" }

func Register(group *gin.RouterGroup, store Store) {
	h := handlers{store: store}
	group.GET("/workspaces/:id/research", h.get)
	group.PUT("/workspaces/:id/research", h.put)
}

type handlers struct{ store Store }

func (h handlers) get(c *gin.Context) {
	uid, ok := caller(c)
	if !ok {
		return
	}
	doc, err := h.store.Get(c.Request.Context(), c.Param("id"), uid)
	if err != nil {
		h.writeError(c, err)
		return
	}
	c.JSON(http.StatusOK, doc)
}

func (h handlers) put(c *gin.Context) {
	uid, ok := caller(c)
	if !ok {
		return
	}
	var req struct {
		BaseVersion     int              `json:"base_version"`
		Items           *[]Item          `json:"items"`
		Reproducibility *Reproducibility `json:"reproducibility"`
	}
	if err := bindStrict(c, &req); err != nil {
		if errors.Is(err, errBodyTooLarge) {
			fail(c, http.StatusRequestEntityTooLarge, "body_too_large", "Research notes may be at most 1 MiB")
			return
		}
		fail(c, http.StatusBadRequest, "invalid_body", "Request body must contain valid research notes")
		return
	}
	if req.BaseVersion < 0 || req.Items == nil || req.Reproducibility == nil || !valid(*req.Items, *req.Reproducibility) {
		fail(c, http.StatusBadRequest, "invalid_research", "Research notes contain invalid or oversized fields")
		return
	}
	doc, err := h.store.Save(c.Request.Context(), c.Param("id"), uid, Document{Items: *req.Items, Reproducibility: *req.Reproducibility}, req.BaseVersion)
	if err != nil {
		h.writeError(c, err)
		return
	}
	c.Header("ETag", `"v`+itoa(doc.Version)+`"`)
	c.JSON(http.StatusOK, doc)
}

func valid(items []Item, r Reproducibility) bool {
	if items == nil || len(items) > maxItems {
		return false
	}
	seen := make(map[string]struct{}, len(items))
	for _, item := range items {
		if strings.TrimSpace(item.ID) == "" || len(item.ID) > 80 || !oneOf(item.Status, "to_review", "supported_by_author", "needs_revision") {
			return false
		}
		if _, exists := seen[item.ID]; exists {
			return false
		}
		seen[item.ID] = struct{}{}
		if !bounded(item.Claim, item.CitationKey, item.Quote, item.Locator, item.StudyType, item.Sample, item.Findings, item.Limitations, item.Relevance) {
			return false
		}
		if strings.TrimSpace(item.CitationKey) == "" || strings.TrimSpace(item.Claim) == "" {
			return false
		}
	}
	return bounded(r.DataAvailability, r.CodeAvailability, r.Software, r.AnalysisSteps, r.Preregistration, r.Notes)
}
func bounded(values ...string) bool {
	for _, s := range values {
		if !utf8.ValidString(s) || utf8.RuneCountInString(s) > maxFieldRunes {
			return false
		}
	}
	return true
}
func oneOf(s string, values ...string) bool {
	for _, v := range values {
		if s == v {
			return true
		}
	}
	return false
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
func bindStrict(c *gin.Context, dst any) error {
	data, err := io.ReadAll(io.LimitReader(c.Request.Body, 1<<20+1))
	if len(data) > 1<<20 {
		return errBodyTooLarge
	}
	if err != nil {
		return err
	}
	decoder := json.NewDecoder(bytes.NewReader(data))
	decoder.DisallowUnknownFields()
	if err := decoder.Decode(dst); err != nil {
		return err
	}
	if decoder.Decode(new(any)) != io.EOF {
		return errors.New("trailing JSON")
	}
	return nil
}
func itoa(i int) string                                     { return strconv.Itoa(i) }
func fail(c *gin.Context, status int, code, message string) { apierror.Abort(c, status, code, message) }
func (h handlers) writeError(c *gin.Context, err error) {
	var conflict *ConflictError
	switch {
	case errors.As(err, &conflict):
		c.Header("ETag", `"v`+strconv.Itoa(conflict.Current)+`"`)
		apierror.AbortWith(c, http.StatusConflict, "version_conflict", "Research notes changed since you opened them. Reload before saving.", gin.H{"current_version": conflict.Current})
	case errors.Is(err, ErrNotFound):
		fail(c, http.StatusNotFound, "not_found", "Not found, or you do not have access to it")
	case errors.Is(err, ErrReadOnly):
		fail(c, http.StatusForbidden, "edit_forbidden", "Only the workspace owner or an editor may change research notes")
	default:
		fail(c, http.StatusServiceUnavailable, "research_unavailable", "Research notes are temporarily unavailable")
	}
}
