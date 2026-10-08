// Command backend-go is the Go API gateway, scoped to authentication,
// authorization, and CoLab (2026-09-27: every other domain — author lookups,
// feed, discovery, quests, recommendations, similarity, activity-feed,
// user-memory — was removed; see the pre-domain-removal-2026-09-27 git tag
// for the full-featured version and docs/audits/ for why).
package main

import (
	"context"
	"errors"
	"fmt"
	"log"
	"log/slog"
	"net"
	"net/http"
	"os"
	"os/signal"
	"strconv"
	"sync/atomic"
	"syscall"
	"time"

	"github.com/getsentry/sentry-go"
	sentrygin "github.com/getsentry/sentry-go/gin"
	"github.com/gin-gonic/gin"
	"github.com/google/uuid"
	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/skolab/backend-go/internal/alerts"
	"github.com/skolab/backend-go/internal/apierror"
	"github.com/skolab/backend-go/internal/auth"
	"github.com/skolab/backend-go/internal/colab"
	"github.com/skolab/backend-go/internal/db"
	"github.com/skolab/backend-go/internal/health"
	"github.com/skolab/backend-go/internal/middleware"
	"github.com/skolab/backend-go/internal/quota"
	"github.com/skolab/backend-go/internal/security"
	"github.com/skolab/backend-go/internal/shared"
	"github.com/skolab/backend-go/internal/telemetry"
	"github.com/skolab/backend-go/internal/user"
	"github.com/skolab/backend-go/internal/websocket"
	"github.com/skolab/backend-go/internal/workspace"
	"go.opentelemetry.io/otel/trace"
	"golang.org/x/time/rate"
)

func main() {
	// A deploy/restart sends SIGTERM, not SIGKILL: run drains on it instead
	// of killing every in-flight connection.
	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer stop()
	if err := run(ctx, ":8080", nil); err != nil {
		log.Fatal(err)
	}
}

