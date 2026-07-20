package quest

import (
	"context"
	"encoding/json"
	"log/slog"
	"net/http"
	"strings"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/skolab/backend-go/internal/db"
)

// ── Types ─────────────────────────────────────────────────────────────────────

type Quest struct {
	ID            string `json:"id"`
	Title         string `json:"title"`
	RewardEntropy int    `json:"reward_entropy"`
	IsCompleted   bool   `json:"is_completed"`
}

type LeaderboardEntry struct {
	Rank         int    `json:"rank"`
	ID           string `json:"id"`
	UserName     string `json:"user_name"`
	Institution  string `json:"institution"`
	EntropyScore int    `json:"entropy_score"`
}

// ── Leaderboard ───────────────────────────────────────────────────────────────

// GetLeaderboard handles GET /api/v1/leaderboard/:field
// Queries PostgreSQL researcher_metrics ordered by innovation_score.
func GetLeaderboard(c *gin.Context) {
	field := c.Param("field")

	if db.Pool == nil {
		c.JSON(http.StatusServiceUnavailable, gin.H{"error": "database unavailable"})
		return
	}

	ctx, cancel := context.WithTimeout(c.Request.Context(), 8*time.Second)
	defer cancel()

	var rows interface{ Close() }
	var err error
	fieldLower := strings.ToLower(strings.TrimSpace(field))

	if fieldLower == "" || fieldLower == "all fields" || fieldLower == "all" || fieldLower == "any" {
		rows, err = db.Pool.Query(ctx, `
			SELECT openalex_id, display_name, current_institution, innovation_score
			FROM researcher_metrics
			WHERE innovation_score IS NOT NULL
			ORDER BY innovation_score DESC
			LIMIT 10
		`)
	} else {
		rows, err = db.Pool.Query(ctx, `
			SELECT openalex_id, display_name, current_institution, innovation_score
			FROM researcher_metrics
			WHERE innovation_score IS NOT NULL
			  AND field_of_study ILIKE $1
			ORDER BY innovation_score DESC
			LIMIT 10
		`, "%"+field+"%")
	}

	if err != nil {
		slog.Error("leaderboard query failed", "field", field, "err", err)
		c.JSON(http.StatusInternalServerError, gin.H{"error": "failed to load leaderboard"})
		return
	}

	// rows is a pgx.Rows interface.
	type pgRows interface {
		Next() bool
		Scan(dest ...any) error
		Close()
		Err() error
	}

	pgr := rows.(pgRows)
	defer pgr.Close()

	entries := make([]LeaderboardEntry, 0, 10)
	rank := 1
	for pgr.Next() {
		var id string
		var name string
		var institution *string
		var score *float64
		if err := pgr.Scan(&id, &name, &institution, &score); err != nil {
			continue
		}
		inst := "Independent Researcher"
		if institution != nil && *institution != "" {
			inst = *institution
		}
		entropyScore := 0
		if score != nil {
			entropyScore = int(*score)
		}
		entries = append(entries, LeaderboardEntry{
			Rank:         rank,
			ID:           id,
			UserName:     name,
			Institution:  inst,
			EntropyScore: entropyScore,
		})
		rank++
	}

	if pgr.Err() != nil {
		slog.Warn("leaderboard rows error", "err", pgr.Err())
	}

	if len(entries) == 0 {
		c.JSON(http.StatusUnprocessableEntity, gin.H{
			"error": "no leaderboard data available for field '" + field + "'",
		})
		return
	}

	c.JSON(http.StatusOK, entries)
}

// ── Quests ────────────────────────────────────────────────────────────────────

