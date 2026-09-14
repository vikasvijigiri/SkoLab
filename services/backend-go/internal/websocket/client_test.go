package websocket

import (
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/gin-gonic/gin"
)

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
	r.GET("/ws/colab/:workspace_id", func(c *gin.Context) { ServeWs(hub, c) })

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
