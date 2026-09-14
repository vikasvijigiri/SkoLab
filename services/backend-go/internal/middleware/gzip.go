package middleware

import (
	"bytes"
	"compress/gzip"
	"strconv"
	"strings"

	"github.com/gin-gonic/gin"
)

// gzipResponseWriter overrides Write/WriteString/Flush to send every byte
// into an in-memory buffer via gzip.Writer instead of the underlying
// connection -- every other method (Status, Header, Hijack, ...) is
// promoted unchanged from the embedded gin.ResponseWriter, the standard
// shape for this kind of wrapper.
//
// Flush is deliberately a no-op. An earlier version of this type flushed
// gz and the real connection together on every Flush() call, matching how
// httputil.ReverseProxy flushes after each upstream chunk -- correct for
// that proxied path (see main.go's reverseProxy/ModifyResponse comment for
// the chunked-response corruption that fix addresses), but this type wraps
// *native* Gin routes, and confirmed live (2026-09-14) the same class of
// bug reaches them too: /observability (metrics.Handler, pure in-memory
// text) and /api/v1/author_stats with a real author_id (a genuinely large
// JSON bundle) both 502'd at Render's edge with x-render-routing:
// no-deploy on every attempt -- while the exact same request in the app's
// own structured log showed a clean 200, and a small response on the same
// route (author_stats with an invalid id, a one-line JSON error) succeeded
// every time. That is response-size-dependent corruption isolated to
// multi-flush gzip output, the same signature as the already-fixed proxy
// bug, just on the writer side that streams straight to the connection
// instead of through ReverseProxy. Buffering the whole compressed body and
// writing it once, with a correct Content-Length instead of chunked
// transfer, removes the multiple flushes entirely.
type gzipResponseWriter struct {
	gin.ResponseWriter
	gz *gzip.Writer
}

func (w *gzipResponseWriter) Write(b []byte) (int, error) {
	return w.gz.Write(b)
}

func (w *gzipResponseWriter) WriteString(s string) (int, error) {
	return w.gz.Write([]byte(s))
}

func (w *gzipResponseWriter) Flush() {}

// Gzip compresses response bodies for any client that sends
// Accept-Encoding: gzip -- every JSON response this gateway serves or
// proxies benefits, and gzip's CPU cost is negligible next to the network
// transfer time it saves, especially over a slower connection.
//
// Skips a WebSocket upgrade request entirely: wrapping c.Writer would
// interfere with the handshake response, and the connection gets
// Hijack()'d immediately after anyway, bypassing normal response writing.
func Gzip() gin.HandlerFunc {
	return func(c *gin.Context) {
		if !strings.Contains(c.GetHeader("Accept-Encoding"), "gzip") {
			c.Next()
			return
		}
		if strings.EqualFold(c.GetHeader("Upgrade"), "websocket") {
			c.Next()
			return
		}

		real := c.Writer
		buf := &bytes.Buffer{}
		gz := gzip.NewWriter(buf)

		c.Writer = &gzipResponseWriter{ResponseWriter: real, gz: gz}
		c.Next()
		_ = gz.Close()

		if buf.Len() == 0 {
			// Nothing was ever written (e.g. a bare WriteHeader(204)) --
			// leave real's already-recorded status for Gin's own
			// end-of-request WriteHeaderNow() to flush, untouched by
			// gzip headers that would be meaningless on an empty body.
			return
		}

		real.Header().Set("Content-Encoding", "gzip")
		real.Header().Set("Vary", "Accept-Encoding")
		// Known up front now that the whole compressed body is buffered --
		// a correct Content-Length (not chunked transfer) is exactly what
		// stopped triggering the edge-side corruption described above.
		real.Header().Set("Content-Length", strconv.Itoa(buf.Len()))
		_, _ = real.Write(buf.Bytes())
	}
}
