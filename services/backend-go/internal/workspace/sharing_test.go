package workspace

import (
	"context"
	"encoding/json"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/gin-gonic/gin"
)

// fakeSharing records calls and returns canned results.
type fakeSharing struct {
	role  string
	err   error
	calls []string
}

func (f *fakeSharing) note(call string) error { f.calls = append(f.calls, call); return f.err }

func (f *fakeSharing) CallerRole(_ context.Context, _, _ string) (string, error) {
	return f.role, f.note("role")
}
func (f *fakeSharing) CreateInvite(_ context.Context, _, _, role string, ttl time.Duration, maxUses *int) (Invite, error) {
	uses := "nil"
	if maxUses != nil {
		uses = string(rune('0' + *maxUses%10))
	}
	err := f.note("create:" + role + ":" + ttl.String() + ":" + uses)
	return Invite{ID: "inv-1", Role: role, Token: "inv_secret"}, err
}
func (f *fakeSharing) ListInvites(context.Context, string, string) ([]Invite, error) {
	return []Invite{{ID: "inv-1"}}, f.note("list")
}
func (f *fakeSharing) RevokeInvite(_ context.Context, _, id, _ string) error {
	return f.note("revoke:" + id)
}
func (f *fakeSharing) PreviewInvite(_ context.Context, token string) (InvitePreview, error) {
	return InvitePreview{WorkspaceTitle: "Paper", Role: "editor"}, f.note("preview:" + token)
}
func (f *fakeSharing) AcceptInvite(_ context.Context, token, _ string) (Membership, error) {
	return Membership{WorkspaceID: "ws-1", Role: "editor", Changed: true}, f.note("accept:" + token)
}
func (f *fakeSharing) ListMembers(context.Context, string, string) ([]Member, error) {
	return []Member{{UserID: "ada", Role: "owner"}}, f.note("members")
}
func (f *fakeSharing) ChangeRole(_ context.Context, _, _, target, role string) error {
	return f.note("role:" + target + ":" + role)
}
func (f *fakeSharing) RemoveMember(_ context.Context, _, _, target string) error {
	return f.note("remove:" + target)
}
func (f *fakeSharing) TransferOwnership(_ context.Context, _, _, target string, _ int) error {
	return f.note("transfer:" + target)
}

func serveSharing(t *testing.T, store SharingStore, user, method, path, body string) *httptest.ResponseRecorder {
	t.Helper()
	gin.SetMode(gin.TestMode)
	r := gin.New()
	group := r.Group("/api/v1", func(c *gin.Context) {
		if user != "" {
			c.Set("user_id", user)
		}
	})
	RegisterSharing(group, store)
	w := httptest.NewRecorder()
	r.ServeHTTP(w, httptest.NewRequest(method, path, strings.NewReader(body)))
	return w
}

func TestInviteOptionsServeEveryChoice(t *testing.T) {
	w := serveSharing(t, &fakeSharing{role: "editor"}, "ada", "GET", "/api/v1/workspaces/ws-1/invite-options", "")
	var got struct {
		Roles          []string `json:"roles"`
		ExpiresInHours []int    `json:"expires_in_hours"`
		MaxUses        []*int   `json:"max_uses"`
	}
	if err := json.Unmarshal(w.Body.Bytes(), &got); err != nil || w.Code != 200 {
		t.Fatalf("status %d: %s", w.Code, w.Body)
	}
	if strings.Join(got.Roles, ",") != "editor,commenter,viewer" || len(got.ExpiresInHours) != 3 || len(got.MaxUses) != 3 {
		t.Fatalf("options = %+v", got)
	}
	if w := serveSharing(t, &fakeSharing{role: "viewer"}, "ada", "GET", "/api/v1/workspaces/ws-1/invite-options", ""); w.Code != 403 || errorCode(t, w) != "invite_forbidden" {
		t.Fatalf("viewer: %d %s", w.Code, w.Body)
	}
}

