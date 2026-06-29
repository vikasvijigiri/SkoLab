package user

import (
	"context"
	"encoding/json"
	"log/slog"
	"net/http"
	"strings"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/skolab/backend-go/internal/cache"
	"github.com/skolab/backend-go/internal/db"
)

// memoryCache caches aggregated profiles for 5 minutes (TTL matches Python's L1/L2).
var memoryCache = cache.New(2 * time.Minute)

const memoryCacheTTL = 5 * time.Minute

// logRow is a single activity log entry returned from PostgreSQL.
type logRow struct {
	EventType string
	Meta      map[string]any
	CreatedAt time.Time
}

// ── Types ─────────────────────────────────────────────────────────────────────

type UserProfileSyncRequest struct {
	UID        string `json:"uid" binding:"required"`
	Name       string `json:"name" binding:"required"`
	Discipline string `json:"discipline"`
}

type ActivityEvent struct {
	Type              string  `json:"type"`
	Timestamp         *int64  `json:"timestamp"`
	HourOfDay         *int    `json:"hourOfDay"`
	PaperTitle        *string `json:"paperTitle"`
	PaperDomain       *string `json:"paperDomain"`
	PaperJournal      *string `json:"paperJournal"`
	DurationSeconds   *int    `json:"durationSeconds"`
	AuthorName        *string `json:"authorName"`
	AuthorInstitution *string `json:"authorInstitution"`
	Query             *string `json:"query"`
	FilterValue       *string `json:"filterValue"`
	CollaboratorName  *string `json:"collaboratorName"`
	CollaboratorField *string `json:"collaboratorField"`
}

type SyncEventsRequest struct {
	UserID string          `json:"user_id" binding:"required"`
	Events []ActivityEvent `json:"events"  binding:"required"`
}

type UserMemoryProfile struct {
	UserID                string   `json:"user_id"`
	TopTopics             []string `json:"top_topics"`
	ActiveHours           []int    `json:"active_hours"`
	ReadingPace           string   `json:"reading_pace"`
	ResearchStyle         string   `json:"research_style"`
	AvgReadMinutes        float64  `json:"avg_read_minutes"`
	UnfinishedPapers      []string `json:"unfinished_papers"`
	RecentlyReadPapers    []string `json:"recently_read_papers"`
	FrequentCollaborators []string `json:"frequent_collaborators"`
	FrequentSearchTerms   []string `json:"frequent_search_terms"`
	LastActiveTopic       string   `json:"last_active_topic"`
	TotalPapersRead       int      `json:"total_papers_read"`
	ResearcherBio         *string  `json:"researcher_bio"`
	LastUpdated           int64    `json:"last_updated"`
}

// ── User Profile Sync & Deletion Handlers ─────────────────────────────────────

// SyncUserProfile handles profile creation and triggers background AI enrichment
func SyncUserProfile(c *gin.Context) {
	var req UserProfileSyncRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	// Upsert the user into PostgreSQL natively in Go
	query := `
		INSERT INTO users (id, display_name) 
		VALUES ($1, $2) 
		ON CONFLICT (id) DO UPDATE SET display_name = $2
	`
	_, err := db.Pool.Exec(context.Background(), query, req.UID, req.Name)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "Failed to sync user to database"})
		return
	}

	c.JSON(http.StatusOK, gin.H{
		"status": "syncing",
		"uid":    req.UID,
	})
}

// DeleteUser handles GDPR Right to be Forgotten
func DeleteUser(c *gin.Context) {
	targetUserID := c.Param("userId")
	tokenUserID := c.GetString("user_id")

	if targetUserID != tokenUserID {
		c.JSON(http.StatusForbidden, gin.H{"error": "Forbidden: you may only delete your own account."})
		return
	}

	// Delete user records natively
	tx, err := db.Pool.Begin(context.Background())
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "Database error"})
		return
	}
	defer tx.Rollback(context.Background())

	// Anonymize Activity Logs
	_, _ = tx.Exec(context.Background(), "UPDATE user_activity_logs SET user_id = NULL WHERE user_id = $1", targetUserID)
	// Delete User Preferences
	_, _ = tx.Exec(context.Background(), "DELETE FROM user_preferences WHERE user_id = $1", targetUserID)
	// Delete User Profile
	_, _ = tx.Exec(context.Background(), "DELETE FROM users WHERE id = $1", targetUserID)

	if err := tx.Commit(context.Background()); err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "Failed to commit deletion"})
		return
	}

	c.JSON(http.StatusOK, gin.H{"status": "success", "detail": "Account deleted successfully and telemetry anonymized."})
}

