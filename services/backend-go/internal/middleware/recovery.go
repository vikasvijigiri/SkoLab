// Package middleware: structured panic recovery.
//
// gin.Recovery() (the default) writes its panic + stack trace to os.Stderr in
// gin's own plain-text format, completely bypassing the slog JSON pipeline
// every other log line in this service goes through — in production that
// meant a panic was invisible to anything reading structured logs (2026-09-26
// observability audit: the Python service classifies and ships errors via
// Sentry, sentry-go is not yet wired up here for a real error dashboard —
// `go get github.com/getsentry/sentry-go` needs a Go toolchain this
// environment doesn't have — but a panic should be a searchable, correlated
// log line either way in the meantime).
package middleware

import (
	"fmt"
	"log/slog"
	"net/http"
	"runtime/debug"

	"github.com/gin-gonic/gin"
	"github.com/skolab/backend-go/internal/apierror"
)

// Recovery replaces gin.Recovery(): same "don't crash the process" behavior,
// but the panic + stack trace go through slog (JSON in production) tagged
// with the same request_id the rest of this request's logs use, instead of
// an unstructured stderr dump.
func Recovery() gin.HandlerFunc {
	return func(c *gin.Context) {
		defer func() {
			if rec := recover(); rec != nil {
				slog.Error("panic recovered",
					"panic", fmt.Sprintf("%v", rec),
					"stack", string(debug.Stack()),
					"method", c.Request.Method,
					"path", c.Request.URL.Path,
					"request_id", c.GetString("request_id"),
				)
				// The standard error body, unless the handler already started
				// writing its response (then the status can no longer change).
				if c.Writer.Written() {
					c.Abort()
					return
				}
				apierror.Abort(c, http.StatusInternalServerError, "internal_error", "Internal server error")
			}
		}()
		c.Next()
	}
}