func TestCreateInviteAcceptsOnlyServedChoices(t *testing.T) {
	store := &fakeSharing{role: "editor"}
	w := serveSharing(t, store, "ada", "POST", "/api/v1/workspaces/ws-1/invites",
		`{"role":"commenter","expires_in_hours":168,"max_uses":1}`)
	if w.Code != 201 || !strings.Contains(w.Body.String(), `"token":"inv_secret"`) || w.Header().Get("Location") == "" {
		t.Fatalf("create: %d %v %s", w.Code, w.Header(), w.Body)
	}
	if store.calls[0] != "create:commenter:168h0m0s:1" {
		t.Fatalf("store got %v", store.calls)
	}
	for _, body := range []string{
		`{"role":"owner","expires_in_hours":168}`,
		`{"role":"admin","expires_in_hours":168}`,
		`{"role":"editor","expires_in_hours":5}`,
		`{"role":"editor","expires_in_hours":168,"max_uses":3}`,
		`not json`,
	} {
		store := &fakeSharing{role: "owner"}
		if w := serveSharing(t, store, "ada", "POST", "/api/v1/workspaces/ws-1/invites", body); w.Code != 400 || len(store.calls) != 0 {
			t.Fatalf("%s: status %d, store calls %v", body, w.Code, store.calls)
		}
	}
	// Unlimited uses (null) is a served choice.
	if w := serveSharing(t, &fakeSharing{role: "owner"}, "ada", "POST", "/api/v1/workspaces/ws-1/invites",
		`{"role":"viewer","expires_in_hours":24,"max_uses":null}`); w.Code != 201 {
		t.Fatalf("unlimited uses: %d", w.Code)
	}
}

func TestMalformedOrUnknownTokensLookTheSame(t *testing.T) {
	for _, body := range []string{`{}`, `{"token":"not-an-invite"}`, `{"token":"inv_` + strings.Repeat("x", 200) + `"}`, `{"token":"inv_\u0000"}`, `garbage`} {
		store := &fakeSharing{}
		w := serveSharing(t, store, "ada", "POST", "/api/v1/invites/accept", body)
		if w.Code != 404 || errorCode(t, w) != "invite_invalid" || len(store.calls) != 0 {
			t.Fatalf("%s: %d %s, calls %v", body, w.Code, w.Body, store.calls)
		}
	}
	store := &fakeSharing{err: ErrNotFound}
	for _, path := range []string{"/api/v1/invites/preview", "/api/v1/invites/accept"} {
		if w := serveSharing(t, store, "ada", "POST", path, `{"token":"inv_expired"}`); w.Code != 404 || errorCode(t, w) != "invite_invalid" {
			t.Fatalf("%s unknown token: %d %s", path, w.Code, w.Body)
		}
	}
}

func TestPreviewAndAcceptPassTheTokenFromTheBody(t *testing.T) {
	store := &fakeSharing{}
	if w := serveSharing(t, store, "ada", "POST", "/api/v1/invites/preview", `{"token":" inv_abc "}`); w.Code != 200 {
		t.Fatalf("preview: %d", w.Code)
	}
	if w := serveSharing(t, store, "ada", "POST", "/api/v1/invites/accept", `{"token":"inv_abc"}`); w.Code != 200 ||
		!strings.Contains(w.Body.String(), `"role":"editor"`) {
		t.Fatalf("accept: %d %s", w.Code, w.Body)
	}
	if strings.Join(store.calls, ",") != "preview:inv_abc,accept:inv_abc" {
		t.Fatalf("calls = %v", store.calls)
	}
}

func TestMemberManagement(t *testing.T) {
	store := &fakeSharing{}
	if w := serveSharing(t, store, "ada", "PATCH", "/api/v1/workspaces/ws-1/members/grace", `{"role":"viewer"}`); w.Code != 200 {
		t.Fatalf("change role: %d", w.Code)
	}
	for _, body := range []string{`{"role":"owner"}`, `{"role":""}`, `x`} {
		if w := serveSharing(t, &fakeSharing{}, "ada", "PATCH", "/api/v1/workspaces/ws-1/members/grace", body); w.Code != 400 {
			t.Fatalf("%s: %d", body, w.Code)
		}
	}
	if w := serveSharing(t, store, "ada", "DELETE", "/api/v1/workspaces/ws-1/members/grace", ""); w.Code != 204 {
		t.Fatalf("remove: %d", w.Code)
	}
	if strings.Join(store.calls, ",") != "role:grace:viewer,remove:grace" {
		t.Fatalf("calls = %v", store.calls)
	}
	cases := map[error]string{ErrOwnerCannotLeave: "owner_cannot_leave", ErrOwnerImmutable: "owner_immutable",
		ErrForbidden: "owner_required", ErrInviteForbidden: "invite_forbidden", ErrNotFound: "not_found"}
	for err, code := range cases {
		if w := serveSharing(t, &fakeSharing{err: err}, "ada", "DELETE", "/api/v1/workspaces/ws-1/members/ada", ""); errorCode(t, w) != code {
			t.Fatalf("%v -> %s, want %s", err, errorCode(t, w), code)
		}
	}
}

