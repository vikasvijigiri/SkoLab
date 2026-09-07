// Package pulse aggregates public science-news feeds into one recency-sorted
// list for the Home screen. Pure fetch + XML parse + in-memory cache, no LLM,
// no database — so it lives on the Go edge like internal/activity.
//
// Sources are curated, reputable, and publish full RSS/Atom: Quanta Magazine,
// Phys.org (whose feed licence explicitly permits commercial use as long as
// headlines/links are unaltered), ScienceDaily and Nature news. We store only
// headline + link + short summary + source, and always link out.
package pulse

import (
	"context"
	"io"
	"net/http"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/gin-gonic/gin"
)

type feedSource struct {
	name string
	url  string
}

var sources = []feedSource{
	{"Quanta Magazine", "https://www.quantamagazine.org/feed/"},
	{"Phys.org", "https://phys.org/rss-feed/"},
	{"ScienceDaily", "https://www.sciencedaily.com/rss/top/science.xml"},
	{"Nature", "https://www.nature.com/nature.rss"},
}

var httpClient = &http.Client{Timeout: 8 * time.Second}

// ── tiny TTL cache ────────────────────────────────────────────────────────────

type cacheEntry struct {
	items []NewsItem
	at    time.Time
}

var (
	mu    sync.RWMutex
	cache = map[string]cacheEntry{}
	ttl   = 45 * time.Minute
)

func cached(key string) ([]NewsItem, bool) {
	mu.RLock()
	defer mu.RUnlock()
	e, ok := cache[key]
	if !ok || time.Since(e.at) > ttl {
		return nil, false
	}
	return e.items, true
}

func store(key string, items []NewsItem) {
	mu.Lock()
	cache[key] = cacheEntry{items: items, at: time.Now()}
	mu.Unlock()
}

// ── fetch + merge ─────────────────────────────────────────────────────────────

func fetchAll(ctx context.Context) []NewsItem {
	var (
		wg  sync.WaitGroup
		mx  sync.Mutex
		all []NewsItem
	)
	for _, s := range sources {
		wg.Add(1)
		go func(src feedSource) {
			defer wg.Done()
			req, err := http.NewRequestWithContext(ctx, http.MethodGet, src.url, nil)
			if err != nil {
				return
			}
			req.Header.Set("User-Agent", "SkoLab/1.0 (+https://skolab-web.onrender.com)")
			req.Header.Set("Accept", "application/rss+xml, application/atom+xml, application/xml, text/xml")
			resp, err := httpClient.Do(req)
			if err != nil {
				return
			}
			defer resp.Body.Close()
			if resp.StatusCode != http.StatusOK {
				return
			}
			body, err := io.ReadAll(io.LimitReader(resp.Body, 4<<20))
			if err != nil {
				return
			}
			items := parseFeed(body, src.name)
			mx.Lock()
			all = append(all, items...)
			mx.Unlock()
		}(s)
	}
	wg.Wait()

	// De-dupe by URL, newest first.
	seen := map[string]bool{}
	uniq := make([]NewsItem, 0, len(all))
	for _, it := range all {
		if it.URL == "" || seen[it.URL] {
			continue
		}
		seen[it.URL] = true
		uniq = append(uniq, it)
	}
	sort.SliceStable(uniq, func(i, j int) bool { return uniq[i].Published > uniq[j].Published })
	return uniq
}

// matchesField keeps items whose title or summary mentions any token of the
// field name. Falls back to "keep" when the field is empty.
func matchesField(it NewsItem, tokens []string) bool {
	if len(tokens) == 0 {
		return true
	}
	hay := strings.ToLower(it.Title + " " + it.Summary)
	for _, t := range tokens {
		if len(t) >= 4 && strings.Contains(hay, t) {
			return true
		}
	}
	return false
}

func fieldTokens(field string) []string {
	field = strings.TrimSpace(strings.ToLower(field))
	if field == "" {
		return nil
	}
	var out []string
	for _, w := range strings.Fields(field) {
		w = strings.Trim(w, ",.;:()")
		if len(w) >= 4 && w != "and" && w != "the" {
			out = append(out, w)
		}
	}
	return out
}

func clampLimit(raw string, def, max int) int {
	n, err := strconv.Atoi(raw)
	if err != nil || n <= 0 {
		return def
	}
	if n > max {
		return max
	}
	return n
}

// ── GET /api/v1/science_news ──────────────────────────────────────────────────

// GetScienceNews handles GET /api/v1/science_news?field=&limit=.
// field   — optional topical filter (a research-field name); items unrelated to
//
//	it are dropped, but if that empties the list the unfiltered set is
//	returned so the section is never blank.
//
// limit   — default 8, max 20.
func GetScienceNews(c *gin.Context) {
	limit := clampLimit(c.Query("limit"), 8, 20)

	all, ok := cached("all")
	if !ok {
		all = fetchAll(c.Request.Context())
		store("all", all)
	}

	tokens := fieldTokens(c.Query("field"))
	items := all
	if len(tokens) > 0 {
		filtered := make([]NewsItem, 0, len(all))
		for _, it := range all {
			if matchesField(it, tokens) {
				filtered = append(filtered, it)
			}
		}
		if len(filtered) >= 3 {
			items = filtered
		}
	}

	if len(items) > limit {
		items = items[:limit]
	}
	if items == nil {
		items = []NewsItem{}
	}
	c.JSON(http.StatusOK, gin.H{"items": items})
}