// ── User Memory Handlers ──────────────────────────────────────────────────────

// SyncUserMemoryEvents ingests a batch of activity events into PostgreSQL.
func SyncUserMemoryEvents(c *gin.Context) {
	var req SyncEventsRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}
	if len(req.Events) == 0 {
		c.JSON(http.StatusOK, gin.H{"status": "success", "synced": 0})
		return
	}

	if db.Pool == nil {
		c.JSON(http.StatusServiceUnavailable, gin.H{"error": "database unavailable"})
		return
	}

	ctx, cancel := context.WithTimeout(c.Request.Context(), 10*time.Second)
	defer cancel()

	rows := make([][]any, 0, len(req.Events))
	for _, ev := range req.Events {
		entityID := firstNonEmpty(ev.PaperTitle, ev.AuthorName, ev.Query, ev.FilterValue, ev.CollaboratorName)
		entityName := firstNonEmpty(ev.PaperDomain, ev.AuthorInstitution, ev.CollaboratorField, ev.PaperJournal)

		metaBytes, _ := json.Marshal(ev)

		createdAt := time.Now().UTC()
		if ev.Timestamp != nil {
			createdAt = time.UnixMilli(*ev.Timestamp).UTC()
		}

		rows = append(rows, []any{req.UserID, ev.Type, entityID, entityName, string(metaBytes), createdAt})
	}

	_, err := db.Pool.Exec(ctx, `
		CREATE TEMP TABLE IF NOT EXISTS _evt_batch (
			user_id TEXT, event_type TEXT, entity_id TEXT,
			entity_name TEXT, event_metadata JSONB, created_at TIMESTAMPTZ
		) ON COMMIT DROP
	`)
	if err != nil {
		if err := insertEventsOneByOne(ctx, req.UserID, rows); err != nil {
			slog.Error("memory sync failed", "user", req.UserID, "err", err)
			c.JSON(http.StatusInternalServerError, gin.H{"error": "database synchronisation failed"})
			return
		}
	} else {
		if err := insertEventsCopyFrom(ctx, rows); err != nil {
			if err2 := insertEventsOneByOne(ctx, req.UserID, rows); err2 != nil {
				slog.Error("memory sync failed (both paths)", "user", req.UserID, "err", err2)
				c.JSON(http.StatusInternalServerError, gin.H{"error": "database synchronisation failed"})
				return
			}
		}
	}

	memoryCache.Delete(req.UserID)

	c.JSON(http.StatusOK, gin.H{"status": "success", "synced": len(rows)})
}

func insertEventsOneByOne(ctx context.Context, _ string, rows [][]any) error {
	const q = `
		INSERT INTO user_activity_logs (user_id, event_type, entity_id, entity_name, event_metadata, created_at)
		VALUES ($1, $2, $3, $4, $5::jsonb, $6)
	`
	for _, r := range rows {
		if _, err := db.Pool.Exec(ctx, q, r...); err != nil {
			return err
		}
	}
	return nil
}

func insertEventsCopyFrom(ctx context.Context, rows [][]any) error {
	const q = `
		INSERT INTO user_activity_logs (user_id, event_type, entity_id, entity_name, event_metadata, created_at)
		SELECT user_id, event_type, entity_id, entity_name, event_metadata::jsonb, created_at FROM _evt_batch
	`
	cols := []string{"user_id", "event_type", "entity_id", "entity_name", "event_metadata", "created_at"}
	typed := make([][]any, len(rows))
	copy(typed, rows)

	_, err := db.Pool.CopyFrom(ctx,
		[]string{"_evt_batch"},
		cols,
		newRowSource(typed),
	)
	if err != nil {
		return err
	}
	_, err = db.Pool.Exec(ctx, q)
	return err
}

