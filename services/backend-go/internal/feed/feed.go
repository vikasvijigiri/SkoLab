// Package feed serves the non-LLM feed-persistence and stub-integration
// endpoints moved off the Python backend in Phase 2 of the "Python is LLM-only"
// migration (decisions/0002; docs/plans/2026-09-04-phase2-feed-to-go.md).
//
// What lives here is pure data / static response — no LLM call, no embeddings:
//   - POST /api/v1/daily_feed/dismiss         (owner-checked write to cache_entries)
//   - GET  /api/v1/support/metrics            (constant dict)
//   - GET  /api/v1/integrations/zotero/auth   (OAuth stub)
//   - GET  /api/v1/integrations/zotero/callback (OAuth stub)
//   - POST /api/v1/integrations/zotero/sync   (echo stub)
//
// Feed *generation* (GET /api/v1/daily_feed and the daily_conjecture / roadmap /
// industry LLM routes) stays in services/backend and is reached through the
// gateway's NoRoute proxy.
package feed

import (
	"context"
	"encoding/json"
	"errors"
	"log/slog"
	"net/http"
	"strings"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/jackc/pgx/v5"
	"github.com/skolab/backend-go/internal/db"
)

// dismissedRecsTTL mirrors _pg_dismissed_recs_cache in
// services/backend/app/services/platform/pipeline/text_utils.py: an effectively
// permanent (10-year) TTL so a rejected paper does not quietly expire back into
// the feed.
const dismissedRecsTTL = 315360000 * time.Second

// cacheEntryEnvelope is the JSON shape PgBackedCache writes into
// cache_entries.data — {"v": <value>}. For the dismissed-recs key the value is a
// list of OpenAlex work ids.
type cacheEntryEnvelope struct {
	V []string `json:"v"`
}

// ── POST /api/v1/daily_feed/dismiss ──────────────────────────────────────────

type dismissRequest struct {
	AuthorID string `json:"author_id" binding:"required"`
	WorkID   string `json:"work_id"   binding:"required"`
}

// DismissDailyFeedItem records that the caller has dismissed a paper from their
// own daily feed. Port of dismiss_daily_feed_item (Python endpoint) +
// PipelineServices.dismiss_recommendation.
//
// The route MUST be mounted behind auth.VerifyUser() so a missing/invalid token
// is a 401 before this runs. Ownership: the verified Firebase uid's linked
// users.openalex_id must equal the body author_id (an OpenAlex id, not a uid) —
// otherwise 403, matching the Python owner check exactly (401 no token, 403 when
// the token's user is not that OpenAlex author, incl. an unlinked account).
func DismissDailyFeedItem(c *gin.Context) {
	var req dismissRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		// FastAPI returned 422 {"code":"validation_error"} here; Gin's binding
		// gives 400. No client depends on the 422 — see the plan's deferred note.
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	uid := c.GetString("user_id")
	if uid == "" {
		// VerifyUser() should have aborted already; defensive.
		c.JSON(http.StatusUnauthorized, gin.H{"error": "authentication required"})
		return
	}

	if db.Pool == nil {
		c.JSON(http.StatusServiceUnavailable, gin.H{"error": "database unavailable"})
		return
	}

	ctx, cancel := context.WithTimeout(c.Request.Context(), 10*time.Second)
	defer cancel()

	// Owner check: uid -> users.openalex_id, compared to the body author_id.
	// scalar_one_or_none() in Python is None for "no row" and for a NULL
	// openalex_id; both -> 403 here.
	var linkedOpenAlexID *string
	err := db.Pool.QueryRow(ctx, `SELECT openalex_id FROM users WHERE id = $1`, uid).Scan(&linkedOpenAlexID)
	if err != nil && !errors.Is(err, pgx.ErrNoRows) {
		slog.Error("dismiss: owner lookup failed", "uid", uid, "err", err)
		c.JSON(http.StatusInternalServerError, gin.H{"error": "owner check failed"})
		return
	}
	if linkedOpenAlexID == nil || *linkedOpenAlexID != req.AuthorID {
		c.JSON(http.StatusForbidden, gin.H{"detail": "You can only dismiss items from your own feed."})
		return
	}

	docID := lastPathSegment(req.AuthorID)
	dismissedKey := "pipeline_dismissed_recs::" + docID
	now := time.Now().UTC()

	// Read the existing dismissed-ids list (expires_at > now, matching
	// PgBackedCache.get). Missing row -> empty list.
	ids := []string{}
	var raw []byte
	rerr := db.Pool.QueryRow(ctx,
		`SELECT data FROM cache_entries WHERE cache_key = $1 AND expires_at > $2`,
		dismissedKey, now,
	).Scan(&raw)
	if rerr == nil && len(raw) > 0 {
		var env cacheEntryEnvelope
		if json.Unmarshal(raw, &env) == nil && env.V != nil {
			ids = env.V
		}
	} else if rerr != nil && !errors.Is(rerr, pgx.ErrNoRows) {
		slog.Warn("dismiss: dismissed-recs read failed, treating as empty", "key", dismissedKey, "err", rerr)
	}

	if !contains(ids, req.WorkID) {
		ids = append(ids, req.WorkID)
	}

	envBytes, err := json.Marshal(cacheEntryEnvelope{V: ids})
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "encode failed"})
		return
	}

	_, err = db.Pool.Exec(ctx, `
		INSERT INTO cache_entries (cache_key, data, last_synced, expires_at)
		VALUES ($1, $2::json, $3, $4)
		ON CONFLICT (cache_key) DO UPDATE SET
			data = EXCLUDED.data,
			last_synced = EXCLUDED.last_synced,
			expires_at = EXCLUDED.expires_at
	`, dismissedKey, string(envBytes), now, now.Add(dismissedRecsTTL))
	if err != nil {
		slog.Error("dismiss: dismissed-recs upsert failed", "key", dismissedKey, "err", err)
		c.JSON(http.StatusInternalServerError, gin.H{"error": "failed to record dismissal"})
		return
	}

	// Best-effort feed-cache invalidation (PgBackedCache(name="pipeline").delete
	// of daily_feed_<doc_id>). Not fatal if it fails — get_daily_feed re-checks
	// the dismissed-ids list against any cached feed.
	if _, derr := db.Pool.Exec(ctx,
		`DELETE FROM cache_entries WHERE cache_key = $1`,
		"pipeline::daily_feed_"+docID,
	); derr != nil {
		slog.Warn("dismiss: feed-cache invalidation failed", "doc_id", docID, "err", derr)
	}

	c.JSON(http.StatusOK, gin.H{"success": true})
}

