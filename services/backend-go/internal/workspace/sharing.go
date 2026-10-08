package workspace

import (
	"errors"
	"net/http"
	"slices"
	"strings"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/skolab/backend-go/internal/middleware"
	"github.com/skolab/backend-go/internal/security"
)

// Sharing routes (all behind auth.VerifyUser + the per-user limit):
//
//	GET    /workspaces/:id/invite-options     choices an inviter may pick from
//	POST   /workspaces/:id/invites            create a link (owner or editor) -> 201, token shown once
//	GET    /workspaces/:id/invites            active links (owner or editor)
//	DELETE /workspaces/:id/invites/:invite_id revoke (owner or editor) -> 204
//	POST   /invites/preview                   what a link offers  {"token"}
//	POST   /invites/accept                    join through a link {"token"}
//	GET    /workspaces/:id/members            members (any member)
//	PATCH  /workspaces/:id/members/:user_id   change role (owner)
//	DELETE /workspaces/:id/members/:user_id   remove (owner) or leave (self) -> 204
//	POST   /workspaces/:id/owner              transfer ownership to an active editor (owner)
//
// Every choice a caller can make is served by invite-options; no request
// needs free text. Tokens travel only in request bodies, never in URL paths,
// so they cannot leak into access logs.

// InviteExpiryHours and InviteMaxUses are the only values a link may use.
var (
	InviteExpiryHours = []int{24, 168, 720}
	InviteMaxUses     = []*int{intPtr(1), intPtr(10), nil} // nil = unlimited until expiry
)

func intPtr(n int) *int { return &n }

// RegisterSharing mounts invite and member routes on an authenticated group.
func RegisterSharing(group *gin.RouterGroup, store SharingStore) {
	h := sharingHandlers{store: store, maxPerUser: maxPerUser()}
	group.GET("/workspaces/:id/invite-options", h.inviteOptions)
	group.POST("/workspaces/:id/invites", h.createInvite)
	group.GET("/workspaces/:id/invites", h.listInvites)
	group.DELETE("/workspaces/:id/invites/:invite_id", h.revokeInvite)
	group.POST("/invites/preview", h.previewInvite)
	group.POST("/invites/accept", h.acceptInvite)
	group.GET("/workspaces/:id/members", h.listMembers)
	group.PATCH("/workspaces/:id/members/:user_id", h.changeRole)
	group.DELETE("/workspaces/:id/members/:user_id", h.removeMember)
	group.POST("/workspaces/:id/owner", h.transferOwnership)
}

type sharingHandlers struct {
	store      SharingStore
	maxPerUser int
}

func (h sharingHandlers) inviteOptions(c *gin.Context) {
	uid, ok := caller(c)
	if !ok {
		return
	}
	role, err := h.store.CallerRole(c.Request.Context(), c.Param("id"), uid)
	if err == nil && !canInvite(role) {
		err = ErrInviteForbidden
	}
	if err != nil {
		storeError(c, err)
		return
	}
	c.JSON(http.StatusOK, gin.H{
		"roles": GrantableRoles, "expires_in_hours": InviteExpiryHours, "max_uses": InviteMaxUses,
		"defaults": gin.H{"role": "editor", "expires_in_hours": 168, "max_uses": nil},
	})
}

type createInviteRequest struct {
	Role           string `json:"role"`
	ExpiresInHours int    `json:"expires_in_hours"`
	MaxUses        *int   `json:"max_uses"`
}

func (h sharingHandlers) createInvite(c *gin.Context) {
	uid, ok := caller(c)
	if !ok {
		return
	}
	var req createInviteRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		fail(c, http.StatusBadRequest, "invalid_body", "Request body must be JSON")
		return
	}
	validUses := slices.ContainsFunc(InviteMaxUses, func(n *int) bool {
		return (n == nil && req.MaxUses == nil) || (n != nil && req.MaxUses != nil && *n == *req.MaxUses)
	})
	switch {
	case !slices.Contains(GrantableRoles, req.Role):
		fail(c, http.StatusBadRequest, "invalid_role", "Choose a role from invite-options")
		return
	case !slices.Contains(InviteExpiryHours, req.ExpiresInHours):
		fail(c, http.StatusBadRequest, "invalid_expiry", "Choose an expiry from invite-options")
		return
	case !validUses:
		fail(c, http.StatusBadRequest, "invalid_max_uses", "Choose max uses from invite-options")
		return
	}
	inv, err := h.store.CreateInvite(c.Request.Context(), c.Param("id"), uid, req.Role,
		time.Duration(req.ExpiresInHours)*time.Hour, req.MaxUses)
	if err != nil {
		storeError(c, err)
		return
	}
	security.Audit(c, security.Event{Name: security.InviteCreated, Outcome: security.Allowed,
		WorkspaceID: c.Param("id"), Reason: req.Role})
	c.Header("Location", "/api/v1/workspaces/"+c.Param("id")+"/invites/"+inv.ID)
	c.JSON(http.StatusCreated, inv)
}

func (h sharingHandlers) listInvites(c *gin.Context) {
	uid, ok := caller(c)
	if !ok {
		return
	}
	invites, err := h.store.ListInvites(c.Request.Context(), c.Param("id"), uid)
	if err != nil {
		storeError(c, err)
		return
	}
	c.JSON(http.StatusOK, gin.H{"invites": invites})
}