// GetUserMemory aggregates activity logs into a researcher profile.
func GetUserMemory(c *gin.Context) {
	userID := c.Param("userId")
	if userID == "" {
		c.JSON(http.StatusBadRequest, gin.H{"error": "userId is required"})
		return
	}

	if cached, ok := memoryCache.Get(userID); ok {
		c.JSON(http.StatusOK, cached)
		return
	}

	if db.Pool == nil {
		c.JSON(http.StatusServiceUnavailable, gin.H{"error": "database unavailable"})
		return
	}

	ctx, cancel := context.WithTimeout(c.Request.Context(), 15*time.Second)
	defer cancel()

	rows, err := db.Pool.Query(ctx, `
		SELECT event_type, event_metadata, created_at
		FROM user_activity_logs
		WHERE user_id = $1
		ORDER BY created_at ASC
	`, userID)
	if err != nil {
		slog.Error("memory query failed", "user", userID, "err", err)
		c.JSON(http.StatusInternalServerError, gin.H{"error": "failed to load user memory"})
		return
	}
	defer rows.Close()

	var logs []logRow
	for rows.Next() {
		var lr logRow
		var metaJSON []byte
		if err := rows.Scan(&lr.EventType, &metaJSON, &lr.CreatedAt); err != nil {
			continue
		}
		if metaJSON != nil {
			_ = json.Unmarshal(metaJSON, &lr.Meta)
		}
		logs = append(logs, lr)
	}
	if rows.Err() != nil {
		slog.Warn("memory rows error", "user", userID, "err", rows.Err())
	}

	profile := aggregateMemory(userID, logs)

	memoryCache.Set(userID, profile, memoryCacheTTL)
	c.JSON(http.StatusOK, profile)
}

func aggregateMemory(userID string, logs []logRow) UserMemoryProfile {
	if len(logs) == 0 {
		return UserMemoryProfile{
			UserID:      userID,
			LastUpdated: time.Now().UnixMilli(),
		}
	}

	topTopicsCtr := make(map[string]int)
	activeHoursCtr := make(map[int]int)
	collaboratorsCtr := make(map[string]int)
	searchTermsCtr := make(map[string]int)

	type paperSession struct {
		title    string
		duration int
	}
	var paperSessions []paperSession
	distinctDomains := make(map[string]bool)

	for _, l := range logs {
		m := l.Meta

		if domain := strVal(m, "paperDomain"); len(domain) > 3 {
			topTopicsCtr[strings.Title(domain)]++ //nolint:staticcheck
			distinctDomains[domain] = true
		}
		if q := strVal(m, "query"); len(q) > 3 {
			searchTermsCtr[q]++
			topTopicsCtr[strings.Title(q)]++
		}
		if fv := strVal(m, "filterValue"); len(fv) > 3 {
			topTopicsCtr[strings.Title(fv)]++
		}
		if hour := intVal(m, "hourOfDay"); hour >= 0 {
			activeHoursCtr[hour]++
		}
		if l.EventType == "PAPER_CLOSED" {
			title := strVal(m, "paperTitle")
			dur := intVal(m, "durationSeconds")
			if title != "" {
				paperSessions = append(paperSessions, paperSession{title: title, duration: dur})
			}
		}
		collab := firstStrVal(m, "collaboratorName", "authorName")
		if len(collab) > 2 {
			collaboratorsCtr[collab]++
		}
	}

	topTopics := topN(topTopicsCtr, 6)
	activeHours := topNInt(activeHoursCtr, 4)
	collaborators := topN(collaboratorsCtr, 5)
	searchTerms := topN(searchTermsCtr, 5)

	var (
		avgReadMinutes  float64
		readingPace     = "unknown"
		unfinished      []string
		recentlyRead    []string
		totalPapersRead int
	)

	if len(paperSessions) > 0 {
		sum := 0
		for _, ps := range paperSessions {
			sum += ps.duration
		}
		avgReadSec := float64(sum) / float64(len(paperSessions))
		avgReadMinutes = avgReadSec / 60.0

		switch {
		case avgReadMinutes >= 5.0:
			readingPace = "deep_reader"
		case avgReadMinutes >= 2.0:
			readingPace = "moderate_reader"
		case avgReadMinutes > 0.0:
			readingPace = "quick_scanner"
		}

		for _, ps := range paperSessions {
			if ps.duration >= 1 && ps.duration < 90 {
				unfinished = append(unfinished, ps.title)
			}
			if ps.duration >= 90 {
				recentlyRead = append(recentlyRead, ps.title)
				totalPapersRead++
			}
		}
		unfinished = last(unfinished, 3)
		recentlyRead = last(recentlyRead, 5)
	}

	researchStyle := "exploratory"
	switch {
	case len(distinctDomains) >= 4:
		researchStyle = "interdisciplinary"
	case len(distinctDomains) >= 2:
		researchStyle = "focused"
	}

	lastActiveTopic := ""
	if len(topTopics) > 0 {
		lastActiveTopic = topTopics[0]
	}

	return UserMemoryProfile{
		UserID:                userID,
		TopTopics:             topTopics,
		ActiveHours:           activeHours,
		ReadingPace:           readingPace,
		ResearchStyle:         researchStyle,
		AvgReadMinutes:        round2(avgReadMinutes),
		UnfinishedPapers:      unfinished,
		RecentlyReadPapers:    recentlyRead,
		FrequentCollaborators: collaborators,
		FrequentSearchTerms:   searchTerms,
		LastActiveTopic:       lastActiveTopic,
		TotalPapersRead:       totalPapersRead,
		ResearcherBio:         nil,
		LastUpdated:           time.Now().UnixMilli(),
	}
}

