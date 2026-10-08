package workspace

import (
	"context"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"errors"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgconn"
	"github.com/jackc/pgx/v5/pgxpool"
)

var (
	ErrOwnerCannotLeave = errors.New("the owner cannot leave their own workspace")
	ErrOwnerImmutable   = errors.New("the owner's role cannot be changed or removed")
	ErrInviteForbidden  = errors.New("only the owner or an editor may manage invites")
	ErrTransferTarget   = errors.New("the new owner must be an active editor of the workspace")
	ErrTransferLimit    = errors.New("the new owner has reached their workspace limit")
)

// rank orders roles; a link never grants "owner".
var rank = map[string]int{"viewer": 1, "commenter": 2, "editor": 3, "owner": 4}

// GrantableRoles are the roles an invite link may carry.
var GrantableRoles = []string{"editor", "commenter", "viewer"}

// canInvite: owners and editors may invite (product decision 2026-10-02).
func canInvite(role string) bool { return role == "owner" || role == "editor" }

type Invite struct {
	ID        string    `json:"id"`
	Role      string    `json:"role"`
	CreatedBy string    `json:"created_by"`
	CreatedAt time.Time `json:"created_at"`
	ExpiresAt time.Time `json:"expires_at"`
	MaxUses   *int      `json:"max_uses"` // nil = unlimited until expiry
	Uses      int       `json:"uses"`
	Token     string    `json:"token,omitempty"` // only in the create response
}

type InvitePreview struct {
	WorkspaceID    string    `json:"workspace_id"`
	WorkspaceTitle string    `json:"workspace_title"`
	Role           string    `json:"role"`
	ExpiresAt      time.Time `json:"expires_at"`
}

type Member struct {
	UserID      string    `json:"user_id"`
	DisplayName string    `json:"display_name"`
	Role        string    `json:"role"`
	Since       time.Time `json:"since"`
}

// Membership is what accepting an invite left the caller with.
type Membership struct {
	WorkspaceID string `json:"workspace_id"`
	Role        string `json:"role"`
	Changed     bool   `json:"changed"` // false: the caller already had this access or more
}

// SharingStore manages invites and members. Every method takes the caller
// and enforces who may do what, so handlers cannot forget a check.
type SharingStore interface {
	CallerRole(ctx context.Context, workspaceID, userID string) (string, error)
	CreateInvite(ctx context.Context, workspaceID, callerID, role string, ttl time.Duration, maxUses *int) (Invite, error)
	ListInvites(ctx context.Context, workspaceID, callerID string) ([]Invite, error)
	RevokeInvite(ctx context.Context, workspaceID, inviteID, callerID string) error
	PreviewInvite(ctx context.Context, token string) (InvitePreview, error)
	AcceptInvite(ctx context.Context, token, callerID string) (Membership, error)
	ListMembers(ctx context.Context, workspaceID, callerID string) ([]Member, error)
	ChangeRole(ctx context.Context, workspaceID, callerID, targetID, role string) error
	RemoveMember(ctx context.Context, workspaceID, callerID, targetID string) error
	TransferOwnership(ctx context.Context, workspaceID, callerID, targetID string, maxPerUser int) error
}

type postgresSharing struct{ pool *pgxpool.Pool }

func NewPostgresSharingStore(pool *pgxpool.Pool) SharingStore { return &postgresSharing{pool: pool} }

// newInviteToken returns the bearer token (shown once) and its stored digest.
func newInviteToken() (string, string, error) {
	raw := make([]byte, 32)
	if _, err := rand.Read(raw); err != nil {
		return "", "", err
	}
	token := "inv_" + base64.RawURLEncoding.EncodeToString(raw)
	return token, digest(token), nil
}

func digest(token string) string {
	sum := sha256.Sum256([]byte(token))
	return hex.EncodeToString(sum[:])
}

// usable selects invites (aliased i) that can still be redeemed. A link is
// only as good as its creator's current authority: once the creator is no
// longer the owner or an active editor (removed, demoted, account deleted),
// their links stop working, so a removed editor cannot rejoin through a link
// they minted while they were a member.
const usable = `i.revoked_at IS NULL AND i.expires_at > $2 AND (i.max_uses IS NULL OR i.uses < i.max_uses)
	AND EXISTS (
		SELECT 1 FROM workspaces iw
		LEFT JOIN workspace_members im
		       ON im.workspace_id = iw.id AND im.user_id = i.created_by AND im.status = 'active'
		WHERE iw.id = i.workspace_id AND (iw.owner_id = i.created_by OR im.role IN ('owner', 'editor')))`

