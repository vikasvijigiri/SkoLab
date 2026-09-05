package middleware

import (
	"compress/gzip"
	"net/http"
	"strings"

	"github.com/gin-gonic/gin"
)

// gzipResponseWriter overrides Write/WriteString to go through a gzip.Writer
// instead of the underlying connection, and overrides Flush to match --
// every other method (Status, Header, Hijack, ...) is promoted unchanged
// from the embedded gin.ResponseWriter, the standard shape for this kind of
// wrapper.
//
// The Flush override is not optional. httputil.ReverseProxy flushes its
// destination writer after every chunk read from an upstream response with
// no Content-Length (Go's http.Response reports ContentLength == -1 for a
// chunked, streaming-shaped response -- every LLM route here, since their
// response size isn't known up front) -- confirmed live: a real
// /discovery/predict response, proxied from Python, came back as
// undecodable binary garbage on every attempt, while the same route hit
// directly against skolab-backend-py (no gateway, no gzip wrapping) came
// back as clean JSON every time, and a native (non-proxied) Go gzip route
// (citation_heatmap) decompressed correctly every time too -- isolating the
// break to exactly this proxied-and-chunked combination. Without this
// override, ReverseProxy's Flush() call reaches the embedded
// gin.ResponseWriter directly, bypassing gz's own internal buffer entirely
// -- gzip.Writer decides when to release compressed bytes to its
// underlying writer on its own schedule, unrelated to when something else
// flushes that writer, so a flush arriving between two of gzip's internal
// writes ships a gzip stream cut at a boundary that has nothing to do with
// a valid frame boundary. Flushing gz itself first keeps the two in sync.
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

func (w *gzipResponseWriter) Flush() {
	_ = w.gz.Flush()
	if f, ok := w.ResponseWriter.(http.Flusher); ok {
		f.Flush()
	}
}

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

		gz := gzip.NewWriter(c.Writer)
		defer gz.Close()

		c.Header("Content-Encoding", "gzip")
		c.Header("Vary", "Accept-Encoding")
		// The compressed length isn't known up front, and an incorrect
		// Content-Length (the original, uncompressed size) would make a
		// client stop reading early or flag a truncated response.
		c.Writer.Header().Del("Content-Length")

		c.Writer = &gzipResponseWriter{ResponseWriter: c.Writer, gz: gz}
		c.Next()
	}
}
