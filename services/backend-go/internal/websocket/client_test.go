package websocket

import (
	"net/http/httptest"
	"testing"
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
