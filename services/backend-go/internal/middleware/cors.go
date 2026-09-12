package middleware

import (
	"net/http"
	"os"
	"strings"

	"github.com/gin-gonic/gin"
)

// defaultCORSOrigins mirrors the Python backend's allow-list (services/backend/app/main.py)
// so both services accept the same local dev origins out of the box.
var defaultCORSOrigins = []string{
	"http://localhost",
	"http://localhost:3000",
	"http://127.0.0.1",
	"http://127.0.0.1:3000",
}

// allowedOrigins builds the same origin allow-list CORS() uses, from
// defaultCORSOrigins plus the comma-separated CORS_ORIGINS env var. Exported
// as IsAllowedOrigin below so non-HTTP callers (the websocket upgrader's
// CheckOrigin, which has no CORS preflight of its own) can apply the exact
// same policy instead of accepting every origin.
func allowedOrigins() map[string]bool {
	allowed := make(map[string]bool)
	for _, o := range defaultCORSOrigins {
		allowed[o] = true
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
			c.Header("Access-Control-Allow-Headers", "Authorization, Content-Type")
		}
		if c.Request.Method == http.MethodOptions {
			c.AbortWithStatus(http.StatusNoContent)
			return
		}
		c.Next()
	}
}