// GetUserQuests handles GET /api/v1/users/quests?user_id=...
// Fast path: read from PostgreSQL user_preferences.
// Slow path (no quests exist yet): proxied to Python for LLM-based generation.
func GetUserQuests(proxyFallback gin.HandlerFunc) gin.HandlerFunc {
	return func(c *gin.Context) {
		userID := strings.TrimSpace(c.Query("user_id"))
		if userID == "" {
			c.JSON(http.StatusBadRequest, gin.H{"error": "user_id is required"})
			return
		}

		if db.Pool == nil {
			// No DB — let Python handle everything.
			proxyFallback(c)
			return
		}

		ctx, cancel := context.WithTimeout(c.Request.Context(), 8*time.Second)
		defer cancel()

		quests, err := loadQuestsFromPG(ctx, userID)
		if err != nil {
			slog.Warn("quests PG load failed", "user", userID, "err", err)
			// Fall through to Python proxy.
			proxyFallback(c)
			return
		}

		if quests == nil {
			// Quests not yet generated — delegate to Python (LLM generation).
			proxyFallback(c)
			return
		}

		c.JSON(http.StatusOK, quests)
	}
}

// CompleteQuest handles POST /api/v1/users/quests/complete?user_id=...&quest_id=...
// Marks a quest as completed and returns the awarded entropy.
func CompleteQuest(c *gin.Context) {
	userID := strings.TrimSpace(c.Query("user_id"))
	questID := strings.TrimSpace(c.Query("quest_id"))

	if userID == "" || questID == "" {
		c.JSON(http.StatusBadRequest, gin.H{"error": "user_id and quest_id are required"})
		return
	}

	if db.Pool == nil {
		c.JSON(http.StatusServiceUnavailable, gin.H{"error": "database unavailable"})
		return
	}

	ctx, cancel := context.WithTimeout(c.Request.Context(), 8*time.Second)
	defer cancel()

	quests, err := loadQuestsFromPG(ctx, userID)
	if err != nil || quests == nil {
		c.JSON(http.StatusNotFound, gin.H{
			"status":  "error",
			"message": "quests not found or not yet initialised for this user",
		})
		return
	}

	updated := false
	reward := 0
	for i := range quests {
		if quests[i].ID == questID {
			quests[i].IsCompleted = true
			reward = quests[i].RewardEntropy
			updated = true
			break
		}
	}

	if !updated {
		c.JSON(http.StatusNotFound, gin.H{
			"status":  "error",
			"message": "quest " + questID + " not found",
		})
		return
	}

	if err := saveQuestsToPG(ctx, userID, quests); err != nil {
		slog.Error("complete quest save failed", "user", userID, "err", err)
		c.JSON(http.StatusInternalServerError, gin.H{"error": "failed to save quest completion"})
		return
	}

	c.JSON(http.StatusOK, gin.H{
		"status":          "success",
		"message":         "Quest " + questID + " completed",
		"entropy_awarded": reward,
	})
}

// ── PostgreSQL helpers ────────────────────────────────────────────────────────

func loadQuestsFromPG(ctx context.Context, userID string) ([]Quest, error) {
	if db.Pool == nil {
		return nil, nil
	}
	row := db.Pool.QueryRow(ctx, `
		SELECT preference_value
		FROM user_preferences
		WHERE user_id = $1 AND preference_key = 'quests'
		LIMIT 1
	`, userID)

	var raw []byte
	if err := row.Scan(&raw); err != nil {
		// pgx returns pgx.ErrNoRows when empty — treat as "not yet generated".
		if err.Error() == "no rows in result set" {
			return nil, nil
		}
		return nil, err
	}

	var quests []Quest
	if err := json.Unmarshal(raw, &quests); err != nil {
		return nil, err
	}
	return quests, nil
}

func saveQuestsToPG(ctx context.Context, userID string, quests []Quest) error {
	raw, err := json.Marshal(quests)
	if err != nil {
		return err
	}
	_, err = db.Pool.Exec(ctx, `
		UPDATE user_preferences
		SET preference_value = $1::jsonb
		WHERE user_id = $2 AND preference_key = 'quests'
	`, string(raw), userID)
	return err
}
