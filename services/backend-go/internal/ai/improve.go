package ai

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"io"
	"log/slog"
	"net/http"
	"strings"
	"time"
	"unicode/utf8"

	"github.com/gin-gonic/gin"
)

const (
	maxPassageRunes = 4000
	minPassageProse = 20 // letters of prose; less is nothing to improve
	modelTimeout    = 30 * time.Second
)

var goals = map[string]bool{"clarity": true, "concise": true, "academic": true, "grammar": true, "flow": true}

type improveRequest struct {
	Text string `json:"text"`
	Goal string `json:"goal"`
}

type modelAnswer struct {
	Edits []Edit `json:"edits"`
	Model string `json:"model"`
}

// errModel is a model call that failed; status and retryAfter say how.
type errModel struct {
	status     int
	retryAfter string
}

func (e *errModel) Error() string { return "model call failed" }

func (h *handlers) improve(c *gin.Context) {
	uid, ok := caller(c)
	if !ok {
		return
	}
	ctx := c.Request.Context()
	var req improveRequest
	dec := json.NewDecoder(io.LimitReader(c.Request.Body, 64<<10))
	dec.DisallowUnknownFields()
	if err := dec.Decode(&req); err != nil {
		fail(c, http.StatusBadRequest, "invalid_body", "Send JSON with text and goal")
		return
	}
	switch {
	case !goals[req.Goal]:
		fail(c, http.StatusBadRequest, "invalid_goal", "goal must be clarity, concise, academic, grammar or flow")
		return
	case !utf8.ValidString(req.Text) || strings.ContainsRune(req.Text, 0):
		fail(c, http.StatusBadRequest, "invalid_text", "text must be UTF-8 without NUL characters")
		return
	case strings.TrimSpace(req.Text) == "":
		fail(c, http.StatusBadRequest, "invalid_text", "text must not be blank")
		return
	case utf8.RuneCountInString(req.Text) > maxPassageRunes:
		fail(c, http.StatusRequestEntityTooLarge, "passage_too_long", "Select at most 4,000 characters at a time")
		return
	}
	if !h.workspaceRole(c, uid, true) {
		return
	}
	if !h.Config.ImproveEnabled {
		countRequest(ctx, "improve", "unconfigured")
		fail(c, http.StatusServiceUnavailable, "ai_unconfigured", "The writing assistant is not set up yet")
		return
	}
	masked := Mask(req.Text)
	if prose(masked.Text) < minPassageProse {
		fail(c, http.StatusUnprocessableEntity, "nothing_to_improve", "Select a passage with some prose; math, citations and commands are left as they are")
		return
	}
	left, refund, ok := spend(c, h.Budgets, uid, "improve", h.Config.ImprovePerUser, modelKey, h.Config.ModelDaily)
	if !ok {
		countRequest(ctx, "improve", "over_budget")
		return
	}

	started := time.Now()
	answer, err := h.askModel(ctx, masked.Text, req.Goal)
	if err != nil {
		refund()
		var failed *errModel
		switch {
		case errors.As(err, &failed) && failed.status == http.StatusTooManyRequests:
			countProvider(ctx, "model", "busy", time.Since(started))
			countRequest(ctx, "improve", "busy")
			if failed.retryAfter != "" {
				c.Header("Retry-After", failed.retryAfter)
			}
			fail(c, http.StatusServiceUnavailable, "ai_busy", "The writing assistant is busy; try again in a moment")
		case errors.As(err, &failed) && failed.status == http.StatusBadGateway:
			countProvider(ctx, "model", "bad_output", time.Since(started))
			countRequest(ctx, "improve", "failed")
			fail(c, http.StatusBadGateway, "ai_failed", "The writing assistant gave an unusable answer; try again")
		default:
			countProvider(ctx, "model", "error", time.Since(started))
			countRequest(ctx, "improve", "failed")
			fail(c, http.StatusServiceUnavailable, "ai_unavailable", "The writing assistant is unavailable; try again shortly")
		}
		return
	}
	countProvider(ctx, "model", "ok", time.Since(started))
	suggestions, refused := Verify(masked, answer.Edits)
	countEdits(ctx, len(suggestions), refused)
	countRequest(ctx, "improve", "ok")
	if suggestions == nil {
		suggestions = []Suggestion{}
	}
	c.JSON(http.StatusOK, gin.H{
		"goal":        req.Goal,
		"suggestions": suggestions,
		"model":       answer.Model,
		"left_today":  left,
	})
}