func TestEverySharingRouteRequiresAuthentication(t *testing.T) {
	routes := [][2]string{
		{"GET", "/api/v1/workspaces/w/invite-options"}, {"POST", "/api/v1/workspaces/w/invites"},
		{"GET", "/api/v1/workspaces/w/invites"}, {"DELETE", "/api/v1/workspaces/w/invites/i"},
		{"POST", "/api/v1/invites/preview"}, {"POST", "/api/v1/invites/accept"},
		{"GET", "/api/v1/workspaces/w/members"}, {"PATCH", "/api/v1/workspaces/w/members/u"},
		{"DELETE", "/api/v1/workspaces/w/members/u"}, {"POST", "/api/v1/workspaces/w/owner"},
	}
	for _, r := range routes {
		store := &fakeSharing{}
		if w := serveSharing(t, store, "", r[0], r[1], `{"token":"inv_x","role":"viewer"}`); w.Code != 401 || len(store.calls) != 0 {
			t.Fatalf("%s %s: %d, calls %v", r[0], r[1], w.Code, store.calls)
		}
	}
}

func TestListAndRevokeInvitesAndListMembers(t *testing.T) {
	store := &fakeSharing{}
	w := serveSharing(t, store, "ada", "GET", "/api/v1/workspaces/ws-1/invites", "")
	var invites struct{ Invites []Invite }
	if w.Code != 200 || json.Unmarshal(w.Body.Bytes(), &invites) != nil || len(invites.Invites) != 1 {
		t.Fatalf("list invites: %d %s", w.Code, w.Body)
	}
	if w := serveSharing(t, store, "ada", "DELETE", "/api/v1/workspaces/ws-1/invites/inv-1", ""); w.Code != 204 {
		t.Fatalf("revoke: %d", w.Code)
	}
	w = serveSharing(t, store, "ada", "GET", "/api/v1/workspaces/ws-1/members", "")
	var members struct{ Members []Member }
	if w.Code != 200 || json.Unmarshal(w.Body.Bytes(), &members) != nil || len(members.Members) != 1 {
		t.Fatalf("list members: %d %s", w.Code, w.Body)
	}
	if strings.Join(store.calls, ",") != "list,revoke:inv-1,members" {
		t.Fatalf("calls = %v", store.calls)
	}

	denied := &fakeSharing{err: ErrInviteForbidden}
	for _, route := range [][2]string{
		{"GET", "/api/v1/workspaces/ws-1/invites"},
		{"DELETE", "/api/v1/workspaces/ws-1/invites/inv-1"},
	} {
		if w := serveSharing(t, denied, "ada", route[0], route[1], ""); errorCode(t, w) != "invite_forbidden" {
			t.Fatalf("%s %s: got %s, want invite_forbidden", route[0], route[1], errorCode(t, w))
		}
	}
	if w := serveSharing(t, &fakeSharing{err: ErrNotFound}, "ada", "GET", "/api/v1/workspaces/ws-1/members", ""); errorCode(t, w) != "not_found" {
		t.Fatalf("members of a hidden workspace: got %s, want not_found", errorCode(t, w))
	}
}

func TestTransferOwnershipHandler(t *testing.T) {
	store := &fakeSharing{}
	w := serveSharing(t, store, "ada", "POST", "/api/v1/workspaces/ws-1/owner", `{"user_id":"grace"}`)
	if w.Code != 200 || !strings.Contains(w.Body.String(), `"owner_id":"grace"`) || strings.Join(store.calls, ",") != "transfer:grace" {
		t.Fatalf("transfer: %d %s %v", w.Code, w.Body, store.calls)
	}
	// A NUL byte would reach Postgres and fail there as a 503.
	for _, body := range []string{`{}`, `{"user_id":"  "}`, `x`, `{"user_id":"gr\u0000ace"}`} {
		if w := serveSharing(t, &fakeSharing{}, "ada", "POST", "/api/v1/workspaces/ws-1/owner", body); w.Code != 400 {
			t.Fatalf("%s: %d", body, w.Code)
		}
	}
	for err, code := range map[error]string{ErrTransferTarget: "transfer_target_invalid",
		ErrTransferLimit: "new_owner_limit_reached", ErrForbidden: "owner_required", ErrNotFound: "not_found"} {
		w := serveSharing(t, &fakeSharing{err: err}, "ada", "POST", "/api/v1/workspaces/ws-1/owner", `{"user_id":"grace"}`)
		if errorCode(t, w) != code {
			t.Fatalf("%v -> %s, want %s", err, errorCode(t, w), code)
		}
	}
}
