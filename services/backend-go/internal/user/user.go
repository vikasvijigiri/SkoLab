// Package user is deliberately narrow: it exists only because workspaces,
// workspace_members, and websocket_tickets all carry a foreign key into
// users.id (see the alembic migrations that create them). Without a row
// in users for a given Firebase uid, that account cannot own or join a
// CoLab workspace or receive a connection ticket. This package is that one
// hard dependency, not a general "user profile" feature — the broader
// user-memory/activity-log surface that used to live here was removed
// (2026-09-27 scope cut to auth/authorization/CoLab only) and is not needed
// by anything this service still serves.
package user

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

// SyncUserProfile upserts the caller's own users row, keyed by their
// verified Firebase uid.
func SyncUserProfile(c *gin.Context) {
	var req UserProfileSyncRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	// auth.VerifyUser() only proves *a* valid Firebase account made this
	// call, not that it owns the profile being written -- req.UID came
	// from the client-controlled JSON body, so without this check any
	// authenticated caller could overwrite any other user's display_name
	// (2026-09-12 endpoint audit).
	if req.UID != c.GetString("user_id") {
		c.JSON(http.StatusForbidden, gin.H{"error": "Forbidden: you may only sync your own profile."})
		return
	}

	if db.Pool == nil {
		c.JSON(http.StatusServiceUnavailable, gin.H{"error": "database unavailable"})
		return
	}

	query := `
		INSERT INTO users (id, display_name)
		VALUES ($1, $2)
		ON CONFLICT (id) DO UPDATE SET display_name = $2
	`
	if _, err := db.Pool.Exec(context.Background(), query, req.UID, req.Name); err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "Failed to sync user to database"})
		return
	}

	c.JSON(http.StatusOK, gin.H{"status": "synced", "uid": req.UID})
}

// DeleteUser handles GDPR Right to be Forgotten. Workspace/membership/ticket
// rows referencing this uid cascade-delete via their own FK definitions
// (ON DELETE CASCADE) — deleting the users row is enough.
func DeleteUser(c *gin.Context) {
	targetUserID := c.Param("userId")
	tokenUserID := c.GetString("user_id")

	if targetUserID != tokenUserID {
		c.JSON(http.StatusForbidden, gin.H{"error": "Forbidden: you may only delete your own account."})
		return
	}

	if db.Pool == nil {
		c.JSON(http.StatusServiceUnavailable, gin.H{"error": "database unavailable"})
		return
	}

	if _, err := db.Pool.Exec(context.Background(), "DELETE FROM users WHERE id = $1", targetUserID); err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "Database error"})
		return
	}

	c.JSON(http.StatusOK, gin.H{"status": "success", "detail": "Account deleted successfully."})
}
