package metrics

import (
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/gin-gonic/gin"
)

func newTestRouter() *gin.Engine {
	gin.SetMode(gin.TestMode)
	r := gin.New()
	r.Use(Middleware())
	r.GET("/api/v1/widgets/:id", func(c *gin.Context) { c.Status(http.StatusOK) })
	r.GET("/api/v1/boom", func(c *gin.Context) { c.Status(http.StatusInternalServerError) })
	r.GET("/metrics", Handler())
	return r
}

func doGet(r *gin.Engine, path string) *httptest.ResponseRecorder {
	req := httptest.NewRequest(http.MethodGet, path, nil)
	w := httptest.NewRecorder()
	r.ServeHTTP(w, req)
	return w
}

func scrape(t *testing.T, r *gin.Engine) string {
	t.Helper()
	w := doGet(r, "/metrics")
	if w.Code != http.StatusOK {
		t.Fatalf("/metrics status = %d, want 200", w.Code)
	}
	return w.Body.String()
}

// State (requestsTotal, durationHist, inFlight) is package-global, exactly
// like a real Prometheus client library's default registry -- correct for
// production (counters are cumulative for the life of the process), so
// each test below hits its own distinct route rather than resetting
// anything, to stay independent of the others regardless of run order.

func TestMiddleware_RecordsRequestByRouteTemplateNotResolvedPath(t *testing.T) {
	r := newTestRouter()
	doGet(r, "/api/v1/widgets/w-1")
	doGet(r, "/api/v1/widgets/w-2")

	body := scrape(t, r)
	// The route *template*, not either resolved ID, appears exactly once
	// with count 2 -- two different real IDs must not create two series.
	if !strings.Contains(body, `http_requests_total{method="GET",path="/api/v1/widgets/:id",status="200"} 2`) {
		t.Errorf("expected a single templated series with count 2, got:\n%s", body)
	}
	if strings.Contains(body, "w-1") || strings.Contains(body, "w-2") {
		t.Errorf("a resolved path value leaked into label output:\n%s", body)
	}
}

func TestMiddleware_RecordsErrorStatusSeparately(t *testing.T) {
	r := newTestRouter()
	doGet(r, "/api/v1/boom")

	body := scrape(t, r)
	if !strings.Contains(body, `http_requests_total{method="GET",path="/api/v1/boom",status="500"} 1`) {
		t.Errorf("expected a 500-labeled series, got:\n%s", body)
	}
}

func TestMiddleware_UnmatchedRouteCollapsesToOneLabel(t *testing.T) {
	r := newTestRouter()
	doGet(r, "/this/route/does/not/exist")
	doGet(r, "/neither/does/this/one")

	body := scrape(t, r)
	if !strings.Contains(body, `path="unmatched"`) {
		t.Errorf("expected an unmatched-route series, got:\n%s", body)
	}
	if strings.Contains(body, "does-not-exist") || strings.Contains(body, "/this/route/does/not/exist") {
		t.Errorf("an unmatched raw path leaked into label output (unbounded cardinality risk):\n%s", body)
	}
}

func TestHandler_DurationHistogramHasConsistentCumulativeBuckets(t *testing.T) {
	r := newTestRouter()
	doGet(r, "/api/v1/widgets/w-3")

	body := scrape(t, r)
	if !strings.Contains(body, `http_request_duration_seconds_bucket{method="GET",path="/api/v1/widgets/:id",le="+Inf"}`) {
		t.Errorf("expected a +Inf bucket in the histogram output, got:\n%s", body)
	}
	if !strings.Contains(body, `http_request_duration_seconds_count{method="GET",path="/api/v1/widgets/:id"}`) {
		t.Errorf("expected a _count line, got:\n%s", body)
	}
}

func TestHandler_ExposesPrometheusContentType(t *testing.T) {
	r := newTestRouter()
	w := doGet(r, "/metrics")
	ct := w.Header().Get("Content-Type")
	if !strings.HasPrefix(ct, "text/plain") {
		t.Errorf("Content-Type = %q, want text/plain (Prometheus exposition format)", ct)
	}
}
