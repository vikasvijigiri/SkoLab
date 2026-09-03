package main

import (
	"context"
	"errors"
	"log"
	"log/slog"
	"net"
	"net/http"
	"net/http/httputil"
	"net/url"
	"os"
	"strings"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/skolab/backend-go/internal/auth"
	"github.com/skolab/backend-go/internal/author"
	"github.com/skolab/backend-go/internal/db"
	"github.com/skolab/backend-go/internal/middleware"
	"github.com/skolab/backend-go/internal/quest"
	"github.com/skolab/backend-go/internal/recommendation"
	"github.com/skolab/backend-go/internal/user"
	"github.com/skolab/backend-go/internal/websocket"
	"golang.org/x/time/rate"
)

func main() {
	pythonBackendURL := os.Getenv("PYTHON_BACKEND_URL")
	if pythonBackendURL == "" {
		pythonBackendURL = "http://localhost:8000"
	}

	// Structured JSON logging in production.
	if os.Getenv("GIN_MODE") == "release" {
		gin.SetMode(gin.ReleaseMode)
		slog.SetDefault(slog.New(slog.NewJSONHandler(os.Stdout, nil)))
	}

	auth.InitFirebase()

	if err := db.InitDB(); err != nil {
		log.Printf("WARNING: PostgreSQL init failed (%v) — DB-backed endpoints will be degraded\n", err)
	} else {
		defer db.CloseDB()
	}

	r := gin.New()
	r.Use(gin.Recovery())
	r.Use(requestLogger())
	r.Use(middleware.CORS())

	// ── Rate limiting: 120 req/s per IP, burst of 30 ─────────────────────────
	rl := middleware.NewRateLimiter(rate.Limit(120), 30)
	r.Use(rl.Limit())

	// ── WebSocket hub ─────────────────────────────────────────────────────────
	hub := websocket.NewHub()
	go hub.Run()

	// ── Health ────────────────────────────────────────────────────────────────
	r.GET("/gateway-health", func(c *gin.Context) {
		c.JSON(http.StatusOK, gin.H{"status": "online", "service": "go-gateway"})
	})

	// ── WebSockets ────────────────────────────────────────────────────────────
	r.GET("/ws/colab/:workspace_id", func(c *gin.Context) {
		websocket.ServeWs(hub, c)
	})
	r.GET("/ws/system/health", func(c *gin.Context) {
		websocket.ServeHealthWs(c)
	})

	// ── User management (Firebase-authenticated) ──────────────────────────────
	usersAPI := r.Group("/api/v1/users")
	usersAPI.Use(auth.VerifyUser())
	{
		usersAPI.POST("/profile/sync", user.SyncUserProfile)
		usersAPI.DELETE("/:userId", user.DeleteUser)
	}

	// ── User Memory — pure Go aggregation, no AI ──────────────────────────────
	memoryAPI := r.Group("/api/v1/user_memory")
	memoryAPI.Use(auth.VerifyUser())
	{
		memoryAPI.POST("/events", user.SyncUserMemoryEvents)
		memoryAPI.GET("/:userId", user.GetUserMemory)
	}

	// ── Author endpoints — Go PG + OpenAlex, no AI ───────────────────────────
	r.GET("/api/v1/author_suggestions", author.GetAuthorSuggestions)
	r.GET("/api/v1/orbit_metrics", author.GetOrbitMetrics)
	r.GET("/orbit_metrics", author.GetOrbitMetrics)
	r.GET("/api/v1/authors/orbit_metrics", author.GetOrbitMetrics)
	r.GET("/api/v1/resolve_email", author.ResolveAuthorEmail)
	r.GET("/api/v1/authors/resolve_email", author.ResolveAuthorEmail)
	r.GET("/authors/resolve_email", author.ResolveAuthorEmail)
	r.GET("/resolve_email", author.ResolveAuthorEmail)

	// ── Leaderboard — PG query only ───────────────────────────────────────────
	r.GET("/api/v1/leaderboard/:field", quest.GetLeaderboard)

	// ── Quests — Go fast-path read; Python slow-path for LLM generation ───────
	proxy := reverseProxy(pythonBackendURL)
	questsAPI := r.Group("/api/v1")
	questsAPI.Use(auth.VerifyUser())
	{
		questsAPI.GET("/users/quests", quest.GetUserQuests(proxy))
		questsAPI.POST("/users/quests/complete", quest.CompleteQuest)
	}

	// ── Recommendations: CoLab peer autocomplete — Go PG only, no AI ─────────
	// Ported from services/backend/app/domains/recommendation. Hard Firebase
	// auth (decisions/0008): the Android client attaches a token as of #27
	// (network/AuthInterceptor.kt). The per-IP limit + the 200-id cap on
	// check-registered still bound abuse from an authenticated caller.
	recRL := middleware.NewRateLimiter(rate.Limit(5), 5)
	recAPI := r.Group("/api/v1/recommendations")
	recAPI.Use(auth.VerifyUser(), recRL.Limit())
	{
		recAPI.GET("/peers", recommendation.GetPeerRecommendations)
		recAPI.POST("/peers/invite", recommendation.LogPeerInvite)
		recAPI.POST("/peers/check-registered", recommendation.CheckRegisteredPeers)
	}

	// ── Fallback: everything else → Python (AI / ML / enrichment) ────────────
	r.NoRoute(func(c *gin.Context) {
		if strings.EqualFold(c.GetHeader("Upgrade"), "websocket") {
			slog.Warn("rejected unhandled websocket upgrade", "path", c.Request.URL.Path)
			c.AbortWithStatus(http.StatusNotImplemented)
			return
		}
		proxy(c)
	})

	addr := ":8080"
	slog.Info("Go API Gateway starting",
		"addr", addr,
		"python_backend", pythonBackendURL,
	)
	if err := r.Run(addr); err != nil {
		log.Fatalf("server error: %v", err)
	}
}