// run starts the gateway on addr and serves until ctx ends, then drains.
// A configuration a deployment must not run with is returned as an error
// before anything listens; listening (optional) receives the bound address.
func run(ctx context.Context, addr string, listening func(net.Addr)) error {
	otel, err := telemetry.New(context.Background())
	if err != nil {
		return fmt.Errorf("telemetry configuration invalid: %w", err)
	}
	defer func() {
		ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
		defer cancel()
		if err := otel.Shutdown(ctx); err != nil {
			slog.Warn("telemetry shutdown incomplete", "err", err)
		}
	}()
	// Only remaining caller: internal/colab's fallback path while
	// COLAB_SANDBOX_URL is unset (see internal/colab/colab.go).
	pythonBackendURL := os.Getenv("PYTHON_BACKEND_URL")
	if pythonBackendURL == "" {
		pythonBackendURL = "http://localhost:8000"
	}

	// Structured JSON logging in production.
	release := os.Getenv("GIN_MODE") == "release"
	if release {
		gin.SetMode(gin.ReleaseMode)
		slog.SetDefault(slog.New(slog.NewJSONHandler(os.Stdout, nil)))

		// Fail fast: a real deploy must supply INTERNAL_API_TOKEN. The
		// colab-sandbox worker (and, while unset, Python's /colab/compile)
		// treats an empty token as "skip the check" — a deliberate local-dev
		// convenience that becomes "compile runs with no authentication" if
		// left unset in a real deployment, with no prior warning anywhere
		// (2026-09-26 security audit).
		if os.Getenv("INTERNAL_API_TOKEN") == "" {
			return errors.New("INTERNAL_API_TOKEN is unset while GIN_MODE=release; set it " +
				"(and the matching value on the Python backend / colab-sandbox worker) " +
				"before starting the gateway")
		}
	}

	// Error aggregation (Sentry) — mirrors app/core/observability.py's
	// init_observability(): no-op unless SENTRY_DSN is set, and the
	// BeforeSend gate keeps a developer's local .env DSN from shipping dev
	// noise into the same shared production Sentry project.
	if dsn := os.Getenv("SENTRY_DSN"); dsn != "" {
		env := os.Getenv("APP_ENV")
		if env == "" {
			env = "development"
		}
		err := sentry.Init(sentry.ClientOptions{
			Dsn:         dsn,
			Environment: env,
			BeforeSend: func(event *sentry.Event, hint *sentry.EventHint) *sentry.Event {
				if env != "production" && env != "staging" {
					return nil
				}
				return event
			},
		})
		if err != nil {
			slog.Error("sentry init failed", "err", err)
		} else {
			defer sentry.Flush(2 * time.Second)
			slog.Info("Sentry enabled", "environment", env)
		}
	} else {
		slog.Info("Sentry disabled — no SENTRY_DSN set")
	}

	auth.InitFirebase()
	if release && !auth.Ready() {
		// Exact wording: CI's image check and operators grep for it.
		//lint:ignore ST1005 the capitalized wording is a grep contract (ci.yml images job)
		return errors.New("Firebase initialization failed; refusing to start a deployed gateway")
	}

	dbErr := retryDatabaseInitialization(func() error { return db.InitDB(otel.DBTracer()) }, time.Sleep)
	if dbErr != nil {
		if release {
			return errors.New("PostgreSQL initialization failed after retries; refusing to start gateway")
		}
		slog.Warn("PostgreSQL unavailable in development", "err", dbErr)
	} else {
		defer db.CloseDB()
	}

	// Expired quota buckets and audit rows past retention, once a minute.
	cleanupCtx, stopCleanup := context.WithCancel(context.Background())
	defer stopCleanup()
	go maintain(cleanupCtx, db.Pool, time.Minute)
	// Saturation signals (pool usage, goroutines, heap) beside the RED metrics.
	// Pool stats are read at export time; nil while the pool is down.
	if err := otel.ObserveDBPool(func() telemetry.PoolStats {
		if db.Pool == nil {
			return nil
		}
		return db.Pool.Stat()
	}); err != nil {
		slog.Warn("DB pool metrics unavailable", "err", err)
	}
	if err := otel.ObserveRuntime(); err != nil {
		slog.Warn("runtime metrics unavailable", "err", err)
	}
	// Security decisions: counted for dashboards/alerts; account-level
	// actions also land in the durable security_audit_log table.
	if err := security.UseMeter(otel.Metrics.Meter("skolab.security")); err != nil {
		slog.Warn("security event metrics unavailable", "err", err)
	}
	security.UseStore(db.Pool)

	// ── Shared state ──────────────────────────────────────────────────────────
	// With SHARED_STATE_REDIS_URL, rate limits, the failed-login throttle,
	// compile slots and WebSocket broadcasts are shared by every instance;
	// without it they stay in process (exact with one instance, as on
	// Render's free tier). Deliberately not REDIS_URL, the Python cache's.
	limits := shared.Connect(context.Background(), os.Getenv("SHARED_STATE_REDIS_URL"))
	if limits != nil {
		defer limits.Close()
	}
	if err := shared.ValidateConfiguration(limits); err != nil {
		return err
	}
	if err := colab.ValidateConfiguration(); err != nil {
		return err
	}
	auth.ShareFailedLogins(limits)
	colab.ShareLocks(limits)

	// ── WebSocket hub ─────────────────────────────────────────────────────────
	hub := websocket.NewHub()
	if release {
		hub.CheckSession = auth.CheckSession
	}
	if shared.Required() {
		ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
		err := hub.Ready(ctx)
		cancel()
		if err != nil {
			return errors.New("shared WebSocket broadcasts unavailable at startup")
		}
	}
	go hub.Run()
	var draining atomic.Bool

	r := newRouter(gateway{
		pool:      db.Pool,
		limits:    limits,
		hub:       hub,
		draining:  &draining,
		python:    pythonBackendURL,
		telemetry: otel.Middleware(),
		transport: otel.Transport(nil),
		sentry: alerts.Relay{
			Secret: os.Getenv("SENTRY_WEBHOOK_SECRET"),
			Slack:  alerts.SlackWebhook(os.Getenv("SLACK_ALERT_WEBHOOK_URL")),
			Client: &http.Client{Transport: otel.Transport(nil)},
		},
	})

	listener, err := net.Listen("tcp", addr)
	if err != nil {
		_ = hub.Shutdown(context.Background())
		return fmt.Errorf("listen on %s: %w", addr, err)
	}
	if listening != nil {
		listening(listener.Addr())
	}
	srv := &http.Server{Handler: r, ReadHeaderTimeout: 5 * time.Second, ReadTimeout: 30 * time.Second, IdleTimeout: 65 * time.Second, MaxHeaderBytes: 32 * 1024}
	served := make(chan error, 1)
	go func() {
		slog.Info("Go API Gateway starting", "addr", listener.Addr().String(), "python_backend", pythonBackendURL)
		served <- srv.Serve(listener)
	}()

	select {
	case err := <-served:
		_ = hub.Shutdown(context.Background())
		return fmt.Errorf("server error: %w", err)
	case <-ctx.Done():
	}
	draining.Store(true)
	slog.Info("shutdown signal received, draining in-flight requests")

	shutdownCtx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	if err := hub.Shutdown(shutdownCtx); err != nil {
		slog.Error("WebSocket shutdown incomplete", "err", err)
	}
	if err := srv.Shutdown(shutdownCtx); err != nil {
		slog.Error("graceful shutdown did not complete cleanly", "err", err)
	}
	return nil
}

