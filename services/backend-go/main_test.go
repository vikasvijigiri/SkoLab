package main

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"strings"
	"sync/atomic"
	"testing"

	"github.com/gin-gonic/gin"
	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/skolab/backend-go/internal/websocket"
)

func init() { gin.SetMode(gin.TestMode) }

// python stands in for the Python service: ready unless told otherwise.
func python(t *testing.T, status int) string {
	t.Helper()
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(status)
	}))
	t.Cleanup(server.Close)
	return server.URL
}

func testRouter(t *testing.T, g gateway) (*gin.Engine, *atomic.Bool) {
	t.Helper()
	if g.draining == nil {
		g.draining = &atomic.Bool{}
	}
	if g.hub == nil {
		g.hub = websocket.NewHub()
	}
	if g.python == "" {
		g.python = python(t, http.StatusOK)
	}
	if g.transport == nil {
		g.transport = http.DefaultTransport
	}
	return newRouter(g), g.draining
}

func serve(r http.Handler, method, path string, header ...string) *httptest.ResponseRecorder {
	req := httptest.NewRequest(method, path, nil)
	req.RemoteAddr = "203.0.113.7:1234"
	for i := 0; i+1 < len(header); i += 2 {
		req.Header.Set(header[i], header[i+1])
	}
	w := httptest.NewRecorder()
	r.ServeHTTP(w, req)
	return w
}

func decode(t *testing.T, w *httptest.ResponseRecorder) map[string]any {
	t.Helper()
	var body map[string]any
	if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
		t.Fatalf("body %q is not JSON: %v", w.Body.String(), err)
	}
	return body
}

func TestLivenessAnswersGetAndHead(t *testing.T) {
	r, _ := testRouter(t, gateway{})
	for _, method := range []string{http.MethodGet, http.MethodHead} {
		w := serve(r, method, "/gateway-health")
		if w.Code != http.StatusOK {
			t.Fatalf("%s: status %d", method, w.Code)
		}
		if w.Header().Get("X-Content-Type-Options") != "nosniff" {
			t.Fatalf("%s: security headers missing", method)
		}
	}
}

func TestRequestIDKeepsSafeIDsAndReplacesOthers(t *testing.T) {
	r, _ := testRouter(t, gateway{})
	if got := serve(r, http.MethodGet, "/gateway-health", "X-Request-ID", "abc-123").Header().Get("X-Request-ID"); got != "abc-123" {
		t.Fatalf("got %q, want the caller's id", got)
	}
	got := serve(r, http.MethodGet, "/gateway-health", "X-Request-ID", "bad id\twith tab").Header().Get("X-Request-ID")
	if got == "" || strings.ContainsAny(got, " \t") {
		t.Fatalf("got %q, want a fresh id", got)
	}
}

func TestReadinessWhileDraining(t *testing.T) {
	r, draining := testRouter(t, gateway{})
	draining.Store(true)
	w := serve(r, http.MethodGet, "/readyz")
	if w.Code != http.StatusServiceUnavailable || decode(t, w)["status"] != "draining" {
		t.Fatalf("got %d %s, want 503 draining", w.Code, w.Body)
	}
}

func TestReadinessWithoutDatabase(t *testing.T) {
	r, _ := testRouter(t, gateway{})
	w := serve(r, http.MethodGet, "/readyz")
	body := decode(t, w)
	if w.Code != http.StatusServiceUnavailable || body["database"] != "unhealthy" || body["python"] != "healthy" {
		t.Fatalf("got %d %v, want 503 with the database unhealthy", w.Code, body)
	}
}

func TestReadinessWithDatabase(t *testing.T) {
	url := os.Getenv("TEST_DATABASE_URL")
	if url == "" {
		if os.Getenv("CI") != "" {
			t.Fatal("TEST_DATABASE_URL must be set in CI")
		}
		t.Skip("TEST_DATABASE_URL not set")
	}
	pool, err := pgxpool.New(context.Background(), url)
	if err != nil {
		t.Fatal(err)
	}
	defer pool.Close()

	r, _ := testRouter(t, gateway{pool: pool})
	if w := serve(r, http.MethodGet, "/readyz"); w.Code != http.StatusOK || decode(t, w)["status"] != "ready" {
		t.Fatalf("got %d %s, want 200 ready", w.Code, w.Body)
	}
	r, _ = testRouter(t, gateway{pool: pool, python: python(t, http.StatusServiceUnavailable)})
	if w := serve(r, http.MethodGet, "/readyz"); w.Code != http.StatusServiceUnavailable || decode(t, w)["python"] != "unhealthy" {
		t.Fatalf("got %d %s, want 503 with Python unhealthy", w.Code, w.Body)
	}
}