// proxyTransport is a dedicated HTTP transport for the Python upstream. The
// default (http.DefaultTransport) caps idle connections per host at 2, so under
// load the gateway constantly reopens TCP connections to the single Python
// host, and — with no ResponseHeaderTimeout — a hung upstream request pins a
// gateway goroutine and the client socket indefinitely, leading to goroutine
// pileup and memory growth.
var proxyTransport http.RoundTripper = &http.Transport{
	Proxy: http.ProxyFromEnvironment,
	DialContext: (&net.Dialer{
		Timeout:   10 * time.Second,
		KeepAlive: 30 * time.Second,
	}).DialContext,
	ForceAttemptHTTP2:     true,
	MaxIdleConns:          100,
	MaxIdleConnsPerHost:   100,
	IdleConnTimeout:       90 * time.Second,
	TLSHandshakeTimeout:   10 * time.Second,
	ExpectContinueTimeout: 1 * time.Second,
	ResponseHeaderTimeout: 60 * time.Second,
}

// proxyRequestTimeout bounds a single proxied request end-to-end. LLM routes
// are the slow case (long 70B generations); 120s covers them with margin while
// still guaranteeing a hung upstream cannot hold a goroutine forever.
const proxyRequestTimeout = 120 * time.Second

// reverseProxy returns a Gin handler that reverse-proxies to target.
func reverseProxy(target string) gin.HandlerFunc {
	targetURL, err := url.Parse(target)
	if err != nil {
		log.Fatalf("invalid proxy target URL: %v", err)
	}
	proxy := httputil.NewSingleHostReverseProxy(targetURL)
	proxy.Transport = proxyTransport
	orig := proxy.Director
	proxy.Director = func(req *http.Request) {
		orig(req)
		req.Header.Set("X-Forwarded-Host", req.Header.Get("Host"))
		req.Header.Set("X-Gateway", "go")
	}
	// The Python backend sets its own CORS headers (see services/backend/app/main.py).
	// The Go gateway is the sole CORS authority for browser-facing responses
	// (middleware.CORS() already set them), so drop the upstream's copies here —
	// otherwise ReverseProxy appends them alongside the gateway's, producing duplicate
	// Access-Control-Allow-Origin/Vary values that browsers reject as invalid CORS.
	proxy.ModifyResponse = func(resp *http.Response) error {
		resp.Header.Del("Access-Control-Allow-Origin")
		resp.Header.Del("Access-Control-Allow-Credentials")
		resp.Header.Del("Access-Control-Allow-Methods")
		resp.Header.Del("Access-Control-Allow-Headers")
		resp.Header.Del("Vary")
		return nil
	}
	proxy.ErrorHandler = func(w http.ResponseWriter, r *http.Request, err error) {
		if errors.Is(err, context.Canceled) {
			// Client hung up — not a gateway fault, don't log it as an error.
			return
		}
		if errors.Is(err, context.DeadlineExceeded) {
			slog.Warn("proxy upstream timeout", "path", r.URL.Path)
			w.WriteHeader(http.StatusGatewayTimeout)
			return
		}
		slog.Error("proxy error", "path", r.URL.Path, "err", err)
		w.WriteHeader(http.StatusBadGateway)
	}
	return func(c *gin.Context) {
		ctx, cancel := context.WithTimeout(c.Request.Context(), proxyRequestTimeout)
		defer cancel()
		proxy.ServeHTTP(c.Writer, c.Request.WithContext(ctx))
	}
}

// requestLogger is a minimal structured access logger.
func requestLogger() gin.HandlerFunc {
	return func(c *gin.Context) {
		c.Next()
		slog.Info("request",
			"method", c.Request.Method,
			"path", c.Request.URL.Path,
			"status", c.Writer.Status(),
			"ip", c.RemoteIP(),
		)
	}
}
