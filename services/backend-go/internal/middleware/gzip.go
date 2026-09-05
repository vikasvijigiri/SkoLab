package middleware

import (
	"compress/gzip"
	"io"
	"strings"

	"github.com/gin-gonic/gin"
)

// gzipResponseWriter overrides Write/WriteString to go through a gzip.Writer
// instead of the underlying connection. Every other method (Status, Header,
// Flush, Hijack, ...) is promoted unchanged from the embedded
// gin.ResponseWriter -- the standard shape for this kind of wrapper.
type gzipResponseWriter struct {
	gin.ResponseWriter
	gz io.Writer
}

func (w *gzipResponseWriter) Write(b []byte) (int, error) {
	return w.gz.Write(b)
}

func (w *gzipResponseWriter) WriteString(s string) (int, error) {
	return w.gz.Write([]byte(s))
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