// maintain runs periodic database housekeeping until ctx ends: expired
// quota buckets and security audit rows past their retention.
func maintain(ctx context.Context, pool *pgxpool.Pool, every time.Duration) {
	ticker := time.NewTicker(every)
	defer ticker.Stop()
	for {
		if err := quota.Sweep(ctx, pool); err != nil && ctx.Err() == nil {
			slog.Warn("quota cleanup unavailable")
		}
		if err := security.SweepAudit(ctx, pool, security.AuditRetention()); err != nil && ctx.Err() == nil {
			slog.Warn("audit log retention cleanup unavailable")
		}
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
		}
	}
}

// Do not construct stores until initialization succeeds; they capture the pool.
func retryDatabaseInitialization(initialize func() error, pause func(time.Duration)) error {
	var err error
	for attempt := 1; attempt <= 3; attempt++ {
		err = initialize()
		if err == nil {
			return nil
		}
		if attempt < 3 {
			pause(time.Duration(attempt) * time.Second)
		}
	}
	return err
}

// gateway is everything the router needs; main connects it, tests fake it.
type gateway struct {
	pool      *pgxpool.Pool // nil while the database is down
	limits    *shared.Store // nil: limits stay in process
	hub       *websocket.Hub
	draining  *atomic.Bool
	python    string
	telemetry gin.HandlerFunc // nil: no tracing
	transport http.RoundTripper
	sentry    alerts.Relay // unconfigured: /hooks/sentry answers 503
}

