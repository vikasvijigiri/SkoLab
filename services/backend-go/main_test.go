package main

import (
	"compress/gzip"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/gin-gonic/gin"
	"github.com/skolab/backend-go/internal/middleware"
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

// TestReverseProxy_RewritesHostHeaderToTarget locks in a production incident:
// httputil.NewSingleHostReverseProxy's default Director rewrites req.URL but
// never req.Host, so without an explicit override the outbound request keeps
// carrying the inbound Host header. On Render, the edge routes by Host header
// rather than by resolved IP -- it saw the gateway's own hostname on a
// request dialed to the Python service and sent it straight back to the
// gateway, an infinite loop (HTTP 508, x-render-routing: loop) on every
// proxied route.
func TestReverseProxy_RewritesHostHeaderToTarget(t *testing.T) {
	var gotHost string
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotHost = r.Host
		w.WriteHeader(http.StatusOK)
	}))
	defer upstream.Close()

	gateway := newTestGateway(upstream.URL)
	defer gateway.Close()

	// The inbound request's own Host (gateway.URL's host:port) must not leak
	// through -- the upstream must see itself, not the gateway.
	resp, err := http.Get(gateway.URL + "/anything")
	if err != nil {
		t.Fatalf("request to gateway failed: %v", err)
	}
	defer resp.Body.Close()
	io.Copy(io.Discard, resp.Body)

	wantHost := upstream.URL[len("http://"):]
	if gotHost != wantHost {
		t.Errorf("upstream saw Host = %q, want %q (the gateway's inbound Host leaked through)", gotHost, wantHost)
	}
}

// TestProxyTransport_ResponseHeaderTimeoutNotShorterThanRequestTimeout locks
// in a production incident: proxyTransport.ResponseHeaderTimeout fires
// independently of the per-request context deadline (proxyRequestTimeout) set
// in reverseProxy()'s returned handler, so a shorter transport-level timeout
// silently truncates it. ResponseHeaderTimeout previously stood at 60s while
// proxyRequestTimeout documented (and enforced via context) 120s -- a real
// cold-compute daily_feed request was cut off with a 502 at exactly 60.3s,
// well inside the documented ~40-80s cold-compute path for that route. A
// real end-to-end request that takes 60-120s would make this an expensive,
// flaky test to write directly, so this instead asserts the two durations
// stay consistent -- the cheap invariant whose violation caused the bug.
func TestProxyTransport_ResponseHeaderTimeoutNotShorterThanRequestTimeout(t *testing.T) {
	transport, ok := proxyTransport.(*http.Transport)
	if !ok {
		t.Fatalf("proxyTransport is a %T, want *http.Transport", proxyTransport)
	}
	if transport.ResponseHeaderTimeout < proxyRequestTimeout {
		t.Errorf(
			"proxyTransport.ResponseHeaderTimeout = %v, proxyRequestTimeout = %v: "+
				"the transport will truncate any proxied request before the "+
				"per-request context deadline ever gets a chance to",
			transport.ResponseHeaderTimeout, proxyRequestTimeout,
		)
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

// TestReverseProxy_BuffersAMultiChunkUpstreamResponse locks in a real,
// live production bug: any proxied route whose upstream response reports no
// Content-Length (Transfer-Encoding: chunked -- every route on the Python
// backend, since FastAPI doesn't set Content-Length up front) came back
// corrupted at the real client once the body was large enough to arrive in
// more than one upstream read. Confirmed live against skolab-gateway's real
// /discovery/predict, with gzip entirely out of the picture (reproduced
// identically with Accept-Encoding: identity and no Content-Encoding
// anywhere in the response): the same request straight to
// skolab-backend-py, no gateway involved, came back clean every time, and a
// native (non-proxied) Go route serving a large gzip-wrapped body stayed
// clean too -- isolating the break to ReverseProxy's own multi-chunk copy
// path specifically, independent of this gateway's own gzip or header
// handling (which a previous, real, and separately necessary fix already
// addressed without resolving this).
//
// This upstream deliberately writes in two separate chunks with a Flush()
// between them and never sets Content-Length, forcing exactly the
// no-Content-Length, multi-write shape that broke in production -- for a
// large enough body that it cannot complete in a single upstream read.
func TestReverseProxy_BuffersAMultiChunkUpstreamResponse(t *testing.T) {
	firstChunk := strings.Repeat("skolab-chunk-one-", 2000)
	secondChunk := strings.Repeat("skolab-chunk-two-", 2000)
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(firstChunk))
		w.(http.Flusher).Flush()
		_, _ = w.Write([]byte(secondChunk))
	}))
	defer upstream.Close()

	gateway := newTestGateway(upstream.URL)
	defer gateway.Close()

	resp, err := http.Get(gateway.URL + "/anything")
	if err != nil {
		t.Fatalf("request to gateway failed: %v", err)
	}
	defer resp.Body.Close()

	got, err := io.ReadAll(resp.Body)
	if err != nil {
		t.Fatalf("reading proxied response: %v", err)
	}
	want := firstChunk + secondChunk
	if string(got) != want {
		t.Errorf("proxied body corrupted (len %d vs want %d)", len(got), len(want))
	}
	// Deliberately NOT asserting Content-Length here: this handler's own
	// buffering fix intentionally leaves it unset (see the inline comment on
	// resp.ContentLength = -1 in reverseProxy()) so a later gzip-wrapping
	// middleware -- exercised by the next test below -- can compress the
	// buffered bytes without the client being told to expect the raw,
	// pre-compression length.
}