// revokeLinksBy revokes every open link userID created in workspaceID. Run
// when userID loses invite rights, so a later re-promotion does not silently
// revive links the owner may have forgotten about.
const revokeLinksBy = `UPDATE workspace_invites SET revoked_at = $3
	WHERE workspace_id = $1 AND created_by = $2 AND revoked_at IS NULL`

func (s *postgresSharing) ready() error {
	if s.pool == nil {
		return ErrUnavailable
	}
	return nil
}

// CallerRole is the caller's role, or ErrNotFound if they cannot see the
// workspace (mirrors the WebSocket authorizer and the workspace API).
func (s *postgresSharing) CallerRole(ctx context.Context, workspaceID, userID string) (string, error) {
	if err := s.ready(); err != nil {
		return "", err
	}
	ctx, cancel := context.WithTimeout(ctx, queryTimeout)
	defer cancel()
	ws, err := scanWorkspace(s.pool.QueryRow(ctx, visible+` AND w.id = $2`, userID, workspaceID))
	if errors.Is(err, pgx.ErrNoRows) {
		return "", ErrNotFound
	}
	if err != nil {
		return "", unavailable(err)
	}
	return ws.Role, nil
}

// requireInviter: visible and owner/editor, else NotFound / InviteForbidden.
func (s *postgresSharing) requireInviter(ctx context.Context, workspaceID, callerID string) error {
	role, err := s.CallerRole(ctx, workspaceID, callerID)
	if err != nil {
		return err
	}
	if !canInvite(role) {
		return ErrInviteForbidden
	}
	return nil
}

func (s *postgresSharing) CreateInvite(ctx context.Context, workspaceID, callerID, role string, ttl time.Duration, maxUses *int) (Invite, error) {
	if err := s.requireInviter(ctx, workspaceID, callerID); err != nil {
		return Invite{}, err
	}
	token, tokenDigest, err := newInviteToken()
	if err != nil {
		return Invite{}, unavailable(err)
	}
	now := time.Now().UTC().Truncate(time.Microsecond)
	inv := Invite{ID: uuid.NewString(), Role: role, CreatedBy: callerID, CreatedAt: now,
		ExpiresAt: now.Add(ttl), MaxUses: maxUses, Token: token}
	ctx, cancel := context.WithTimeout(ctx, queryTimeout)
	defer cancel()
	if _, err := s.pool.Exec(ctx, `
		INSERT INTO workspace_invites (id, workspace_id, token_digest, role, created_by, created_at, expires_at, max_uses, uses)
		VALUES ($1, $2, $3, $4, $5, $6, $7, $8, 0)`,
		inv.ID, workspaceID, tokenDigest, role, callerID, inv.CreatedAt, inv.ExpiresAt, maxUses); err != nil {
		return Invite{}, unavailable(err)
	}
	return inv, nil
}

func (s *postgresSharing) ListInvites(ctx context.Context, workspaceID, callerID string) ([]Invite, error) {
	if err := s.requireInviter(ctx, workspaceID, callerID); err != nil {
		return nil, err
	}
	ctx, cancel := context.WithTimeout(ctx, queryTimeout)
	defer cancel()
	rows, err := s.pool.Query(ctx, `
		SELECT i.id, i.role, COALESCE(i.created_by, ''), i.created_at, i.expires_at, i.max_uses, i.uses
		FROM workspace_invites i WHERE i.workspace_id = $1 AND `+usable+`
		ORDER BY i.created_at DESC LIMIT 100`, workspaceID, time.Now().UTC())
	if err != nil {
		return nil, unavailable(err)
	}
	defer rows.Close()
	invites := []Invite{}
	for rows.Next() {
		var inv Invite
		if err := rows.Scan(&inv.ID, &inv.Role, &inv.CreatedBy, &inv.CreatedAt, &inv.ExpiresAt, &inv.MaxUses, &inv.Uses); err != nil {
			return nil, unavailable(err)
		}
		inv.CreatedAt, inv.ExpiresAt = inv.CreatedAt.UTC(), inv.ExpiresAt.UTC()
		invites = append(invites, inv)
	}
	if err := rows.Err(); err != nil {
		return nil, unavailable(err)
	}
	return invites, nil
}