// ── GET /api/v1/support/metrics ──────────────────────────────────────────────

// GetSupportMetrics returns the static support-dashboard counters. Byte-for-byte
// the dict from services/backend/app/api/v1/endpoints/support.py.
func GetSupportMetrics(c *gin.Context) {
	c.JSON(http.StatusOK, gin.H{
		"sla_targets": gin.H{
			"vip_first_response_minutes":    15,
			"standard_first_response_hours": 4,
			"vip_resolution_hours":          2,
			"standard_resolution_hours":     24,
		},
		"performance_metrics": gin.H{
			"average_first_response_time_minutes":      0.0,
			"average_resolution_time_hours":            0.0,
			"customer_satisfaction_score_csat_percent": 98.4,
		},
		"queue_status": gin.H{
			"total_open_tickets":         0,
			"vip_escalation_queue_size":  0,
			"standard_queue_size":        0,
			"zendesk_integration_status": "operational",
		},
	})
}

// ── GET /api/v1/integrations/zotero/auth ─────────────────────────────────────

// ZoteroAuthInit returns the mock Zotero OAuth authorization URL. Stub — no
// state, no DB. `user_id` is a required query param (FastAPI Query(...)).
func ZoteroAuthInit(c *gin.Context) {
	userID := c.Query("user_id")
	if userID == "" {
		c.JSON(http.StatusUnprocessableEntity, gin.H{"error": "user_id query parameter is required"})
		return
	}
	oauthURL := "https://www.zotero.org/oauth/authorize?oauth_token=mock_token_skolab_" +
		userID + "&client_id=skolab_client"
	c.JSON(http.StatusOK, gin.H{"authorization_url": oauthURL})
}

// ── GET /api/v1/integrations/zotero/callback ─────────────────────────────────

// ZoteroAuthCallback returns the fixed "linked" response. Stub. `oauth_token`
// and `oauth_verifier` are required query params (FastAPI Query(...)).
func ZoteroAuthCallback(c *gin.Context) {
	if c.Query("oauth_token") == "" || c.Query("oauth_verifier") == "" {
		c.JSON(http.StatusUnprocessableEntity, gin.H{"error": "oauth_token and oauth_verifier query parameters are required"})
		return
	}
	c.JSON(http.StatusOK, gin.H{
		"status":          "success",
		"message":         "Zotero account linked successfully!",
		"zotero_user_id":  "8765432",
		"zotero_username": "skolab_researcher",
	})
}

// ── POST /api/v1/integrations/zotero/sync ────────────────────────────────────

type zoteroSyncRequest struct {
	UserID string           `json:"user_id"`
	Papers []map[string]any `json:"papers"`
}

// ZoteroSyncPapers echoes the posted paper titles back. Stub — no DB, no Zotero
// call. Mirrors zotero_sync_papers: title defaults to "Untitled Paper".
func ZoteroSyncPapers(c *gin.Context) {
	var req zoteroSyncRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}
	titles := make([]string, 0, len(req.Papers))
	for _, p := range req.Papers {
		title := "Untitled Paper"
		if t, ok := p["title"].(string); ok && t != "" {
			title = t
		}
		titles = append(titles, title)
	}
	c.JSON(http.StatusOK, gin.H{
		"status":        "success",
		"synced_count":  len(titles),
		"synced_papers": titles,
		"message":       "Vault papers synced to desktop Zotero library successfully!",
	})
}

// ── helpers ─────────────────────────────────────────────────────────────────

// lastPathSegment mirrors Python's `s.split("/")[-1]`: everything after the last
// "/", or the whole string when there is none.
func lastPathSegment(s string) string {
	if i := strings.LastIndex(s, "/"); i >= 0 {
		return s[i+1:]
	}
	return s
}

func contains(xs []string, x string) bool {
	for _, v := range xs {
		if v == x {
			return true
		}
	}
	return false
}
