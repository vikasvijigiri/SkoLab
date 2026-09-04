package author

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"os"
	"strings"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/skolab/backend-go/internal/cache"
	"github.com/skolab/backend-go/internal/services/openalex"
)

// metrics.go serves GET /author_metrics, ported from the Python
// authors.py::get_author_metrics + metrics_service.py::compute_author_metrics.
//
// Split per decisions/0010: the gateway does the OpenAlex works fetch, owns the
// "not enough recent papers" 422, builds the title/concepts digest, caches the
// result, and returns the byte-identical AuthorMetricsResponse bundle. The one
// model-bound step — parsing that digest into the scored bundle — is a call to
// the Python internal route POST /api/v1/internal/author_metrics_enrich.
//
// Deliberate parity change: when enrichment is unavailable (Python down, LLM
// down, timeout) this returns the empty bundle with 200, not a 503. The Android
// AuthorMetrics model tolerates it via field defaults. /author_metrics is now a
// best-effort enrichment read.

const (
	metricsFetchTimeout = 45 * time.Second
	metricsEnrichPath   = "/api/v1/internal/author_metrics_enrich"
)

var (
	// In-memory, 2 h — same TTL as the Python author_metrics_cache it replaces.
	metricsCache    = cache.New(10 * time.Minute)
	metricsCacheTTL = 2 * time.Hour

	metricsHTTPClient = &http.Client{Timeout: 40 * time.Second}

	// Mirrors main.go's own default for the Python LLM service.
	metricsPythonBackendURL = func() string {
		if v := os.Getenv("PYTHON_BACKEND_URL"); v != "" {
			return v
		}
		return "http://localhost:8000"
	}()

	// The degrade-to-empty response. Field set + defaults match the Android
	// AuthorMetrics data class (network/ApiService.kt).
	emptyMetricsBundle = []byte(`{"overall_score":0,"topic_toughness":0,"velocity":0,"skills":[],"tools":[],"analysis":""}`)
)

// GetAuthorMetrics handles GET /api/v1/author_metrics?author_id=... (public).
func GetAuthorMetrics(c *gin.Context) {
	authorID := strings.TrimSpace(c.Query("author_id"))
	if authorID == "" {
		c.JSON(http.StatusBadRequest, gin.H{"error": "author_id is required"})
		return
	}
	cleanID := cleanAuthorID(authorID)

	if cached, ok := metricsCache.Get(cleanID); ok {
		if raw, isBytes := cached.([]byte); isBytes {
			c.Data(http.StatusOK, "application/json; charset=utf-8", raw)
			return
		}
	}

	ctx, cancel := context.WithTimeout(c.Request.Context(), metricsFetchTimeout)
	defer cancel()

	works, err := openAlexClient.FetchAuthorWorks(ctx, cleanID, "", 10, "")
	if err != nil {
		slog.Warn("author_metrics: works fetch failed", "id", cleanID, "err", err)
		works = nil
	}
	if len(works) == 0 {
		// Parity with the Python ValueError → HTTPException(422).
		c.JSON(http.StatusUnprocessableEntity, gin.H{
			"error": "Not enough recent papers to analyze comprehensively.",
		})
		return
	}

	digest := buildAuthorMetricsContext(works)

	bundle, err := enrichAuthorMetrics(ctx, digest)
	if err != nil {
		slog.Warn("author_metrics: enrichment unavailable, returning empty bundle",
			"id", cleanID, "err", err)
		c.Data(http.StatusOK, "application/json; charset=utf-8", emptyMetricsBundle)
		return
	}

	metricsCache.Set(cleanID, bundle, metricsCacheTTL)
	c.Data(http.StatusOK, "application/json; charset=utf-8", bundle)
}

// buildAuthorMetricsContext mirrors metrics_service.py::build_author_metrics_context
// exactly — one "Title: <t>. Concepts: <c1, c2, ...>" line per work, joined by \n.
func buildAuthorMetricsContext(works []openalex.Work) string {
	lines := make([]string, 0, len(works))
	for _, w := range works {
		names := make([]string, 0, len(w.Concepts))
		for _, cc := range w.Concepts {
			if cc.DisplayName != "" {
				names = append(names, cc.DisplayName)
			}
		}
		lines = append(lines, "Title: "+w.Title+". Concepts: "+strings.Join(names, ", "))
	}
	return strings.Join(lines, "\n")
}

// enrichAuthorMetrics calls the Python internal route with the digest and returns
// its JSON body verbatim (so any extra keys the LLM emits pass through, matching
// the old extra="allow" AuthorMetricsResponse). Any non-200 or transport error
// is returned so the caller can degrade to the empty bundle.
func enrichAuthorMetrics(ctx context.Context, digest string) ([]byte, error) {
	payload, err := json.Marshal(map[string]string{"context": digest})
	if err != nil {
		return nil, err
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost,
		metricsPythonBackendURL+metricsEnrichPath, bytes.NewReader(payload))
	if err != nil {
		return nil, err
	}
	req.Header.Set("Content-Type", "application/json")

	resp, err := metricsHTTPClient.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()

	body, err := io.ReadAll(io.LimitReader(resp.Body, 1<<20))
	if err != nil {
		return nil, err
	}
	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("author_metrics_enrich returned %d", resp.StatusCode)
	}
	// Guard against a proxy/HTML error page slipping through as a 200.
	var probe map[string]any
	if err := json.Unmarshal(body, &probe); err != nil {
		return nil, fmt.Errorf("author_metrics_enrich body is not a JSON object: %w", err)
	}
	return body, nil
}