// newRouter wires middleware and every route.
func newRouter(g gateway) *gin.Engine {
	r := gin.New()
	// Unknown paths answer 404 and known paths with the wrong method 405
	// (Gin adds the Allow header), both in the standard error body.
	r.HandleMethodNotAllowed = true
	r.NoRoute(apierror.NoRoute)
	r.NoMethod(apierror.NoMethod)
	if g.telemetry != nil {
		r.Use(g.telemetry)
	}
	r.Use(middleware.Recovery())
	// Repanic: true — sentrygin captures the panic as a Sentry event, then
	// re-panics so middleware.Recovery() (registered above, so it recovers
	// last) still owns the actual "don't crash, log via slog, respond 500"
	// behavior. Only registered when Sentry actually initialized above, so
	// this is a true no-op with no SENTRY_DSN set.
	if sentry.CurrentHub().Client() != nil {
		r.Use(sentrygin.New(sentrygin.Options{
			Repanic:         true,
			WaitForDelivery: false,
			Timeout:         5 * time.Second,
		}))
	}
	r.Use(requestID())
	r.Use(requestLogger())
	r.Use(middleware.SecurityHeaders())
	r.Use(middleware.BodyLimit(middleware.MaxBodyBytes))
	r.Use(middleware.ValidPath())
	r.Use(middleware.ValidQuery())
	r.Use(middleware.Gzip())
	r.Use(middleware.CORS())

	// ── Rate limiting: 120 req/s per IP, burst of 30 ─────────────────────────
	// Limits are tunable per environment (the load test raises them so one
	// test account can drive the service); production uses the defaults.
	rl := middleware.NewRateLimiter(envLimit("RATE_LIMIT_IP_RPS", 120), envBurst("RATE_LIMIT_IP_BURST", 30)).Share(g.limits, "ip")
	r.Use(rl.Limit())

	// Per-user limit on authenticated routes (20 req/s, burst 40), applied
	// after the identity is verified -- see middleware.RateLimiter.PerUser.
	perUser := middleware.NewRateLimiter(envLimit("RATE_LIMIT_USER_RPS", 20), envBurst("RATE_LIMIT_USER_BURST", 40))
	authenticated := []gin.HandlerFunc{auth.VerifyUser(), perUser.Share(g.limits, "user").PerUser()}

	workspaceAuthorizer := websocket.NewPostgresWorkspaceAuthorizer(g.pool)
	workspaceTickets := websocket.NewPostgresWorkspaceTicketStore(g.pool)

	// ── Health ────────────────────────────────────────────────────────────────
	liveness := func(c *gin.Context) {
		c.JSON(http.StatusOK, gin.H{"status": "online", "service": "go-gateway"})
	}
	r.GET("/gateway-health", liveness)
	r.HEAD("/gateway-health", liveness) // uptime monitors often probe with HEAD
	// Readiness — /gateway-health stays the dependency-free liveness probe
	// Render restarts on; this one answers "can this instance serve every
	// route right now?": the database and the Python service beside it.
	readinessClient := &http.Client{}
	r.GET("/readyz", func(c *gin.Context) {
		if g.draining.Load() {
			c.JSON(http.StatusServiceUnavailable, gin.H{"status": "draining"})
			return
		}
		var pool health.Pinger
		if g.pool != nil {
			pool = g.pool
		}
		var dependencies []health.Dependency
		if os.Getenv("GIN_MODE") == "release" {
			dependencies = append(dependencies, health.Dependency{Name: "authentication", Check: func(context.Context) error {
				if !auth.Ready() {
					return errors.New("authentication unavailable")
				}
				return nil
			}})
		}
		if shared.Required() {
			dependencies = append(dependencies, health.Dependency{Name: "shared_state", Check: g.limits.Ping}, health.Dependency{Name: "broadcasts", Check: g.hub.Ready})
		}
		state, ready := health.Readiness(c.Request.Context(), pool, readinessClient, g.python, dependencies...)
		if !ready {
			slog.Error("readiness: dependency unhealthy", "dependencies", state)
			c.JSON(http.StatusServiceUnavailable, gin.H{"status": "not ready", "database": state["database"], "python": state["python"], "dependencies": state})
			return
		}
		c.JSON(http.StatusOK, gin.H{"status": "ready", "database": state["database"], "python": state["python"], "dependencies": state})
	})

	// ── Observability ─────────────────────────────────────────────────────────
	// Sentry alert webhooks (a Sentry internal integration) relayed to Slack;
	// authenticated by Sentry's HMAC signature, not a Firebase token.
	r.POST("/hooks/sentry", g.sentry.Handler())
	// Metrics push over OTLP. The former /observability scrape endpoint is retired.

	// ── WebSockets ────────────────────────────────────────────────────────────
	// Browsers obtain a single-use ticket from the Firebase-authenticated HTTPS
	// endpoint below before opening a socket. Only that opaque, one-minute ticket
	// appears in the WebSocket URL; Firebase bearer tokens never do.
	r.GET("/ws/colab/:workspace_id", websocket.VerifyTicket(workspaceTickets), func(c *gin.Context) {
		websocket.ServeWs(g.hub, workspaceAuthorizer, c)
	})
	wsTicketsAPI := r.Group("/api/v1/ws/colab")
	wsTicketsAPI.Use(authenticated...)
	{
		wsTicketsAPI.POST("/:workspace_id/tickets", websocket.IssueTicket(workspaceAuthorizer, workspaceTickets))
	}

	// ── User identity (Firebase-authenticated) ────────────────────────────────
	// Not a "user profile" feature kept for its own sake: workspaces,
	// workspace_members, and websocket_tickets all carry a foreign key into
	// users.id, so a Firebase account needs a row here before it can own or
	// join a CoLab workspace. See internal/user's package doc.
	usersAPI := r.Group("/api/v1/users")
	usersAPI.Use(authenticated...)
	{
		usersAPI.POST("/profile/sync", user.SyncUserProfile)
		usersAPI.DELETE("/:userId", user.DeleteUser)
	}

	// ── Workspaces (Firebase-authenticated) ───────────────────────────────────
	// The CoLab workspace resource: create/list/read/rename/delete. Ownership
	// comes from the verified token; visibility matches the WebSocket
	// authorizer above. See internal/workspace.
	workspacesAPI := r.Group("/api/v1")
	workspacesAPI.Use(authenticated...)
	workspace.Register(workspacesAPI, workspace.NewPostgresStore(g.pool))
	// Sharing: invite links (owner or editor) and member management.
	workspace.RegisterSharing(workspacesAPI, workspace.NewPostgresSharingStore(g.pool))

	// ── CoLab compile — auth + per-user quota + single-flight live in Go;
	// the actual pdflatex run happens in cmd/colab-sandbox (COLAB_SANDBOX_URL,
	// its own per-request-isolated container) once deployed, or falls back to
	// the existing hardened Python route until then. See internal/colab and
	// docs/audits/2026-09-26-backend-security-reliability-reaudit.md.
	colabAPI := r.Group("/api/v1")
	colabAPI.Use(authenticated...)
	{
		colabAPI.POST("/colab/compile", colab.Handler(g.pool, &http.Client{Transport: g.transport}, g.python))
	}

	return r
}

