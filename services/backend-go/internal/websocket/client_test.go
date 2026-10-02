package websocket

import (
	"context"
	"errors"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/gin-gonic/gin"
	"github.com/gorilla/websocket"
)

type staticWorkspaceAuthorizer struct {
	allowed bool
	err     error
}

func (a staticWorkspaceAuthorizer) Role(context.Context, string, string) (string, error) {
	if a.err != nil || !a.allowed {
		return "", a.err
	}
	return RoleEditor, nil
}

// 2026-09-12 endpoint audit: CheckOrigin used to unconditionally return true,
// letting any page on the internet open a socket to /ws/colab/:workspace_id.
// It now defers to the gateway's own CORS allow-list (middleware.IsAllowedOrigin).

func TestCheckOrigin_AllowedOriginAccepted(t *testing.T) {
	req := httptest.NewRequest("GET", "/ws/colab/w1", nil)
	req.Header.Set("Origin", "http://localhost:3000")
	if !upgrader.CheckOrigin(req) {
		t.Fatal("expected an allow-listed origin to be accepted")
	}
}

func TestCheckOrigin_DisallowedOriginRejected(t *testing.T) {
	req := httptest.NewRequest("GET", "/ws/colab/w1", nil)
	req.Header.Set("Origin", "https://evil.example.com")
	if upgrader.CheckOrigin(req) {
		t.Fatal("expected a non-allow-listed origin to be rejected")
	}
}

func TestCheckOrigin_NoOriginHeaderAccepted(t *testing.T) {
	// Origin is a browser-enforced header; its absence (native apps, curl,
	// most non-browser clients) isn't itself suspicious, and CORS() treats
	// a missing Origin the same way (no header check applied at all).
	req := httptest.NewRequest("GET", "/ws/colab/w1", nil)
	if !upgrader.CheckOrigin(req) {
		t.Fatal("expected a request with no Origin header to be accepted")
	}
}

// ServeWs must reject a blank :workspace_id before ever attempting the
// websocket upgrade -- workspace_id is what the Hub now uses to scope
// broadcast delivery (hub.go), so an empty value would otherwise register a
// client into a "" workspace bucket that could collide with anything.
func TestServeWs_BlankWorkspaceIDRejectedBeforeUpgrade(t *testing.T) {
	gin.SetMode(gin.TestMode)
	r := gin.New()
	hub := NewHub()
	r.GET("/ws/colab/:workspace_id", func(c *gin.Context) {
		c.Set("user_id", "member-user")
		ServeWs(hub, staticWorkspaceAuthorizer{allowed: true}, c)
	})

	// %20 decodes to a single space, which TrimSpace reduces to "" --
	// gin's own router already refuses to match a truly empty segment here,
	// so this is the realistic way to reach that guard.
	req := httptest.NewRequest(http.MethodGet, "/ws/colab/%20", nil)
	w := httptest.NewRecorder()
	r.ServeHTTP(w, req)

	if w.Code != http.StatusBadRequest {
		t.Fatalf("status = %d, want %d", w.Code, http.StatusBadRequest)
	}
}

func TestServeWs_RejectsNonMemberBeforeUpgrade(t *testing.T) {
	gin.SetMode(gin.TestMode)
	r := gin.New()
	r.GET("/ws/colab/:workspace_id", func(c *gin.Context) {
		c.Set("user_id", "researcher-without-access")
		ServeWs(NewHub(), staticWorkspaceAuthorizer{allowed: false}, c)
	})
	server := httptest.NewServer(r)
	defer server.Close()

	response, err := http.Get(server.URL + "/ws/colab/quantum-manuscript")
	if err != nil {
		t.Fatalf("HTTP request failed: %v", err)
	}
	defer response.Body.Close()

	if response.StatusCode != http.StatusForbidden {
		t.Fatalf("status = %d, want %d", response.StatusCode, http.StatusForbidden)
	}
	body, err := io.ReadAll(response.Body)
	if err != nil {
		t.Fatalf("read response body: %v", err)
	}
	if string(body) != `{"error":"You do not have access to this workspace"}` {
		t.Fatalf("body = %s", body)
	}
}

func TestServeWs_AuthorizationFailureReturnsServiceUnavailable(t *testing.T) {
	gin.SetMode(gin.TestMode)
	r := gin.New()
	r.GET("/ws/colab/:workspace_id", func(c *gin.Context) {
		c.Set("user_id", "researcher-ada")
		ServeWs(NewHub(), staticWorkspaceAuthorizer{err: ErrWorkspaceAuthorizationUnavailable}, c)
	})
	server := httptest.NewServer(r)
	defer server.Close()

	response, err := http.Get(server.URL + "/ws/colab/quantum-manuscript")
	if err != nil {
		t.Fatalf("HTTP request failed: %v", err)
	}
	defer response.Body.Close()

	if response.StatusCode != http.StatusServiceUnavailable {
		t.Fatalf("status = %d, want %d", response.StatusCode, http.StatusServiceUnavailable)
	}
	body, err := io.ReadAll(response.Body)
	if err != nil {
		t.Fatalf("read response body: %v", err)
	}
	if string(body) != `{"error":"Workspace authorization is temporarily unavailable"}` {
		t.Fatalf("body = %s", body)
	}
}

func TestServeWs_AllowsMemberAndCompletesRealWebSocketHandshake(t *testing.T) {
	gin.SetMode(gin.TestMode)
	hub := NewHub()
	go hub.Run()
	r := gin.New()
	r.GET("/ws/colab/:workspace_id", func(c *gin.Context) {
		c.Set("user_id", "researcher-ada")
		ServeWs(hub, staticWorkspaceAuthorizer{allowed: true}, c)
	})
	server := httptest.NewServer(r)
	defer server.Close()

	wsURL := "ws" + strings.TrimPrefix(server.URL, "http") + "/ws/colab/quantum-manuscript"
	connection, response, err := websocket.DefaultDialer.Dial(wsURL, nil)
	if err != nil {
		if response != nil {
			t.Fatalf("WebSocket handshake failed with HTTP %d: %v", response.StatusCode, err)
		}
		t.Fatalf("WebSocket handshake failed: %v", err)
	}
	defer connection.Close()

	if response.StatusCode != http.StatusSwitchingProtocols {
		t.Fatalf("status = %d, want %d", response.StatusCode, http.StatusSwitchingProtocols)
	}
}

func TestPostgresWorkspaceAuthorizer_FailsClosedWithoutDatabase(t *testing.T) {
	role, err := NewPostgresWorkspaceAuthorizer(nil).Role(
		context.Background(), "quantum-manuscript", "researcher-ada",
	)
	if role != "" {
		t.Fatal("nil database pool must not authorize workspace access")
	}
	if !errors.Is(err, ErrWorkspaceAuthorizationUnavailable) {
		t.Fatalf("error = %v, want ErrWorkspaceAuthorizationUnavailable", err)
	}
}
