package main

import (
	"io"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/gin-gonic/gin"
)

// newTestGateway wraps reverseProxy(target) in a real gin engine served over a
// real httptest.Server (not just an httptest.ResponseRecorder). httputil.ReverseProxy
// probes the ResponseWriter for http.CloseNotifier, which gin's wrapper only
// satisfies against a genuine net/http connection -- a bare ResponseRecorder
// panics. Serving over a real listener is also a more faithful test of a proxy.
func newTestGateway(target string) *httptest.Server {
	gin.SetMode(gin.TestMode)
	r := gin.New()
	r.NoRoute(reverseProxy(target))
	return httptest.NewServer(r)
}

// TestReverseProxy_StripsUpstreamCORSHeaders locks in the fix documented inline
// in reverseProxy(): the Go gateway is the sole CORS authority for browser
// responses, so the Python upstream's own CORS headers must be dropped --
// otherwise ReverseProxy appends them alongside the gateway's own, producing
// duplicate Access-Control-Allow-Origin/Vary values that browsers reject.
func TestReverseProxy_StripsUpstreamCORSHeaders(t *testing.T) {
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Access-Control-Allow-Origin", "http://upstream-only.example.com")
		w.Header().Set("Access-Control-Allow-Credentials", "true")
		w.Header().Set("Access-Control-Allow-Methods", "GET")
		w.Header().Set("Access-Control-Allow-Headers", "X-Upstream-Header")
		w.Header().Set("Vary", "Origin")
		w.WriteHeader(http.StatusOK)
	}))
	defer upstream.Close()

	gateway := newTestGateway(upstream.URL)
	defer gateway.Close()

	resp, err := http.Get(gateway.URL + "/anything")
	if err != nil {
		t.Fatalf("request to gateway failed: %v", err)
	}
	defer resp.Body.Close()
	io.Copy(io.Discard, resp.Body)

	for _, h := range []string{
		"Access-Control-Allow-Origin",
		"Access-Control-Allow-Credentials",
		"Access-Control-Allow-Methods",
		"Access-Control-Allow-Headers",
		"Vary",
	} {
		if got := resp.Header.Get(h); got != "" {
			t.Errorf("expected upstream %s to be stripped, got %q", h, got)
		}
	}
	if resp.StatusCode != http.StatusOK {
		t.Errorf("status = %d, want %d", resp.StatusCode, http.StatusOK)
	}
}

func TestReverseProxy_SetsGatewayHeaders(t *testing.T) {
	var gotGatewayHeader string
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotGatewayHeader = r.Header.Get("X-Gateway")
		w.WriteHeader(http.StatusOK)
	}))
	defer upstream.Close()

	gateway := newTestGateway(upstream.URL)
	defer gateway.Close()

	resp, err := http.Get(gateway.URL + "/anything")
	if err != nil {
		t.Fatalf("request to gateway failed: %v", err)
	}
	defer resp.Body.Close()
	io.Copy(io.Discard, resp.Body)

	if gotGatewayHeader != "go" {
		t.Errorf("X-Gateway = %q, want %q", gotGatewayHeader, "go")
	}
}

func TestReverseProxy_UpstreamDownReturnsBadGateway(t *testing.T) {
	// A port nothing is listening on -- guaranteed connection refused.
	gateway := newTestGateway("http://127.0.0.1:1")
	defer gateway.Close()

	resp, err := http.Get(gateway.URL + "/anything")
	if err != nil {
		t.Fatalf("request to gateway failed: %v", err)
	}
	defer resp.Body.Close()
	io.Copy(io.Discard, resp.Body)

	if resp.StatusCode != http.StatusBadGateway {
		t.Errorf("status = %d, want %d when upstream is unreachable", resp.StatusCode, http.StatusBadGateway)
	}
}
