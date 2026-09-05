// Package metrics implements RED metrics (Rate, Errors, Duration) for the
// gateway, exposed at GET /metrics in Prometheus's text exposition format
// (https://prometheus.io/docs/instrumenting/exposition_formats/).
//
// infrastructure/prometheus.yml has scraped this exact path
// ("skolab-gateway", metrics_path: /metrics, port 8080) since the local
// observability stack was first stood up — the endpoint itself never
// existed until now.
//
// Hand-rolled against the Go standard library only, not
// github.com/prometheus/client_golang: this machine has no local Go
// toolchain to run `go mod tidy` and produce correct go.sum hashes for a
// new dependency, and a hand-edited go.sum entry that CI's `go build`
// (the default -mod=readonly) can't verify would fail the build outright.
// The output below is the same text format that library produces; a
// standard Prometheus server scrapes it identically either way.
package metrics

import (
	"fmt"
	"net/http"
	"sort"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/gin-gonic/gin"
)

// Bucket boundaries, seconds — Prometheus's own commonly-used HTTP-latency
// defaults, close enough for a single gateway service.
var buckets = []float64{0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1, 2.5, 5, 10}

type counterKey struct {
	method, path, status string
}

type histogram struct {
	mu     sync.Mutex
	counts []uint64 // cumulative per bucket, parallel to `buckets`
	sum    float64
	total  uint64
}

func newHistogram() *histogram {
	return &histogram{counts: make([]uint64, len(buckets))}
}

func (h *histogram) observe(seconds float64) {
	h.mu.Lock()
	defer h.mu.Unlock()
	h.sum += seconds
	h.total++
	for i, boundary := range buckets {
		if seconds <= boundary {
			h.counts[i]++
		}
	}
}

func (h *histogram) snapshot() (counts []uint64, sum float64, total uint64) {
	h.mu.Lock()
	defer h.mu.Unlock()
	counts = make([]uint64, len(h.counts))
	copy(counts, h.counts)
	return counts, h.sum, h.total
}

var (
	requestsTotal sync.Map // counterKey -> *uint64
	durationHist  sync.Map // "method|path" -> *histogram
	inFlight      int64
)

func counterFor(k counterKey) *uint64 {
	v, _ := requestsTotal.LoadOrStore(k, new(uint64))
	return v.(*uint64)
}

func histogramFor(method, path string) *histogram {
	v, _ := durationHist.LoadOrStore(method+"|"+path, newHistogram())
	return v.(*histogram)
}

// Middleware records a request's method, route template, status, and
// duration. Keyed by c.FullPath() (the route *template*, e.g.
// "/api/v1/leaderboard/:field") rather than the resolved path — a request
// carrying a real ID must never create a new, unbounded metric series.
// An unmatched route (404, no registered handler) collapses to a single
// "unmatched" label instead of one series per garbage path a scanner or
// bot might probe.
func Middleware() gin.HandlerFunc {
	return func(c *gin.Context) {
		atomic.AddInt64(&inFlight, 1)
		start := time.Now()

		c.Next()

		atomic.AddInt64(&inFlight, -1)

		path := c.FullPath()
		if path == "" {
			path = "unmatched"
		}
		method := c.Request.Method
		status := strconv.Itoa(c.Writer.Status())

		atomic.AddUint64(counterFor(counterKey{method, path, status}), 1)
		histogramFor(method, path).observe(time.Since(start).Seconds())
	}
}

// Handler serves GET /metrics.
func Handler() gin.HandlerFunc {
	return func(c *gin.Context) {
		var b strings.Builder

		writeRequestsTotal(&b)
		writeDurationHistogram(&b)

		b.WriteString("# HELP http_requests_in_flight Requests currently being handled by the gateway.\n")
		b.WriteString("# TYPE http_requests_in_flight gauge\n")
		fmt.Fprintf(&b, "http_requests_in_flight %d\n", atomic.LoadInt64(&inFlight))

		c.Data(http.StatusOK, "text/plain; version=0.0.4; charset=utf-8", []byte(b.String()))
	}
}

func writeRequestsTotal(b *strings.Builder) {
	b.WriteString("# HELP http_requests_total Total HTTP requests handled by the gateway.\n")
	b.WriteString("# TYPE http_requests_total counter\n")

	type row struct {
		k counterKey
		v uint64
	}
	var rows []row
	requestsTotal.Range(func(k, v any) bool {
		rows = append(rows, row{k.(counterKey), atomic.LoadUint64(v.(*uint64))})
		return true
	})
	sort.Slice(rows, func(i, j int) bool {
		return metricLine(rows[i].k) < metricLine(rows[j].k)
	})
	for _, r := range rows {
		fmt.Fprintf(b, "http_requests_total{method=%q,path=%q,status=%q} %d\n",
			r.k.method, r.k.path, r.k.status, r.v)
	}
}

func metricLine(k counterKey) string {
	return k.method + "|" + k.path + "|" + k.status
}

func writeDurationHistogram(b *strings.Builder) {
	b.WriteString("# HELP http_request_duration_seconds HTTP request latency in seconds.\n")
	b.WriteString("# TYPE http_request_duration_seconds histogram\n")

	var keys []string
	durationHist.Range(func(k, _ any) bool {
		keys = append(keys, k.(string))
		return true
	})
	sort.Strings(keys)

	for _, hk := range keys {
		v, _ := durationHist.Load(hk)
		h := v.(*histogram)
		counts, sum, total := h.snapshot()

		parts := strings.SplitN(hk, "|", 2)
		method, path := parts[0], parts[1]

		for i, boundary := range buckets {
			fmt.Fprintf(b, "http_request_duration_seconds_bucket{method=%q,path=%q,le=%q} %d\n",
				method, path, strconv.FormatFloat(boundary, 'f', -1, 64), counts[i])
		}
		fmt.Fprintf(b, "http_request_duration_seconds_bucket{method=%q,path=%q,le=\"+Inf\"} %d\n",
			method, path, total)
		fmt.Fprintf(b, "http_request_duration_seconds_sum{method=%q,path=%q} %s\n",
			method, path, strconv.FormatFloat(sum, 'f', -1, 64))
		fmt.Fprintf(b, "http_request_duration_seconds_count{method=%q,path=%q} %d\n",
			method, path, total)
	}
}