func (s *postgresSharing) RevokeInvite(ctx context.Context, workspaceID, inviteID, callerID string) error {
	if err := s.requireInviter(ctx, workspaceID, callerID); err != nil {
		return err
	}
	ctx, cancel := context.WithTimeout(ctx, queryTimeout)
	defer cancel()
	tag, err := s.pool.Exec(ctx, `UPDATE workspace_invites SET revoked_at = $3
		WHERE id = $1 AND workspace_id = $2 AND revoked_at IS NULL`, inviteID, workspaceID, time.Now().UTC())
	if err != nil {
		return unavailable(err)
	}
	if tag.RowsAffected() == 0 {
		return ErrNotFound
	}
	return nil
}

// PreviewInvite shows what a link offers before the recipient joins. Any
// unusable token (unknown, expired, revoked, used up) is the same NotFound.
func (s *postgresSharing) PreviewInvite(ctx context.Context, token string) (InvitePreview, error) {
	if err := s.ready(); err != nil {
		return InvitePreview{}, err
	}
	ctx, cancel := context.WithTimeout(ctx, queryTimeout)
	defer cancel()
	var p InvitePreview
	err := s.pool.QueryRow(ctx, `
		SELECT w.id, w.title, i.role, i.expires_at
		FROM workspace_invites i JOIN workspaces w ON w.id = i.workspace_id
		WHERE i.token_digest = $1 AND `+usable, digest(token), time.Now().UTC()).
		Scan(&p.WorkspaceID, &p.WorkspaceTitle, &p.Role, &p.ExpiresAt)
	if errors.Is(err, pgx.ErrNoRows) {
		return InvitePreview{}, ErrNotFound
	}
	if err != nil {
		return InvitePreview{}, unavailable(err)
	}
	p.ExpiresAt = p.ExpiresAt.UTC()
	return p, nil
}

// AcceptInvite redeems a link in one transaction. The invite row is locked,
// so concurrent accepts cannot exceed max_uses. Existing access is never
// downgraded, and a use is only consumed when access actually changes.
func (s *postgresSharing) AcceptInvite(ctx context.Context, token, callerID string) (Membership, error) {
	if err := s.ready(); err != nil {
		return Membership{}, err
	}
	ctx, cancel := context.WithTimeout(ctx, queryTimeout)
	defer cancel()
	tx, err := s.pool.Begin(ctx)
	if err != nil {
		return Membership{}, unavailable(err)
	}
	defer tx.Rollback(ctx) //nolint:errcheck // no-op after Commit

	var inviteID, workspaceID, role, ownerID string
	err = tx.QueryRow(ctx, `
		SELECT i.id, i.workspace_id, i.role, w.owner_id
		FROM workspace_invites i JOIN workspaces w ON w.id = i.workspace_id
		WHERE i.token_digest = $1 AND `+usable+`
		FOR UPDATE OF i`, digest(token), time.Now().UTC()).Scan(&inviteID, &workspaceID, &role, &ownerID)
	if errors.Is(err, pgx.ErrNoRows) {
		return Membership{}, ErrNotFound
	}
	if err != nil {
		return Membership{}, unavailable(err)
	}
	if ownerID == callerID {
		return Membership{WorkspaceID: workspaceID, Role: "owner"}, nil
	}

	var current, status string
	err = tx.QueryRow(ctx, `SELECT role, status FROM workspace_members WHERE workspace_id = $1 AND user_id = $2 FOR UPDATE`,
		workspaceID, callerID).Scan(&current, &status)
	switch {
	case err == nil && status == "active" && rank[current] >= rank[role]:
		return Membership{WorkspaceID: workspaceID, Role: current}, nil
	case err != nil && !errors.Is(err, pgx.ErrNoRows):
		return Membership{}, unavailable(err)
	}
	if _, err := tx.Exec(ctx, `
		INSERT INTO workspace_members (workspace_id, user_id, role, status, created_at)
		VALUES ($1, $2, $3, 'active', $4)
		ON CONFLICT (workspace_id, user_id) DO UPDATE SET role = EXCLUDED.role, status = 'active'`,
		workspaceID, callerID, role, time.Now().UTC()); err != nil {
		var pgErr *pgconn.PgError
		if errors.As(err, &pgErr) && pgErr.Code == "23503" { // no users row yet
			return Membership{}, ErrProfileRequired
		}
		return Membership{}, unavailable(err)
	}
	if _, err := tx.Exec(ctx, `UPDATE workspace_invites SET uses = uses + 1 WHERE id = $1`, inviteID); err != nil {
		return Membership{}, unavailable(err)
	}
	if err := tx.Commit(ctx); err != nil {
		return Membership{}, unavailable(err)
	}
	return Membership{WorkspaceID: workspaceID, Role: role, Changed: true}, nil
}