func (h sharingHandlers) revokeInvite(c *gin.Context) {
	uid, ok := caller(c)
	if !ok {
		return
	}
	if err := h.store.RevokeInvite(c.Request.Context(), c.Param("id"), c.Param("invite_id"), uid); err != nil {
		storeError(c, err)
		return
	}
	security.Audit(c, security.Event{Name: security.InviteRevoked, Outcome: security.Allowed,
		WorkspaceID: c.Param("id"), Reason: c.Param("invite_id")})
	c.Status(http.StatusNoContent)
}

type tokenRequest struct {
	Token string `json:"token"`
}

// readToken returns a well-formed token or answers like an unknown one.
func readToken(c *gin.Context) (string, bool) {
	var req tokenRequest
	_ = c.ShouldBindJSON(&req)
	token := strings.TrimSpace(req.Token)
	if !strings.HasPrefix(token, "inv_") || len(token) > 100 || !middleware.Storable(token) {
		invalidInvite(c)
		return "", false
	}
	return token, true
}

// invalidInvite: unknown, expired, revoked and used-up links all look the
// same, so a link cannot be probed for which of those it is.
func invalidInvite(c *gin.Context) {
	security.Record(c, security.Event{Name: security.InviteInvalid, Outcome: security.Denied})
	fail(c, http.StatusNotFound, "invite_invalid", "This invite link is invalid or has expired")
}

func (h sharingHandlers) previewInvite(c *gin.Context) {
	if _, ok := caller(c); !ok {
		return
	}
	token, ok := readToken(c)
	if !ok {
		return
	}
	preview, err := h.store.PreviewInvite(c.Request.Context(), token)
	if errors.Is(err, ErrNotFound) {
		invalidInvite(c)
		return
	}
	if err != nil {
		storeError(c, err)
		return
	}
	c.JSON(http.StatusOK, preview)
}

func (h sharingHandlers) acceptInvite(c *gin.Context) {
	uid, ok := caller(c)
	if !ok {
		return
	}
	token, ok := readToken(c)
	if !ok {
		return
	}
	membership, err := h.store.AcceptInvite(c.Request.Context(), token, uid)
	if errors.Is(err, ErrNotFound) {
		invalidInvite(c)
		return
	}
	if err != nil {
		storeError(c, err)
		return
	}
	if membership.Changed {
		security.Audit(c, security.Event{Name: security.InviteAccepted, Outcome: security.Allowed,
			WorkspaceID: membership.WorkspaceID, Reason: membership.Role})
	}
	c.JSON(http.StatusOK, membership)
}

func (h sharingHandlers) listMembers(c *gin.Context) {
	uid, ok := caller(c)
	if !ok {
		return
	}
	members, err := h.store.ListMembers(c.Request.Context(), c.Param("id"), uid)
	if err != nil {
		storeError(c, err)
		return
	}
	c.JSON(http.StatusOK, gin.H{"members": members})
}

type roleRequest struct {
	Role string `json:"role"`
}

func (h sharingHandlers) changeRole(c *gin.Context) {
	uid, ok := caller(c)
	if !ok {
		return
	}
	var req roleRequest
	if err := c.ShouldBindJSON(&req); err != nil || !slices.Contains(GrantableRoles, req.Role) {
		fail(c, http.StatusBadRequest, "invalid_role", "Choose a role from invite-options")
		return
	}
	target := c.Param("user_id")
	if err := h.store.ChangeRole(c.Request.Context(), c.Param("id"), uid, target, req.Role); err != nil {
		storeError(c, err)
		return
	}
	security.Audit(c, security.Event{Name: security.MemberRoleChanged, Outcome: security.Allowed,
		WorkspaceID: c.Param("id"), Reason: target + "->" + req.Role})
	c.JSON(http.StatusOK, gin.H{"user_id": target, "role": req.Role})
}

func (h sharingHandlers) removeMember(c *gin.Context) {
	uid, ok := caller(c)
	if !ok {
		return
	}
	target := c.Param("user_id")
	if err := h.store.RemoveMember(c.Request.Context(), c.Param("id"), uid, target); err != nil {
		storeError(c, err)
		return
	}
	event := security.MemberRemoved
	if target == uid {
		event = security.MemberLeft
	}
	security.Audit(c, security.Event{Name: event, Outcome: security.Allowed, WorkspaceID: c.Param("id"), Reason: target})
	c.Status(http.StatusNoContent)
}

type transferRequest struct {
	UserID string `json:"user_id"`
}

func (h sharingHandlers) transferOwnership(c *gin.Context) {
	uid, ok := caller(c)
	if !ok {
		return
	}
	var req transferRequest
	if err := c.ShouldBindJSON(&req); err != nil || strings.TrimSpace(req.UserID) == "" || !middleware.Storable(req.UserID) {
		fail(c, http.StatusBadRequest, "invalid_body", "Request body must be JSON with the new owner's user_id")
		return
	}
	if err := h.store.TransferOwnership(c.Request.Context(), c.Param("id"), uid, req.UserID, h.maxPerUser); err != nil {
		storeError(c, err)
		return
	}
	security.Audit(c, security.Event{Name: security.OwnershipTransferred, Outcome: security.Allowed,
		WorkspaceID: c.Param("id"), Reason: uid + "->" + req.UserID})
	c.JSON(http.StatusOK, gin.H{"workspace_id": c.Param("id"), "owner_id": req.UserID, "your_role": "editor"})
}