// envLimit and envBurst read a positive rate limit setting, else def.
func envLimit(key string, def float64) rate.Limit {
	if v, err := strconv.ParseFloat(os.Getenv(key), 64); err == nil && v > 0 {
		return rate.Limit(v)
	}
	return rate.Limit(def)
}

func envBurst(key string, def int) int {
	if v, err := strconv.Atoi(os.Getenv(key)); err == nil && v > 0 {
		return v
	}
	return def
}

const requestIDHeader = "X-Request-ID"

// requestID assigns a correlation id to every request — the caller's
// X-Request-ID when it is short and log-safe, else a fresh one — and both
// logs it (requestLogger, below) and forwards it to Python on the colab
// fallback call (internal/colab/colab.go copies inbound headers), so the
// same id shows up in both services' logs for one request.
func requestID() gin.HandlerFunc {
	return func(c *gin.Context) {
		id := c.GetHeader(requestIDHeader)
		if !middleware.RequestIDValid(id) {
			id = uuid.New().String()
		}
		c.Set("request_id", id)
		c.Request.Header.Set(requestIDHeader, id)
		c.Writer.Header().Set(requestIDHeader, id)
		c.Next()
	}
}

// requestLogger is a minimal structured access logger. trace_id/span_id
// match the OTel server span (telemetry.Middleware runs first), so a log line
// links straight to its trace in Tempo, the same as Python's JSON logs.
func requestLogger() gin.HandlerFunc {
	return func(c *gin.Context) {
		c.Next()
		attrs := []any{
			"method", c.Request.Method,
			"path", c.Request.URL.Path,
			"route", c.FullPath(),
			"status", c.Writer.Status(),
			"ip", c.RemoteIP(),
			"request_id", c.GetString("request_id"),
		}
		if sc := trace.SpanContextFromContext(c.Request.Context()); sc.IsValid() {
			attrs = append(attrs, "trace_id", sc.TraceID().String(), "span_id", sc.SpanID().String())
		}
		slog.Info("request", attrs...)
	}
}
