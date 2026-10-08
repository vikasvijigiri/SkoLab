package middleware

import (
	"net/http"
	"net/url"
	"strings"
	"unicode/utf8"

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

// Storable reports whether s can be stored in or compared against a
// Postgres text column. Postgres refuses NUL bytes and invalid UTF-8 with an
// error, which would otherwise surface as a 503 instead of the caller's 4xx.
func Storable(s string) bool {
	return utf8.ValidString(s) && !strings.ContainsRune(s, 0)
}

// ValidQuery refuses a query string that cannot be decoded (a stray "%",
// say) with 400. Go's parser would otherwise drop the broken parameter
// silently, and the handler would answer as if it had never been sent.
// Values that decode to a NUL byte or invalid UTF-8 are refused the same way.
func ValidQuery() gin.HandlerFunc {
	return func(c *gin.Context) {
		values, err := url.ParseQuery(c.Request.URL.RawQuery)
		if err != nil || !storableQuery(values) {
			apierror.Abort(c, http.StatusBadRequest, "invalid_query", "The query string is malformed")
			return
		}
		c.Next()
	}
}

func storableQuery(values url.Values) bool {
	for name, list := range values {
		if !Storable(name) {
			return false
		}
		for _, value := range list {
			if !Storable(value) {
				return false
			}
		}
	}
	return true
}

// ValidPath answers 404 when the decoded path holds a NUL byte or invalid
// UTF-8 (%00 or %ff in an id, say). No resource can have such an id, and
// passing it on would reach Postgres and fail as a 503.
func ValidPath() gin.HandlerFunc {
	return func(c *gin.Context) {
		if !Storable(c.Request.URL.Path) {
			apierror.Abort(c, http.StatusNotFound, "not_found", "Not found")
			return
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
