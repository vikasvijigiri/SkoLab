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
	"log/slog"
	"net/http"
	"strings"
	"time"
	"unicode"
	"unicode/utf8"

	"github.com/gin-gonic/gin"

	"github.com/skolab/backend-go/internal/apierror"
	"github.com/skolab/backend-go/internal/auth"
	"github.com/skolab/backend-go/internal/db"
	"github.com/skolab/backend-go/internal/security"
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
		apierror.Abort(c, http.StatusBadRequest, "invalid_body", "Request body must be JSON with uid and name")
		return
	}

	// auth.VerifyUser() only proves *a* valid Firebase account made this
	// call, not that it owns the profile being written -- req.UID came
	// from the client-controlled JSON body, so without this check any
	// authenticated caller could overwrite any other user's display_name
	// (2026-09-12 endpoint audit).
	req.Name = strings.TrimSpace(req.Name)
	if req.Name == "" || utf8.RuneCountInString(req.Name) > 255 || strings.IndexFunc(req.Name, unicode.IsControl) >= 0 {
		apierror.Abort(c, http.StatusBadRequest, "invalid_name", "name must be 1 to 255 characters with no control characters")
		return
	}

	if req.UID != c.GetString("user_id") {
		apierror.Abort(c, http.StatusForbidden, "forbidden", "You may only sync your own profile")
		return
	}

	if db.Pool == nil {
		apierror.Abort(c, http.StatusServiceUnavailable, "database_unavailable", "The database is temporarily unavailable")
		return
	}

	query := `
		INSERT INTO users (id, display_name)
		VALUES ($1, $2)
		ON CONFLICT (id) DO UPDATE SET display_name = $2
	`
	ctx, cancel := context.WithTimeout(c.Request.Context(), 3*time.Second)
	defer cancel()
	if _, err := db.Pool.Exec(ctx, query, req.UID, req.Name); err != nil {
		apierror.Abort(c, http.StatusInternalServerError, "internal_error", "Could not save the profile")
		return
	}

	c.JSON(http.StatusOK, gin.H{"status": "synced", "uid": req.UID})
}

// DeleteUser handles GDPR Right to be Forgotten. Workspace/membership/ticket
// rows referencing this uid cascade-delete via their own FK definitions
// (ON DELETE CASCADE). That cascade would also delete workspaces the user
// owns but others actively collaborate in, so deletion is refused with 409
// owns_shared_workspaces (listing them) until each is transferred
// (POST /workspaces/:id/owner) or deleted explicitly. The Firebase identity is then deleted too, which ends
// every session: otherwise the caller stays signed in and the next profile
// sync silently recreates the account.
//
// Order matters. The database row goes first; if deleting the Firebase
// identity then fails, the caller is still authenticated and can retry, and
// both steps are idempotent. The reverse order could strand a row nobody can
// ever authenticate to delete.
func DeleteUser(c *gin.Context) {
	targetUserID := c.Param("userId")
	tokenUserID := c.GetString("user_id")

	if targetUserID != tokenUserID {
		apierror.Abort(c, http.StatusForbidden, "forbidden", "You may only delete your own account")
		return
	}

	if db.Pool == nil {
		apierror.Abort(c, http.StatusServiceUnavailable, "database_unavailable", "The database is temporarily unavailable")
		return
	}

	ctx, cancel := context.WithTimeout(c.Request.Context(), 3*time.Second)
	defer cancel()
	shared, err := deleteUnlessSharing(ctx, targetUserID)
	if err != nil {
		apierror.Abort(c, http.StatusInternalServerError, "internal_error", "Could not delete the account data")
		return
	}
	if len(shared) > 0 {
		apierror.AbortWith(c, http.StatusConflict, "owns_shared_workspaces",
			"Transfer or delete the workspaces you share with others before deleting your account",
			gin.H{"workspaces": shared})
		return
	}
	security.Audit(c, security.Event{Name: security.AccountDeleted, Outcome: security.Allowed})
	if err := auth.DeleteIdentity(c.Request.Context(), targetUserID); err != nil {
		slog.Error("account deletion: identity provider step failed", "err", err)
		apierror.Abort(c, http.StatusBadGateway, "identity_delete_failed", "Account data was deleted, but sign-in removal failed; please retry")
		return
	}

	c.Status(http.StatusNoContent)
}

// SharedWorkspace is an owned workspace that blocks account deletion.
type SharedWorkspace struct {
	ID      string `json:"id"`
	Title   string `json:"title"`
	Members int    `json:"members"` // active members besides the owner
}

// deleteUnlessSharing deletes uid's row unless uid owns a workspace with
// other active members, which it returns instead. Two races are closed by
// lock order:
//   - uid's own membership rows are locked first. An ownership transfer TO
//     uid locks the same row (workspace.TransferOwnership), so the two
//     serialize: a transfer that commits first is seen by the check below
//     (409); one that comes second finds no active editor and is refused.
//     Without this, a transfer committing mid-deletion was cascade-deleted.
//   - Owned workspaces are locked next: an invite accept racing the
//     deletion must take a key-share lock on its workspace, so it either
//     commits before the check (and is counted) or waits for the cascade.
func deleteUnlessSharing(ctx context.Context, uid string) ([]SharedWorkspace, error) {
	tx, err := db.Pool.Begin(ctx)
	if err != nil {
		return nil, err
	}
	defer tx.Rollback(ctx) //nolint:errcheck // no-op after Commit
	if _, err := tx.Exec(ctx, "SELECT 1 FROM workspace_members WHERE user_id = $1 FOR UPDATE", uid); err != nil {
		return nil, err
	}
	if _, err := tx.Exec(ctx, "SELECT 1 FROM workspaces WHERE owner_id = $1 FOR UPDATE", uid); err != nil {
		return nil, err
	}
	rows, err := tx.Query(ctx, `
		SELECT w.id, w.title, count(*)
		FROM workspaces w
		JOIN workspace_members m ON m.workspace_id = w.id AND m.status = 'active' AND m.user_id <> w.owner_id
		WHERE w.owner_id = $1
		GROUP BY w.id, w.title, w.created_at
		ORDER BY w.created_at, w.id
		LIMIT 100`, uid)
	if err != nil {
		return nil, err
	}
	var shared []SharedWorkspace
	for rows.Next() {
		var ws SharedWorkspace
		if err := rows.Scan(&ws.ID, &ws.Title, &ws.Members); err != nil {
			rows.Close()
			return nil, err
		}
		shared = append(shared, ws)
	}
	rows.Close()
	if err := rows.Err(); err != nil {
		return nil, err
	}
	if len(shared) > 0 {
		return shared, nil
	}
	if _, err := tx.Exec(ctx, "DELETE FROM users WHERE id = $1", uid); err != nil {
		return nil, err
	}
	return nil, tx.Commit(ctx)
}
