package author

import (
	"context"
	"encoding/json"
	"log/slog"
	"math"
	"net/http"
	"sort"
	"strings"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/skolab/backend-go/internal/cache"
	"github.com/skolab/backend-go/internal/firestore"
)

// heatmap.go ports app/services/platform/pipeline/heatmap.py::
// HeatmapMixin.get_citation_heatmap. It is the only author route with no LLM and
// no embedding, so it moves to the Go gateway whole.
//
// Tier order is unchanged from Python:
//  1. Postgres cache_entries  (cache.PgGet, name "pipeline")
//  2. Firestore mirror        (citation_heatmaps/<cleanID>)
//  3. compute from OpenAlex   (counts_by_year), then write both tiers
//
// One deliberate change: Python added random.randint(2, 6) to institutional_reach.
// That fudge is DROPPED here — the endpoint returns the deterministic estimate.

const (
	heatmapCacheName = "pipeline"
	heatmapCacheTTL  = time.Hour
	heatmapTimeout   = 20 * time.Second
)

// CitationHeatmap mirrors app/schemas/authors_extra.py::CitationHeatmap
// (which is extra="allow", so matching the field names is what matters).
type CitationHeatmap struct {
	Years              []int   `json:"years"`
	Citations          []int   `json:"citations"`
	Works              []int   `json:"works"`
	InstitutionalReach float64 `json:"institutional_reach"`
	HIndex             int     `json:"h_index"`
}

// emptyHeatmap is the profile-missing response — Python returns the same zero
// shape with explicitly empty lists.
func emptyHeatmap() CitationHeatmap {
	return CitationHeatmap{Years: []int{}, Citations: []int{}, Works: []int{}}
}

// GetCitationHeatmap handles GET /api/v1/citation_heatmap?author_id=...
func GetCitationHeatmap(c *gin.Context) {
	authorID := strings.TrimSpace(c.Query("author_id"))
	if authorID == "" {
		c.JSON(http.StatusBadRequest, gin.H{"error": "author_id is required"})
		return
	}
	cleanID := cleanAuthorID(authorID)
	cacheKey := "citation_heatmap_" + cleanID

	ctx, cancel := context.WithTimeout(c.Request.Context(), heatmapTimeout)
	defer cancel()

	// ── Tier 1 — Postgres cache_entries ──────────────────────────────────────
	if raw, ok := cache.PgGet(ctx, heatmapCacheName, cacheKey); ok {
		var hm CitationHeatmap
		if err := json.Unmarshal(raw, &hm); err == nil {
			slog.Info("citation_heatmap: postgres cache hit", "author_id", cleanID)
			c.JSON(http.StatusOK, hm)
			return
		}
		slog.Warn("citation_heatmap: postgres cache entry unparseable, recomputing", "author_id", cleanID)
	}

	// ── Tier 2 — Firestore mirror ───────────────────────────────────────────
	if doc, found, err := firestore.GetDoc(ctx, "citation_heatmaps", cleanID); err != nil {
		slog.Warn("citation_heatmap: firestore get failed", "author_id", cleanID, "err", err)
	} else if found {
		delete(doc, "last_synced")
		hm := heatmapFromDoc(doc)
		if err := cache.PgSet(ctx, heatmapCacheName, cacheKey, hm, heatmapCacheTTL); err != nil {
			slog.Warn("citation_heatmap: postgres warm after firestore hit failed", "author_id", cleanID, "err", err)
		}
		slog.Info("citation_heatmap: firestore cache hit", "author_id", cleanID)
		c.JSON(http.StatusOK, hm)
		return
	}

	// ── Tier 3 — compute from OpenAlex counts_by_year ───────────────────────
	counts, hIndex, err := openAlexClient.FetchAuthorCountsByYear(ctx, cleanID)
	if err != nil {
		slog.Warn("citation_heatmap: author fetch failed", "author_id", cleanID, "err", err)
		c.JSON(http.StatusOK, emptyHeatmap())
		return
	}

	sort.Slice(counts, func(i, j int) bool { return counts[i].Year < counts[j].Year })
	// Keep the last 8 years for a compact mobile layout (Python: counts[-8:]).
	if len(counts) > 8 {
		counts = counts[len(counts)-8:]
	}

	years := make([]int, 0, len(counts))
	citations := make([]int, 0, len(counts))
	works := make([]int, 0, len(counts))
	for _, cy := range counts {
		years = append(years, cy.Year)
		citations = append(citations, cy.CitedByCount)
		works = append(works, cy.WorksCount)
	}

	if hIndex == 0 {
		hIndex = 5 // Python: int((summary_stats or {}).get("h_index") or 5)
	}
	// Python: min(int(h_index * 1.5) + random.randint(2, 6), 35). Fudge dropped.
	reach := math.Trunc(float64(hIndex) * 1.5)
	if reach > 35 {
		reach = 35
	}

	result := CitationHeatmap{
		Years:              years,
		Citations:          citations,
		Works:              works,
		InstitutionalReach: reach,
		HIndex:             hIndex,
	}

	if err := cache.PgSet(ctx, heatmapCacheName, cacheKey, result, heatmapCacheTTL); err != nil {
		slog.Warn("citation_heatmap: postgres cache write failed", "author_id", cleanID, "err", err)
	}
	if firestore.Available() {
		mirror := map[string]any{
			"years":               years,
			"citations":           citations,
			"works":               works,
			"institutional_reach": reach,
			"h_index":             hIndex,
			"last_synced":         firestore.ServerTimestamp,
		}
		if err := firestore.SetDoc(ctx, "citation_heatmaps", cleanID, mirror); err != nil {
			slog.Warn("citation_heatmap: firestore mirror write failed", "author_id", cleanID, "err", err)
		}
	}

	c.JSON(http.StatusOK, result)
}

// heatmapFromDoc coerces a Firestore document map into CitationHeatmap. Firestore
// hands back numbers as int64 and arrays as []interface{}; a JSON round-trip is
// the least error-prone coercion and matches Python handing the raw _fs_cached
// dict straight to pydantic.
func heatmapFromDoc(doc map[string]any) CitationHeatmap {
	hm := emptyHeatmap()
	b, err := json.Marshal(doc)
	if err != nil {
		return hm
	}
	_ = json.Unmarshal(b, &hm)
	if hm.Years == nil {
		hm.Years = []int{}
	}
	if hm.Citations == nil {
		hm.Citations = []int{}
	}
	if hm.Works == nil {
		hm.Works = []int{}
	}
	return hm
}