// ── Utility helpers ───────────────────────────────────────────────────────────

func strVal(m map[string]any, key string) string {
	if m == nil {
		return ""
	}
	v, _ := m[key].(string)
	return v
}

func intVal(m map[string]any, key string) int {
	if m == nil {
		return -1
	}
	switch v := m[key].(type) {
	case float64:
		return int(v)
	case int:
		return v
	}
	return -1
}

func firstStrVal(m map[string]any, keys ...string) string {
	for _, k := range keys {
		if v := strVal(m, k); v != "" {
			return v
		}
	}
	return ""
}

func firstNonEmpty(ptrs ...*string) string {
	for _, p := range ptrs {
		if p != nil && *p != "" {
			return *p
		}
	}
	return ""
}

func topN(m map[string]int, n int) []string {
	type kv struct {
		k string
		v int
	}
	var pairs []kv
	for k, v := range m {
		pairs = append(pairs, kv{k, v})
	}
	for i := 0; i < len(pairs) && i < n; i++ {
		maxIdx := i
		for j := i + 1; j < len(pairs); j++ {
			if pairs[j].v > pairs[maxIdx].v {
				maxIdx = j
			}
		}
		pairs[i], pairs[maxIdx] = pairs[maxIdx], pairs[i]
	}
	out := make([]string, 0, n)
	for i := 0; i < len(pairs) && i < n; i++ {
		out = append(out, pairs[i].k)
	}
	return out
}

func topNInt(m map[int]int, n int) []int {
	type kv struct {
		k int
		v int
	}
	var pairs []kv
	for k, v := range m {
		pairs = append(pairs, kv{k, v})
	}
	for i := 0; i < len(pairs) && i < n; i++ {
		maxIdx := i
		for j := i + 1; j < len(pairs); j++ {
			if pairs[j].v > pairs[maxIdx].v {
				maxIdx = j
			}
		}
		pairs[i], pairs[maxIdx] = pairs[maxIdx], pairs[i]
	}
	out := make([]int, 0, n)
	for i := 0; i < len(pairs) && i < n; i++ {
		out = append(out, pairs[i].k)
	}
	return out
}

func last(s []string, n int) []string {
	if len(s) <= n {
		return s
	}
	return s[len(s)-n:]
}

func round2(v float64) float64 {
	if v == 0 {
		return 0
	}
	return float64(int(v*100+0.5)) / 100
}

// rowSource satisfies pgx's CopyFromSource interface.
type rowSource struct {
	rows [][]any
	idx  int
}

func newRowSource(rows [][]any) *rowSource  { return &rowSource{rows: rows} }
func (r *rowSource) Next() bool             { r.idx++; return r.idx <= len(r.rows) }
func (r *rowSource) Values() ([]any, error) { return r.rows[r.idx-1], nil }
func (r *rowSource) Err() error             { return nil }