// TestReverseProxy_BufferedResponseSurvivesGzipWrapping is the regression
// test for a bug in an earlier version of the buffering fix above: it also
// set Content-Length to the buffered, uncompressed body's length. That's
// wrong the moment a gzip-wrapping middleware sits in front of this
// handler (as middleware.Gzip() always does in the real gateway) -- it
// compresses whatever this handler writes, so the client ends up being
// promised a byte count that doesn't match what's actually sent. Confirmed
// live: this exact combination produced a 502 at the real client (Cloudflare
// detects the mismatch and breaks the response) even though the gateway's
// own access log showed a clean 200, since that log only reflects the
// status line, written before the mismatch is ever caught.
func TestReverseProxy_BufferedResponseSurvivesGzipWrapping(t *testing.T) {
	firstChunk := strings.Repeat("skolab-chunk-one-", 2000)
	secondChunk := strings.Repeat("skolab-chunk-two-", 2000)
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(firstChunk))
		w.(http.Flusher).Flush()
		_, _ = w.Write([]byte(secondChunk))
	}))
	defer upstream.Close()

	gin.SetMode(gin.TestMode)
	r := gin.New()
	r.Use(middleware.Gzip())
	r.NoRoute(reverseProxy(upstream.URL))
	gateway := httptest.NewServer(r)
	defer gateway.Close()

	req, err := http.NewRequest(http.MethodGet, gateway.URL+"/anything", nil)
	if err != nil {
		t.Fatalf("building request: %v", err)
	}
	req.Header.Set("Accept-Encoding", "gzip")
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatalf("request to gateway failed: %v", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		t.Fatalf("status = %d, want %d -- a Content-Length/body mismatch breaks the response at exactly this step", resp.StatusCode, http.StatusOK)
	}
	gz, err := gzip.NewReader(resp.Body)
	if err != nil {
		t.Fatalf("response body is not valid gzip: %v", err)
	}
	defer gz.Close()
	got, err := io.ReadAll(gz)
	if err != nil {
		t.Fatalf("failed to decompress the buffered-then-gzipped response: %v", err)
	}
	want := firstChunk + secondChunk
	if string(got) != want {
		t.Errorf("decompressed body corrupted (len %d vs want %d)", len(got), len(want))
	}
}
