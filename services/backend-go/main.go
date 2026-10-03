// Command backend-go is the Go API gateway, scoped to authentication,
// authorization, and CoLab (2026-09-27: every other domain — author lookups,
// feed, discovery, quests, recommendations, similarity, activity-feed,
// user-memory — was removed; see the pre-domain-removal-2026-09-27 git tag
// for the full-featured version and docs/audits/ for why).
package main

import (
	"context"
	"errors"
	"log"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/getsentry/sentry-go"
	sentrygin "github.com/getsentry/sentry-go/gin"
	"github.com/gin-gonic/gin"
	"github.com/google/uuid"
	"github.com/skolab/backend-go/internal/apierror"
	"github.com/skolab/backend-go/internal/auth"
	"github.com/skolab/backend-go/internal/colab"
	"github.com/skolab/backend-go/internal/db"
	"github.com/skolab/backend-go/internal/middleware"
	"github.com/skolab/backend-go/internal/security"
	"github.com/skolab/backend-go/internal/telemetry"
	"github.com/skolab/backend-go/internal/user"
	"github.com/skolab/backend-go/internal/websocket"
	"github.com/skolab/backend-go/internal/workspace"
	"go.opentelemetry.io/otel/trace"
	"golang.org/x/time/rate"
)

func main() {
	otel, err := telemetry.New(context.Background())
	if err != nil {
		log.Fatalf("telemetry configuration invalid: %v", err)
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
	if os.Getenv("GIN_MODE") == "release" {
		gin.SetMode(gin.ReleaseMode)
		slog.SetDefault(slog.New(slog.NewJSONHandler(os.Stdout, nil)))

		// Fail fast: a real deploy must supply INTERNAL_API_TOKEN. The
		// colab-sandbox worker (and, while unset, Python's /colab/compile)
		// treats an empty token as "skip the check" — a deliberate local-dev
		// convenience that becomes "compile runs with no authentication" if
		// left unset in a real deployment, with no prior warning anywhere
		// (2026-09-26 security audit).
		if os.Getenv("INTERNAL_API_TOKEN") == "" {
			log.Fatal("INTERNAL_API_TOKEN is unset while GIN_MODE=release. Set it " +
				"(and the matching value on the Python backend / colab-sandbox worker) " +
				"before starting the gateway.")
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

	if err := db.InitDB(otel.DBTracer()); err != nil {
		slog.Warn("PostgreSQL init failed — DB-backed endpoints will be degraded", "err", err)
	} else {
		defer db.CloseDB()
	}

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

	r := gin.New()
	// Unknown paths answer 404 and known paths with the wrong method 405
	// (Gin adds the Allow header), both in the standard error body.
	r.HandleMethodNotAllowed = true
	r.NoRoute(apierror.NoRoute)
	r.NoMethod(apierror.NoMethod)
	r.Use(otel.Middleware())
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
	r.Use(middleware.Gzip())
	r.Use(middleware.CORS())

	// ── Rate limiting: 120 req/s per IP, burst of 30 ─────────────────────────
	rl := middleware.NewRateLimiter(rate.Limit(120), 30)
	r.Use(rl.Limit())

	// Per-user limit on authenticated routes (20 req/s, burst 40), applied
	// after the identity is verified -- see middleware.RateLimiter.PerUser.
	authenticated := []gin.HandlerFunc{auth.VerifyUser(), middleware.NewRateLimiter(rate.Limit(20), 40).PerUser()}

	// ── WebSocket hub ─────────────────────────────────────────────────────────
	hub := websocket.NewHub()
	go hub.Run()
	workspaceAuthorizer := websocket.NewPostgresWorkspaceAuthorizer(db.Pool)
	workspaceTickets := websocket.NewPostgresWorkspaceTicketStore(db.Pool)

	// ── Health ────────────────────────────────────────────────────────────────
	liveness := func(c *gin.Context) {
		c.JSON(http.StatusOK, gin.H{"status": "online", "service": "go-gateway"})
	}
	r.GET("/gateway-health", liveness)
	r.HEAD("/gateway-health", liveness) // uptime monitors often probe with HEAD
	// Readiness — /gateway-health stays the dependency-free liveness probe
	// Render restarts on; this one answers "can this instance serve DB-backed
	// routes right now?" for synthetic monitoring and future load balancers.
	r.GET("/readyz", func(c *gin.Context) {
		database := "unhealthy"
		if db.Pool != nil {
			ctx, cancel := context.WithTimeout(c.Request.Context(), 2*time.Second)
			defer cancel()
			if err := db.Pool.Ping(ctx); err == nil {
				database = "healthy"
			} else {
				slog.Error("readiness: database ping failed", "err", err)
			}
		}
		if database != "healthy" {
			c.JSON(http.StatusServiceUnavailable, gin.H{"status": "not ready", "database": database})
			return
		}
		c.JSON(http.StatusOK, gin.H{"status": "ready", "database": database})
	})

	// ── Observability ─────────────────────────────────────────────────────────
	// Metrics push over OTLP. The former /observability scrape endpoint is retired.

	// ── WebSockets ────────────────────────────────────────────────────────────
	// Browsers obtain a single-use ticket from the Firebase-authenticated HTTPS
	// endpoint below before opening a socket. Only that opaque, one-minute ticket
	// appears in the WebSocket URL; Firebase bearer tokens never do.
	r.GET("/ws/colab/:workspace_id", websocket.VerifyTicket(workspaceTickets), func(c *gin.Context) {
		websocket.ServeWs(hub, workspaceAuthorizer, c)
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
	workspace.Register(workspacesAPI, workspace.NewPostgresStore(db.Pool))
	// Sharing: invite links (owner or editor) and member management.
	workspace.RegisterSharing(workspacesAPI, workspace.NewPostgresSharingStore(db.Pool))

	// ── CoLab compile — auth + per-user quota + single-flight live in Go;
	// the actual pdflatex run happens in cmd/colab-sandbox (COLAB_SANDBOX_URL,
	// its own per-request-isolated container) once deployed, or falls back to
	// the existing hardened Python route until then. See internal/colab and
	// docs/audits/2026-09-26-backend-security-reliability-reaudit.md.
	colabAPI := r.Group("/api/v1")
	colabAPI.Use(authenticated...)
	{
		colabAPI.POST("/colab/compile", colab.Handler(db.Pool, &http.Client{Transport: otel.Transport(nil)}, pythonBackendURL))
	}

	addr := ":8080"
	srv := &http.Server{Addr: addr, Handler: r}

	go func() {
		slog.Info("Go API Gateway starting", "addr", addr, "python_backend", pythonBackendURL)
		if err := srv.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
			log.Fatalf("server error: %v", err)
		}
	}()

	// Graceful shutdown: a deploy/restart sends SIGTERM, not SIGKILL. Without
	// this, that signal kills every in-flight connection immediately.
	quitCh := make(chan os.Signal, 1)
	signal.Notify(quitCh, syscall.SIGINT, syscall.SIGTERM)
	<-quitCh
	slog.Info("shutdown signal received, draining in-flight requests")

	shutdownCtx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	if err := srv.Shutdown(shutdownCtx); err != nil {
		slog.Error("graceful shutdown did not complete cleanly", "err", err)
	}
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