func (s *postgresSharing) ListMembers(ctx context.Context, workspaceID, callerID string) ([]Member, error) {
	if _, err := s.CallerRole(ctx, workspaceID, callerID); err != nil {
		return nil, err
	}
	ctx, cancel := context.WithTimeout(ctx, queryTimeout)
	defer cancel()
	// Owner first, then members by role (editor, commenter, viewer) and
	// join time. The owner also has an 'owner' member row; listed once.
	rows, err := s.pool.Query(ctx, `
		SELECT user_id, display_name, role, since FROM (
			SELECT w.owner_id AS user_id, u.display_name, 'owner'::text AS role, w.created_at AS since
			FROM workspaces w JOIN users u ON u.id = w.owner_id WHERE w.id = $1
			UNION ALL
			SELECT m.user_id, u.display_name, m.role::text, m.created_at
			FROM workspace_members m
			JOIN users u ON u.id = m.user_id
			JOIN workspaces w ON w.id = m.workspace_id
			WHERE m.workspace_id = $1 AND m.status = 'active' AND m.user_id <> w.owner_id
		) members
		ORDER BY CASE role WHEN 'owner' THEN 0 WHEN 'editor' THEN 1 WHEN 'commenter' THEN 2 ELSE 3 END, since`, workspaceID)
	if err != nil {
		return nil, unavailable(err)
	}
	defer rows.Close()
	members := []Member{}
	for rows.Next() {
		var m Member
		if err := rows.Scan(&m.UserID, &m.DisplayName, &m.Role, &m.Since); err != nil {
			return nil, unavailable(err)
		}
		m.Since = m.Since.UTC()
		members = append(members, m)
	}
	if err := rows.Err(); err != nil {
		return nil, unavailable(err)
	}
	return members, nil
}

// requireOwner: visible and owner, else NotFound / Forbidden.
func (s *postgresSharing) requireOwner(ctx context.Context, workspaceID, callerID string) error {
	role, err := s.CallerRole(ctx, workspaceID, callerID)
	if err != nil {
		return err
	}
	if role != "owner" {
		return ErrForbidden
	}
	return nil
}

func (s *postgresSharing) ChangeRole(ctx context.Context, workspaceID, callerID, targetID, role string) error {
	if err := s.requireOwner(ctx, workspaceID, callerID); err != nil {
		return err
	}
	if targetID == callerID {
		return ErrOwnerImmutable
	}
	ctx, cancel := context.WithTimeout(ctx, queryTimeout)
	defer cancel()
	tx, err := s.pool.Begin(ctx)
	if err != nil {
		return unavailable(err)
	}
	defer tx.Rollback(ctx) //nolint:errcheck // no-op after Commit
	tag, err := tx.Exec(ctx, `UPDATE workspace_members SET role = $3
		WHERE workspace_id = $1 AND user_id = $2 AND status = 'active'`, workspaceID, targetID, role)
	if err != nil {
		return unavailable(err)
	}
	if tag.RowsAffected() == 0 {
		return ErrNotFound
	}
	if !canInvite(role) {
		if _, err := tx.Exec(ctx, revokeLinksBy, workspaceID, targetID, time.Now().UTC()); err != nil {
			return unavailable(err)
		}
	}
	if err := tx.Commit(ctx); err != nil {
		return unavailable(err)
	}
	return nil
}

// RemoveMember removes targetID. A member may remove themselves (leave);
// only the owner may remove someone else; nobody can remove the owner.
// Removal keeps the row (status 'removed') for history; open sockets are
// disconnected by the WebSocket re-authorization within its interval.
func (s *postgresSharing) RemoveMember(ctx context.Context, workspaceID, callerID, targetID string) error {
	role, err := s.CallerRole(ctx, workspaceID, callerID)
	if err != nil {
		return err
	}
	switch {
	case targetID == callerID && role == "owner":
		return ErrOwnerCannotLeave
	case targetID != callerID && role != "owner":
		return ErrForbidden
	}
	ctx, cancel := context.WithTimeout(ctx, queryTimeout)
	defer cancel()
	tx, err := s.pool.Begin(ctx)
	if err != nil {
		return unavailable(err)
	}
	defer tx.Rollback(ctx) //nolint:errcheck // no-op after Commit
	tag, err := tx.Exec(ctx, `
		UPDATE workspace_members m SET status = 'removed'
		FROM workspaces w
		WHERE m.workspace_id = w.id AND m.workspace_id = $1 AND m.user_id = $2
		  AND m.status = 'active' AND m.user_id <> w.owner_id`, workspaceID, targetID)
	if err != nil {
		return unavailable(err)
	}
	if tag.RowsAffected() == 0 {
		if targetID != callerID {
			if owner, _ := s.isOwner(ctx, workspaceID, targetID); owner {
				return ErrOwnerImmutable
			}
		}
		return ErrNotFound
	}
	// A departed member's links leave with them.
	if _, err := tx.Exec(ctx, revokeLinksBy, workspaceID, targetID, time.Now().UTC()); err != nil {
		return unavailable(err)
	}
	if err := tx.Commit(ctx); err != nil {
		return unavailable(err)
	}
	return nil
}

