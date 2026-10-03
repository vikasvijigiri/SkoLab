package middleware

import (
	"net/http"
	"strings"

	"github.com/gin-gonic/gin"
	"github.com/skolab/backend-go/internal/apierror"
)

// SecurityHeaders sets the response headers every JSON API should carry.
// HSTS makes browsers refuse plain HTTP to this host for two years; nosniff
// stops a JSON body from being reinterpreted as script or HTML; API
// responses carry tokens and private data, so no cache may keep them.
func SecurityHeaders() gin.HandlerFunc {
	return func(c *gin.Context) {
		h := c.Writer.Header()
		h.Set("Strict-Transport-Security", "max-age=63072000; includeSubDomains")
		h.Set("X-Content-Type-Options", "nosniff")
		h.Set("X-Frame-Options", "DENY")
		h.Set("Referrer-Policy", "no-referrer")
		h.Set("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'")
		if strings.HasPrefix(c.Request.URL.Path, "/api/") {
			h.Set("Cache-Control", "no-store")
		}
		c.Next()
	}
}

// MaxBodyBytes is the largest request body the gateway reads. The biggest
// legitimate body is a compile: 100,000 characters of LaTeX (at most ~400 KB
// as UTF-8 JSON).
const MaxBodyBytes = 1 << 20

// BodyLimit refuses bodies over limit with 413 before any handler reads
// them. A declared Content-Length is refused up front; a body without one
// (chunked) is cut off at the limit, so a handler sees a read error and
// answers 400 instead of buffering an unbounded body.
func BodyLimit(limit int64) gin.HandlerFunc {
	return func(c *gin.Context) {
		if c.Request.ContentLength > limit {
			apierror.Abort(c, http.StatusRequestEntityTooLarge, "body_too_large", "Request body is too large")
			return
		}
		if c.Request.Body != nil {
			c.Request.Body = http.MaxBytesReader(c.Writer, c.Request.Body, limit)
		}
		c.Next()
	}
}

// RequestIDValid reports whether a caller-supplied X-Request-ID may be
// reused: short and made of characters that are safe in logs and headers.
// Anything else is replaced with a fresh id.
func RequestIDValid(id string) bool {
	if id == "" || len(id) > 128 {
		return false
	}
	for _, r := range id {
		switch {
		case r >= 'a' && r <= 'z', r >= 'A' && r <= 'Z', r >= '0' && r <= '9', r == '-', r == '_', r == '.', r == ':':
		default:
			return false
		}
	}
	return true
}