func TestUnknownPathAndWrongMethod(t *testing.T) {
	r, _ := testRouter(t, gateway{})
	w := serve(r, http.MethodGet, "/nope")
	if w.Code != http.StatusNotFound || decode(t, w)["code"] != "not_found" {
		t.Fatalf("got %d %s, want a JSON 404", w.Code, w.Body)
	}
	w = serve(r, http.MethodPut, "/gateway-health")
	if w.Code != http.StatusMethodNotAllowed || !strings.Contains(w.Header().Get("Allow"), http.MethodGet) {
		t.Fatalf("got %d Allow=%q, want 405 listing GET", w.Code, w.Header().Get("Allow"))
	}
}

func TestEveryAPIRouteRequiresAToken(t *testing.T) {
	r, _ := testRouter(t, gateway{})
	routes := [][2]string{
		{http.MethodPost, "/api/v1/users/profile/sync"},
		{http.MethodDelete, "/api/v1/users/u1"},
		{http.MethodGet, "/api/v1/workspaces"},
		{http.MethodPost, "/api/v1/workspaces"},
		{http.MethodGet, "/api/v1/workspaces/00000000-0000-0000-0000-000000000001"},
		{http.MethodPatch, "/api/v1/workspaces/00000000-0000-0000-0000-000000000001"},
		{http.MethodDelete, "/api/v1/workspaces/00000000-0000-0000-0000-000000000001"},
		{http.MethodPost, "/api/v1/colab/compile"},
		{http.MethodPost, "/api/v1/ws/colab/00000000-0000-0000-0000-000000000001/tickets"},
	}
	for _, route := range routes {
		w := serve(r, route[0], route[1])
		if w.Code != http.StatusUnauthorized || w.Header().Get("WWW-Authenticate") != "Bearer" {
			t.Fatalf("%s %s: got %d, want 401 with a Bearer challenge", route[0], route[1], w.Code)
		}
	}
}

func TestWebSocketNeedsATicket(t *testing.T) {
	r, _ := testRouter(t, gateway{})
	w := serve(r, http.MethodGet, "/ws/colab/00000000-0000-0000-0000-000000000001")
	if w.Code != http.StatusUnauthorized || decode(t, w)["code"] != "ticket_missing" {
		t.Fatalf("got %d %s, want 401 ticket_missing", w.Code, w.Body)
	}
	// No database: a ticket cannot be checked, so the socket is refused.
	w = serve(r, http.MethodGet, "/ws/colab/00000000-0000-0000-0000-000000000001?ticket=abc")
	if w.Code != http.StatusServiceUnavailable {
		t.Fatalf("got %d %s, want 503 while tickets cannot be checked", w.Code, w.Body)
	}
}

func TestPerIPRateLimitFromEnvironment(t *testing.T) {
	t.Setenv("RATE_LIMIT_IP_RPS", "0.001")
	t.Setenv("RATE_LIMIT_IP_BURST", "2")
	r, _ := testRouter(t, gateway{})
	for i := 0; i < 2; i++ {
		if w := serve(r, http.MethodGet, "/api/v1/workspaces"); w.Code != http.StatusUnauthorized {
			t.Fatalf("request %d: status %d", i+1, w.Code)
		}
	}
	if w := serve(r, http.MethodGet, "/api/v1/workspaces"); w.Code != http.StatusTooManyRequests {
		t.Fatalf("got %d, want 429 beyond the burst", w.Code)
	}
	// Probes stay reachable for the platform even when the caller is limited.
	if w := serve(r, http.MethodGet, "/gateway-health"); w.Code != http.StatusOK {
		t.Fatalf("liveness got %d, want 200 despite the limit", w.Code)
	}
}

func TestTelemetryMiddlewareRuns(t *testing.T) {
	var ran atomic.Bool
	r, _ := testRouter(t, gateway{telemetry: func(c *gin.Context) { ran.Store(true); c.Next() }})
	serve(r, http.MethodGet, "/gateway-health")
	if !ran.Load() {
		t.Fatal("telemetry middleware not installed")
	}
}

func TestEnvLimits(t *testing.T) {
	for _, v := range []string{"", "abc", "0", "-3"} {
		t.Setenv("SKOLAB_TEST_LIMIT", v)
		if envLimit("SKOLAB_TEST_LIMIT", 5) != 5 || envBurst("SKOLAB_TEST_LIMIT", 6) != 6 {
			t.Fatalf("%q must fall back to the default", v)
		}
	}
	t.Setenv("SKOLAB_TEST_LIMIT", "12")
	if envLimit("SKOLAB_TEST_LIMIT", 5) != 12 || envBurst("SKOLAB_TEST_LIMIT", 6) != 12 {
		t.Fatal("a positive value must be used")
	}
}

func TestSentryHookIsUnavailableUntilConfigured(t *testing.T) {
	r, _ := testRouter(t, gateway{})
	w := serve(r, http.MethodPost, "/hooks/sentry")
	if w.Code != http.StatusServiceUnavailable || decode(t, w)["code"] != "not_configured" {
		t.Fatalf("got %d %s, want 503 not_configured", w.Code, w.Body)
	}
}