func (s *postgresSharing) isOwner(ctx context.Context, workspaceID, userID string) (bool, error) {
	var owner bool
	err := s.pool.QueryRow(ctx, `SELECT EXISTS (SELECT 1 FROM workspaces WHERE id = $1 AND owner_id = $2)`,
		workspaceID, userID).Scan(&owner)
	return owner, err
}

// TransferOwnership hands workspaceID from its owner (the caller) to
// targetID, who must already be an active editor: a trusted collaborator
// who can do the work, never a stranger handed a workspace unasked. The
// former owner stays on as an editor. The new owner's workspace cap applies,
// serialized with their own creates by the same advisory lock.
func (s *postgresSharing) TransferOwnership(ctx context.Context, workspaceID, callerID, targetID string, maxPerUser int) error {
	if err := s.requireOwner(ctx, workspaceID, callerID); err != nil {
		return err
	}
	if targetID == callerID {
		return ErrTransferTarget
	}
	ctx, cancel := context.WithTimeout(ctx, queryTimeout)
	defer cancel()
	tx, err := s.pool.Begin(ctx)
	if err != nil {
		return unavailable(err)
	}
	defer tx.Rollback(ctx) //nolint:errcheck // no-op after Commit

	if _, err := tx.Exec(ctx, "SELECT pg_advisory_xact_lock(hashtext('workspace-create:' || $1))", targetID); err != nil {
		return unavailable(err)
	}
	// Re-check ownership under a row lock: a concurrent transfer or delete wins once.
	var owner string
	err = tx.QueryRow(ctx, `SELECT owner_id FROM workspaces WHERE id = $1 FOR UPDATE`, workspaceID).Scan(&owner)
	if errors.Is(err, pgx.ErrNoRows) {
		return ErrNotFound
	}
	if err != nil {
		return unavailable(err)
	}
	if owner != callerID {
		return ErrForbidden
	}
	var role string
	err = tx.QueryRow(ctx, `SELECT role FROM workspace_members
		WHERE workspace_id = $1 AND user_id = $2 AND status = 'active' FOR UPDATE`, workspaceID, targetID).Scan(&role)
	if errors.Is(err, pgx.ErrNoRows) || (err == nil && role != "editor") {
		return ErrTransferTarget
	}
	if err != nil {
		return unavailable(err)
	}
	var owned int
	if err := tx.QueryRow(ctx, "SELECT count(*) FROM workspaces WHERE owner_id = $1", targetID).Scan(&owned); err != nil {
		return unavailable(err)
	}
	if owned >= maxPerUser {
		return ErrTransferLimit
	}
	for _, stmt := range []struct {
		sql  string
		args []any
	}{
		{`UPDATE workspaces SET owner_id = $2 WHERE id = $1`, []any{workspaceID, targetID}},
		{`UPDATE workspace_members SET role = 'owner' WHERE workspace_id = $1 AND user_id = $2`, []any{workspaceID, targetID}},
		// The former owner keeps working on it, as an editor.
		{`INSERT INTO workspace_members (workspace_id, user_id, role, status, created_at)
			VALUES ($1, $2, 'editor', 'active', $3)
			ON CONFLICT (workspace_id, user_id) DO UPDATE SET role = 'editor', status = 'active'`,
			[]any{workspaceID, callerID, time.Now().UTC()}},
	} {
		if _, err := tx.Exec(ctx, stmt.sql, stmt.args...); err != nil {
			return unavailable(err)
		}
	}
	if err := tx.Commit(ctx); err != nil {
		return unavailable(err)
	}
	return nil
}
