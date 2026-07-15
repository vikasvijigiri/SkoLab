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

// CORS builds a Gin handler that allows the configured origins to call this gateway
// from a browser. Extra origins can be supplied via the CORS_ORIGINS env var
// (comma-separated), matching the Python backend's convention.
func CORS() gin.HandlerFunc {
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
