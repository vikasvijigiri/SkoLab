package middleware

import (
	"net/http"
	"os"
	"strings"

	"github.com/gin-gonic/gin"
)

// defaultCORSOrigins are for local development only. Production browser
// origins must be explicitly configured, as on the Python backend.
var defaultCORSOrigins = []string{
	"http://localhost",
	"http://localhost:8000",
	"http://localhost:3000",
	"http://127.0.0.1",
	"http://127.0.0.1:8000",
	"http://127.0.0.1:3000",
}

// allowedOrigins builds the same allow-list for HTTP CORS and WebSocket
// CheckOrigin. Production excludes local defaults and the default APP_BASE_URL.
func allowedOrigins() map[string]bool {
	allowed := make(map[string]bool)
	production := strings.EqualFold(os.Getenv("APP_ENV"), "production")
	if !production {
		for _, o := range defaultCORSOrigins {
			allowed[o] = true
		}
	}
	if baseURL := strings.TrimSpace(os.Getenv("APP_BASE_URL")); baseURL != "" && !(production && baseURL == "http://localhost:8000") {
		allowed[baseURL] = true
	}
	if extra := os.Getenv("CORS_ORIGINS"); extra != "" {
		for _, o := range strings.Split(extra, ",") {
			if o = strings.TrimSpace(o); o != "" {
				allowed[o] = true
			}
		}
	}
	return allowed
}

// IsAllowedOrigin reports whether origin is in this gateway's CORS allow-list.
func IsAllowedOrigin(origin string) bool {
	return origin != "" && allowedOrigins()[origin]
}

// CORS builds a Gin handler that allows the configured origins to call this gateway
// from a browser. Extra origins can be supplied via the CORS_ORIGINS env var
// (comma-separated), matching the Python backend's convention.
func CORS() gin.HandlerFunc {
	allowed := allowedOrigins()

	return func(c *gin.Context) {
		origin := c.GetHeader("Origin")
		if origin != "" && allowed[origin] {
			c.Header("Access-Control-Allow-Origin", origin)
			c.Header("Access-Control-Allow-Credentials", "true")
			c.Header("Vary", "Origin")
			c.Header("Access-Control-Allow-Methods", "GET, POST, PUT, PATCH, DELETE, OPTIONS")
			c.Header("Access-Control-Allow-Headers", "Authorization, Content-Type, Idempotency-Key")
			// Lets browser code read where a created workspace lives and
			// whether a create was an idempotent replay.
			c.Header("Access-Control-Expose-Headers", "Location, Idempotent-Replayed, X-Request-ID")
		}
		if c.Request.Method == http.MethodOptions {
			c.AbortWithStatus(http.StatusNoContent)
			return
		}
		c.Next()
	}
}
