package handlers

import (
	"context"
	"net/http"

	"github.com/gin-gonic/gin"
	"github.com/skolab/backend-go/internal/db"
)

type UserProfileSyncRequest struct {
	UID        string `json:"uid" binding:"required"`
	Name       string `json:"name" binding:"required"`
	Discipline string `json:"discipline"`
}

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

	// Instead of Go running the heavy OpenAlex resolving directly, 
	// Go triggers the Python Teleport background worker via a simple internal HTTP ping or PubSub.
	// For now, return immediate success.

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