// askModel calls the Python service's /api/v1/ai/improve.
func (h *handlers) askModel(ctx context.Context, masked, goal string) (modelAnswer, error) {
	body, _ := json.Marshal(map[string]string{"text": masked, "goal": goal})
	ctx, cancel := context.WithTimeout(ctx, modelTimeout)
	defer cancel()
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, h.PythonURL+"/api/v1/ai/improve", bytes.NewReader(body))
	if err != nil {
		return modelAnswer{}, err
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("X-Internal-Token", h.Token)
	resp, err := h.Client.Do(req)
	if err != nil {
		return modelAnswer{}, err
	}
	defer resp.Body.Close()
	data, err := io.ReadAll(io.LimitReader(resp.Body, 1<<20))
	if err != nil {
		return modelAnswer{}, err
	}
	switch {
	case resp.StatusCode == http.StatusOK:
	case resp.StatusCode == http.StatusTooManyRequests:
		return modelAnswer{}, &errModel{status: http.StatusTooManyRequests, retryAfter: resp.Header.Get("Retry-After")}
	case resp.StatusCode == http.StatusBadGateway:
		return modelAnswer{}, &errModel{status: http.StatusBadGateway}
	default:
		if resp.StatusCode == http.StatusForbidden || resp.StatusCode == http.StatusUnprocessableEntity {
			// The gateway's own credential or request was refused: a deploy problem.
			slog.Error("ai: model service refused the gateway", "status", resp.StatusCode)
		}
		return modelAnswer{}, &errModel{status: resp.StatusCode}
	}
	var answer modelAnswer
	if err := json.Unmarshal(data, &answer); err != nil {
		return modelAnswer{}, &errModel{status: http.StatusBadGateway}
	}
	return answer, nil
}

// workspaceRole checks the caller may use AI on the workspace in the path:
// any member for reading, owner or editor when write is set. It answers
// false after writing the refusal.
func (h *handlers) workspaceRole(c *gin.Context, uid string, write bool) bool {
	role, err := h.Reports.Role(c.Request.Context(), c.Param("id"), uid)
	switch {
	case errors.Is(err, ErrNotFound):
		fail(c, http.StatusNotFound, "not_found", "Workspace not found")
		return false
	case err != nil:
		fail(c, http.StatusServiceUnavailable, "unavailable", "AI features are temporarily unavailable")
		return false
	case write && role != "owner" && role != "editor":
		fail(c, http.StatusForbidden, "edit_forbidden", "Only the owner and editors can use AI on this document")
		return false
	}
	return true
}

// status: what each feature offers the caller today.
func (h *handlers) status(c *gin.Context) {
	uid, ok := caller(c)
	if !ok {
		return
	}
	ctx := c.Request.Context()
	// perUse is how much of the shared budget one use takes.
	feature := func(enabled bool, name string, perUser int, global string, globalLimit, perUse int) gin.H {
		left := 0
		if enabled {
			left = min(h.Budgets.Left(ctx, userKey(uid, name), perUser), h.Budgets.Left(ctx, global, globalLimit)/max(perUse, 1))
		}
		return gin.H{"available": enabled, "daily_limit": perUser, "left_today": left}
	}
	c.JSON(http.StatusOK, gin.H{
		"improve":     feature(h.Config.ImproveEnabled, "improve", h.Config.ImprovePerUser, modelKey, h.Config.ModelDaily, 1),
		"originality": feature(h.Config.OriginalityEnabled, "originality", h.Config.OriginalityPerUser, searchKey, h.Config.SearchDaily, h.Config.MaxProbes),
	})
}
