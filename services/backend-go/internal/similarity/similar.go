package similarity

import (
	"bytes"
	"context"
	"encoding/json"
	"io"
	"log/slog"
	"net/http"
	"os"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/skolab/backend-go/internal/services/openalex"
)

var (
	oaClient   = openalex.New()
	httpClient = &http.Client{Timeout: 15 * time.Second}
)

// ── response shapes ────────────────────────────────────────────────────────

type SimilarPaper struct {
	WorkID  string   `json:"work_id"`
	Title   string   `json:"title"`
	Authors []string `json:"authors"`
	Year    int      `json:"year"`
	Score   float64  `json:"score"`
	Why     string   `json:"why"`
}

type SimilarPapersResponse struct {
	Results  []SimilarPaper `json:"results"`
	Degraded bool           `json:"degraded"`
}

type SimilarResearcher struct {
	AuthorID           string  `json:"author_id"`
	DisplayName        string  `json:"display_name"`
	Institution        string  `json:"institution"`
	FieldOfStudy       string  `json:"field_of_study"`
	HIndex             int     `json:"h_index"`
	Score              float64 `json:"score"`
	Why                string  `json:"why"`
	SharedCollaborators int    `json:"shared_collaborators"`
}

type SimilarResearchersResponse struct {
	Results  []SimilarResearcher `json:"results"`
	Degraded bool                `json:"degraded"`
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

func pythonBase() string {
	b := os.Getenv("PYTHON_BACKEND_URL")
	if b == "" {
		b = "http://localhost:8000"
	}
	return strings.TrimRight(b, "/")
}

// ── GET /api/v1/similar_papers ─────────────────────────────────────────────

// GetSimilarPapers ranks papers most like ?work_id= by embedding cosine, with
// small bonuses for shared concepts and bibliographic coupling, then MMR for
// diversity. Cold query work → one Python embed callback, else OpenAlex
// related_works as a degraded fallback.
func GetSimilarPapers(c *gin.Context) {
	ctx := c.Request.Context()
	workID := cleanID(c.Query("work_id"))
	if workID == "" {
		c.JSON(http.StatusBadRequest, gin.H{"error": "work_id is required"})
		return
	}
	limit := clampLimit(c.Query("limit"), 10, 25)

	q, ok := getWork(ctx, workID)
	if !ok {
		ensureWorkVector(ctx, workID)
		q, ok = getWork(ctx, workID)
	}
	if !ok {
		c.JSON(http.StatusOK, relatedWorksFallback(ctx, workID, limit))
		return
	}

	cands := knnWorks(ctx, q.Vec, workID, 200)
	if len(cands) == 0 {
		c.JSON(http.StatusOK, relatedWorksFallback(ctx, workID, limit))
		return
	}

	type scored struct {
		row    workRow
		score  float64
		cosine float64
		biblio int
	}
	ranked := make([]scored, 0, len(cands))
	for _, cand := range cands {
		cos := 1 - cand.Dist
		if cos < 0 {
			cos = 0
		}
		conFrac := jaccardSim(q.Concepts, cand.Concepts)
		biblioShared := countShared(q.Refs, cand.Refs)
		biblioFrac := overlapFrac(q.Refs, cand.Refs)
		ranked = append(ranked, scored{
			row:    cand,
			score:  cos + 0.15*conFrac + 0.15*biblioFrac,
			cosine: cos,
			biblio: biblioShared,
		})
	}

	items := make([]mmrItem, len(ranked))
	for i, r := range ranked {
		items[i] = mmrItem{relevance: r.score, vec: r.row.Vec}
	}
	order := mmrSelect(items, limit)

	pick := make([]scored, 0, len(order))
	ids := make([]string, 0, len(order))
	for _, idx := range order {
		pick = append(pick, ranked[idx])
		ids = append(ids, ranked[idx].row.ID)
	}
	meta := hydrateWorks(ctx, ids)

	out := SimilarPapersResponse{Results: make([]SimilarPaper, 0, len(pick))}
	for _, r := range pick {
		m := meta[r.row.ID]
		why := formatPct(r.cosine) + " topical"
		if r.biblio == 1 {
			why += " · 1 shared reference"
		} else if r.biblio > 1 {
			why += " · " + strconv.Itoa(r.biblio) + " shared references"
		}
		yr := r.row.Year
		if yr == 0 {
			yr = m.year
		}
		out.Results = append(out.Results, SimilarPaper{
			WorkID:  r.row.ID,
			Title:   orDefault(r.row.Title, m.title),
			Authors: m.authors,
			Year:    yr,
			Score:   round4(r.score),
			Why:     why,
		})
	}
	c.JSON(http.StatusOK, out)
}

// ── GET /api/v1/similar_researchers ───────────────────────────────────────

// GetSimilarResearchers blends embedding cosine, shared-collaborator Jaccard,
// shared-concept Jaccard and same-institution into one score, drops people the
// caller (?user_id=) is already connected to, then MMR-diversifies. Cold
// author → enqueue teleport + degraded OpenAlex fallback.
func GetSimilarResearchers(c *gin.Context) {
	ctx := c.Request.Context()
	authorID := cleanID(c.Query("author_id"))
	if authorID == "" {
		c.JSON(http.StatusBadRequest, gin.H{"error": "author_id is required"})
		return
	}
	limit := clampLimit(c.Query("limit"), 8, 25)
	userID := c.Query("user_id")
	excludeConnected := c.DefaultQuery("exclude_connected", "true") != "false"

	me, ok := getAuthor(ctx, authorID)
	if !ok {
		enqueueTeleport(authorID)
		c.JSON(http.StatusOK, openAlexResearcherFallback(ctx, authorID, limit))
		return
	}

	excl := map[string]bool{cleanID(authorID): true}
	if excludeConnected {
		for id := range excludedPeers(ctx, userID) {
			excl[id] = true
		}
		for _, co := range me.Coauthors {
			excl[cleanID(co)] = true
		}
	}

	cands := knnAuthors(ctx, me.Vec, authorID, 150)
	scored := make([]scoredResearcher, 0, len(cands))
	for _, cand := range cands {
		if excl[cleanID(cand.ID)] {
			continue
		}
		scored = append(scored, blendResearcher(me, cand))
	}
	if len(scored) == 0 {
		enqueueTeleport(authorID)
		c.JSON(http.StatusOK, openAlexResearcherFallback(ctx, authorID, limit))
		return
	}
	sortByScoreDesc(scored)

	items := make([]mmrItem, len(scored))
	for i, s := range scored {
		items[i] = mmrItem{relevance: s.score, vec: s.row.Vec}
	}
	order := mmrSelect(items, limit)

	myField := firstOrEmpty(me.Concepts)
	out := SimilarResearchersResponse{Results: make([]SimilarResearcher, 0, len(order))}
	ids := make([]string, len(order))
	for i, idx := range order {
		ids[i] = scored[idx].row.ID
	}
	names := hydrateResearchers(ctx, ids)
	for _, idx := range order {
		s := scored[idx]
		nm := names[s.row.ID]
		field := firstOrEmpty(s.row.Concepts)
		if field == "" {
			field = myField
		}
		out.Results = append(out.Results, SimilarResearcher{
			AuthorID:            s.row.ID,
			DisplayName:         orDefault(nm.name, "Researcher"),
			Institution:         orDefault(s.row.Institution, nm.institution),
			FieldOfStudy:        field,
			HIndex:              maxInt(s.row.HIndex, nm.hIndex),
			Score:               round4(s.score),
			Why:                 whyResearcher(s, field),
			SharedCollaborators: s.shared,
		})
	}
	c.JSON(http.StatusOK, out)
}

// ── Python callbacks ──────────────────────────────────────────────────────

// ensureWorkVector asks Python to embed + persist one work, then returns. Best
// effort: any failure just means the caller falls back to related_works.
func ensureWorkVector(ctx context.Context, workID string) {
	body, _ := json.Marshal(map[string]string{"work_id": workID})
	req, err := http.NewRequestWithContext(ctx, http.MethodPost,
		pythonBase()+"/api/v1/internal/similar/embed_work", bytes.NewReader(body))
	if err != nil {
		return
	}
	req.Header.Set("Content-Type", "application/json")
	if tok := os.Getenv("INTERNAL_API_TOKEN"); tok != "" {
		req.Header.Set("X-Internal-Token", tok)
	}
	resp, err := httpClient.Do(req)
	if err != nil {
		slog.Warn("similarity: embed_work callback failed", "work", workID, "err", err)
		return
	}
	defer resp.Body.Close()
	_, _ = io.Copy(io.Discard, resp.Body)
}

// enqueueTeleport fire-and-forgets the Python enrichment worker so a later call
// finds a vector. Mirrors author.fireTeleport (not imported to avoid a cycle).
func enqueueTeleport(authorID string) {
	endpoint := pythonBase() + "/api/v1/internal/teleport/" + cleanID(authorID)
	tok := os.Getenv("INTERNAL_API_TOKEN")
	go func() {
		req, err := http.NewRequestWithContext(context.Background(), http.MethodPost, endpoint, nil)
		if err != nil {
			return
		}
		if tok != "" {
			req.Header.Set("X-Internal-Token", tok)
		}
		resp, err := httpClient.Do(req)
		if err != nil {
			slog.Warn("similarity: teleport enqueue failed", "author", authorID, "err", err)
			return
		}
		defer resp.Body.Close()
		_, _ = io.Copy(io.Discard, resp.Body)
	}()
}

// ── OpenAlex fallbacks (used until the vector store warms up) ───────────────

func relatedWorksFallback(ctx context.Context, workID string, limit int) SimilarPapersResponse {
	out := SimilarPapersResponse{Degraded: true, Results: []SimilarPaper{}}
	works, err := oaClient.FetchRelatedWorks(ctx, workID, limit)
	if err != nil {
		return out
	}
	for _, w := range works {
		if len(out.Results) >= limit {
			break
		}
		out.Results = append(out.Results, SimilarPaper{
			WorkID:  cleanID(w.ID),
			Title:   w.Title,
			Authors: authorNames(w.Authorships, 4),
			Year:    w.PublicationYear,
			Why:     "OpenAlex related work",
		})
	}
	return out
}

// openAlexResearcherFallback: fetch the author's topics, search works on the
// top topic, take distinct co-authors, hydrate a few. Self-contained so this
// package never imports the author package.
func openAlexResearcherFallback(ctx context.Context, authorID string, limit int) SimilarResearchersResponse {
	out := SimilarResearchersResponse{Degraded: true, Results: []SimilarResearcher{}}
	a, err := oaClient.FetchAuthorByID(ctx, authorID)
	if err != nil || a == nil {
		return out
	}
	field, expertise := openalex.ExtractFieldAndExpertise(a)
	topic := field
	if len(expertise) > 0 {
		topic = expertise[0]
	}
	if topic == "" || strings.EqualFold(topic, "multidisciplinary") {
		return out
	}
	works, err := oaClient.SearchWorks(ctx, topic, 20, "")
	if err != nil {
		return out
	}
	seen := map[string]bool{cleanID(authorID): true}
	var ids []string
	for _, w := range works {
		for _, as := range w.Authorships {
			id := cleanID(as.Author.ID)
			if id == "" || seen[id] {
				continue
			}
			seen[id] = true
			ids = append(ids, id)
			if len(ids) >= limit {
				break
			}
		}
		if len(ids) >= limit {
			break
		}
	}
	names := hydrateResearchers(ctx, ids)
	for _, id := range ids {
		n := names[id]
		out.Results = append(out.Results, SimilarResearcher{
			AuthorID:     id,
			DisplayName:  orDefault(n.name, "Researcher"),
			Institution:  n.institution,
			FieldOfStudy: field,
			HIndex:       n.hIndex,
			Why:          "works on " + topic,
		})
	}
	return out
}

// ── hydration helpers ─────────────────────────────────────────────────────

type profileLite struct {
	name        string
	institution string
	hIndex      int
}

// hydrateResearchers concurrently fetches display names / institutions for a
// small id set from OpenAlex. Bounded — callers pass ≤25 ids.
func hydrateResearchers(ctx context.Context, ids []string) map[string]profileLite {
	res := make(map[string]profileLite, len(ids))
	if len(ids) == 0 {
		return res
	}
	var mu sync.Mutex
	var wg sync.WaitGroup
	sem := make(chan struct{}, 6)
	for _, id := range ids {
		wg.Add(1)
		go func(id string) {
			defer wg.Done()
			sem <- struct{}{}
			defer func() { <-sem }()
			a, err := oaClient.FetchAuthorByID(ctx, id)
			if err != nil || a == nil {
				return
			}
			mu.Lock()
			res[id] = profileLite{
				name:        a.DisplayName,
				institution: institutionOf(a),
				hIndex:      a.SummaryStats.HIndex,
			}
			mu.Unlock()
		}(id)
	}
	wg.Wait()
	return res
}

type workMeta struct {
	title   string
	authors []string
	year    int
}

// hydrateWorks concurrently fetches title/authors/year for a small id set.
// Bounded — callers pass ≤25 ids.
func hydrateWorks(ctx context.Context, ids []string) map[string]workMeta {
	res := make(map[string]workMeta, len(ids))
	if len(ids) == 0 {
		return res
	}
	var mu sync.Mutex
	var wg sync.WaitGroup
	sem := make(chan struct{}, 6)
	for _, id := range ids {
		wg.Add(1)
		go func(id string) {
			defer wg.Done()
			sem <- struct{}{}
			defer func() { <-sem }()
			w, err := oaClient.FetchWorkByID(ctx, id)
			if err != nil || w == nil {
				return
			}
			mu.Lock()
			res[id] = workMeta{
				title:   w.Title,
				authors: authorNames(w.Authorships, 4),
				year:    w.PublicationYear,
			}
			mu.Unlock()
		}(id)
	}
	wg.Wait()
	return res
}

func authorNames(as []openalex.Authorship, n int) []string {
	out := make([]string, 0, n)
	for _, a := range as {
		if a.Author.DisplayName == "" {
			continue
		}
		out = append(out, a.Author.DisplayName)
		if len(out) >= n {
			break
		}
	}
	return out
}

func institutionOf(a *openalex.Author) string {
	if a == nil || len(a.LastKnownInstitutions) == 0 {
		return ""
	}
	return a.LastKnownInstitutions[0].DisplayName
}

// ── tiny generic helpers ──────────────────────────────────────────────────

func firstOrEmpty(xs []string) string {
	if len(xs) == 0 {
		return ""
	}
	return xs[0]
}

func orDefault(v, def string) string {
	if strings.TrimSpace(v) != "" {
		return v
	}
	return def
}

func maxInt(a, b int) int {
	if a > b {
		return a
	}
	return b
}

func round4(f float64) float64 {
	return float64(int64(f*1e4+0.5)) / 1e4
}

func formatPct(f float64) string {
	return strconv.Itoa(int(f*100+0.5)) + "%"
}
