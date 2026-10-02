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

// usable selects invites that can still be redeemed.
const usable = `revoked_at IS NULL AND expires_at > $2 AND (max_uses IS NULL OR uses < max_uses)`

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
		SELECT id, role, COALESCE(created_by, ''), created_at, expires_at, max_uses, uses
		FROM workspace_invites WHERE workspace_id = $1 AND `+usable+`
		ORDER BY created_at DESC LIMIT 100`, workspaceID, time.Now().UTC())
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
	tag, err := s.pool.Exec(ctx, `UPDATE workspace_members SET role = $3
		WHERE workspace_id = $1 AND user_id = $2 AND status = 'active'`, workspaceID, targetID, role)
	if err != nil {
		return unavailable(err)
	}
	if tag.RowsAffected() == 0 {
		return ErrNotFound
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
	tag, err := s.pool.Exec(ctx, `
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
	return nil
}

func (s *postgresSharing) isOwner(ctx context.Context, workspaceID, userID string) (bool, error) {
	var owner bool
	err := s.pool.QueryRow(ctx, `SELECT EXISTS (SELECT 1 FROM workspaces WHERE id = $1 AND owner_id = $2)`,
		workspaceID, userID).Scan(&owner)
	return owner, err
}
