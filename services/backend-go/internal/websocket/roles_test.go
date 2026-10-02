package websocket

import (
	"context"
	"errors"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/gorilla/websocket"
)

// roleTable is a mutable authorizer keyed by user, so a test can revoke or
// change access while a socket is open.
type roleTable struct {
	mu    sync.Mutex
	roles map[string]string
	err   error
}

func (r *roleTable) Role(_ context.Context, _, userID string) (string, error) {
	r.mu.Lock()
	defer r.mu.Unlock()
	return r.roles[userID], r.err
}

func (r *roleTable) set(userID, role string, err error) {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.roles[userID], r.err = role, err
}

// serveRoles starts a gateway whose ?u= query names the authenticated user.
func serveRoles(t *testing.T, roles *roleTable, interval time.Duration) string {
	t.Helper()
	saved := reauthorizeInterval
	reauthorizeInterval = interval
	t.Cleanup(func() { reauthorizeInterval = saved })
	gin.SetMode(gin.TestMode)
	hub := NewHub()
	go hub.Run()
	r := gin.New()
	r.GET("/ws/colab/:workspace_id", func(c *gin.Context) {
		c.Set("user_id", c.Query("u"))
		ServeWs(hub, roles, c)
	})
	server := httptest.NewServer(r)
	t.Cleanup(server.Close)
	return "ws" + strings.TrimPrefix(server.URL, "http") + "/ws/colab/paper?u="
}

func dial(t *testing.T, base, user string) *websocket.Conn {
	t.Helper()
	conn, _, err := websocket.DefaultDialer.Dial(base+user, nil)
	if err != nil {
		t.Fatalf("dial as %s: %v", user, err)
	}
	t.Cleanup(func() { conn.Close() })
	time.Sleep(50 * time.Millisecond) // let the hub register the client
	return conn
}

// closeCode reads until the server closes the socket and returns the code.
func closeCode(t *testing.T, conn *websocket.Conn) int {
	t.Helper()
	_ = conn.SetReadDeadline(time.Now().Add(3 * time.Second))
	for {
		if _, _, err := conn.ReadMessage(); err != nil {
			var closeErr *websocket.CloseError
			if errors.As(err, &closeErr) {
				return closeErr.Code
			}
			t.Fatalf("socket ended without a close frame: %v", err)
		}
	}
}

func receives(conn *websocket.Conn, within time.Duration) (string, bool) {
	_ = conn.SetReadDeadline(time.Now().Add(within))
	_, message, err := conn.ReadMessage()
	return string(message), err == nil
}

func TestEditorMessagesReachPeers(t *testing.T) {
	roles := &roleTable{roles: map[string]string{"ada": RoleEditor, "grace": RoleViewer}}
	base := serveRoles(t, roles, time.Hour)
	viewer, editor := dial(t, base, "grace"), dial(t, base, "ada")

	if err := editor.WriteMessage(websocket.TextMessage, []byte("edit-1")); err != nil {
		t.Fatal(err)
	}
	if got, ok := receives(viewer, 2*time.Second); !ok || got != "edit-1" {
		t.Fatalf("viewer got %q (ok=%v), want the editor's edit", got, ok)
	}
}

func TestReadOnlyRolesCannotPublish(t *testing.T) {
	for _, role := range []string{RoleViewer, RoleCommenter} {
		t.Run(role, func(t *testing.T) {
			roles := &roleTable{roles: map[string]string{"ada": RoleEditor, "grace": role}}
			base := serveRoles(t, roles, time.Hour)
			editor, readOnly := dial(t, base, "ada"), dial(t, base, "grace")

			if err := readOnly.WriteMessage(websocket.TextMessage, []byte("forged-edit")); err != nil {
				t.Fatal(err)
			}
			if code := closeCode(t, readOnly); code != websocket.ClosePolicyViolation {
				t.Fatalf("close code = %d, want 1008", code)
			}
			if got, ok := receives(editor, 300*time.Millisecond); ok {
				t.Fatalf("a %s's message reached a peer: %q", role, got)
			}
		})
	}
}

func TestRevokedMemberIsDisconnected(t *testing.T) {
	roles := &roleTable{roles: map[string]string{"ada": RoleEditor}}
	base := serveRoles(t, roles, 50*time.Millisecond)
	conn := dial(t, base, "ada")

	roles.set("ada", "", nil) // removed from the workspace
	if code := closeCode(t, conn); code != websocket.ClosePolicyViolation {
		t.Fatalf("close code = %d, want 1008", code)
	}
}

func TestDowngradeToViewerTakesEffectOnOpenSocket(t *testing.T) {
	roles := &roleTable{roles: map[string]string{"ada": RoleEditor}}
	base := serveRoles(t, roles, 50*time.Millisecond)
	conn := dial(t, base, "ada")

	roles.set("ada", RoleViewer, nil)
	time.Sleep(200 * time.Millisecond) // several re-authorizations
	if err := conn.WriteMessage(websocket.TextMessage, []byte("edit")); err != nil {
		t.Fatal(err)
	}
	if code := closeCode(t, conn); code != websocket.ClosePolicyViolation {
		t.Fatalf("close code = %d, want 1008", code)
	}
}

func TestReauthorizationFailsClosedWhenItStaysUnavailable(t *testing.T) {
	roles := &roleTable{roles: map[string]string{"ada": RoleEditor}}
	base := serveRoles(t, roles, 50*time.Millisecond)
	conn := dial(t, base, "ada")

	roles.set("ada", RoleEditor, ErrWorkspaceAuthorizationUnavailable)
	if code := closeCode(t, conn); code != websocket.ClosePolicyViolation {
		t.Fatalf("close code = %d, want 1008", code)
	}
}

func TestOneTransientAuthorizationErrorKeepsTheSocket(t *testing.T) {
	roles := &roleTable{roles: map[string]string{"ada": RoleEditor, "grace": RoleViewer}}
	base := serveRoles(t, roles, 50*time.Millisecond)
	editor, viewer := dial(t, base, "ada"), dial(t, base, "grace")

	roles.set("ada", RoleEditor, ErrWorkspaceAuthorizationUnavailable)
	time.Sleep(70 * time.Millisecond) // one failed check, below the limit
	roles.set("ada", RoleEditor, nil)
	time.Sleep(100 * time.Millisecond)
	if err := editor.WriteMessage(websocket.TextMessage, []byte("still-here")); err != nil {
		t.Fatal(err)
	}
	if got, ok := receives(viewer, 2*time.Second); !ok || got != "still-here" {
		t.Fatalf("viewer got %q (ok=%v); the editor's socket should have survived", got, ok)
	}
}

func TestCanPublish(t *testing.T) {
	for role, want := range map[string]bool{RoleOwner: true, RoleEditor: true, RoleCommenter: false, RoleViewer: false, "": false, "admin": false} {
		if CanPublish(role) != want {
			t.Fatalf("CanPublish(%q) = %v", role, !want)
		}
	}
}
