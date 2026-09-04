package author

// search.go — GET /search_author and GET /refresh_author, ported from
// services/backend/app/api/v1/endpoints/authors.py (search_author,
// refresh_author, fetch_similar_authors) as part of "Python is LLM-only"
// (decisions/0002).
//
// Neither route runs an LLM or an embedding: they are a cache → Postgres
// (researcher_metrics) → Firestore (global_researchers) → OpenAlex lookup that
// assembles the ~40-field AuthorResponse. The LLM *teleport enrichment* worker
// (app/services/data/researcher_worker.py::teleport_researcher) stays Python;
// these handlers hand it off with a fire-and-forget
//   POST {PYTHON_BACKEND_URL}/api/v1/internal/teleport/{clean_id}
// carrying the shared-secret header X-Internal-Token (INTERNAL_API_TOKEN).
//
// Response-shape parity with the Python route is field-for-field (names, order,
// nullability). The one unavoidable cross-runtime difference is numeric
// formatting: Go's encoding/json emits `0` where Python's json emits `0.0`, and
// escapes `<`, `>`, `&` and non-ASCII — the same characteristic every other Go
// gateway port already carries.

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"log/slog"
	"math"
	"net/http"
	"os"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/skolab/backend-go/internal/cache"
	"github.com/skolab/backend-go/internal/db"
	"github.com/skolab/backend-go/internal/firestore"
	"github.com/skolab/backend-go/internal/services/openalex"
)

const (
	profileCacheName     = "profile"
	suggestionsCacheName = "suggestions"
	searchAuthorTimeout  = 30 * time.Second
	refreshAuthorTimeout = 20 * time.Second
)

// teleportHook is the seam for tests. Production points it at fireTeleport.
var teleportHook = fireTeleport

// firestoreQueryEqHook is the seam for tests, same pattern as teleportHook.
// Production points it at firestore.QueryEq.
var firestoreQueryEqHook = firestore.QueryEq

var teleportHTTP = &http.Client{Timeout: 12 * time.Second}

// ── Response types (byte-for-byte field parity with app/schemas/core.py) ─────

// Work mirrors app/schemas/core.py::Work. Field order matches the Pydantic
// model. `authors` serialises to null (Python: Optional[List[str]] = None) on
// the Postgres / Firestore tiers, which never populate it.
type Work struct {
	ID               *string  `json:"id"`
	Title            *string  `json:"title"`
	Year             *int     `json:"year"`
	DOI              *string  `json:"doi"`
	Journal          *string  `json:"journal"`
	IsOpenAccess     bool     `json:"is_open_access"`
	Citations        int      `json:"citations"`
	CreativityScore  float64  `json:"creativity_score"`
	ComplexityScore  float64  `json:"complexity_score"`
	ImpactFactor     float64  `json:"impact_factor"`
	DisruptionScore  float64  `json:"disruption_score"`
	SemanticNovelty  float64  `json:"semantic_novelty"`
	OpenScienceScore float64  `json:"open_science_score"`
	Authors          []string `json:"authors"`
}

// AuthorResponse mirrors app/schemas/core.py::AuthorResponse. Field order and
// nullability match the Pydantic model exactly.
type AuthorResponse struct {
	ID                     string             `json:"id"`
	DisplayName            string             `json:"display_name"`
	ORCID                  *string            `json:"orcid"`
	HIndex                 int                `json:"h_index"`
	I10Index               int                `json:"i10_index"`
	WorksCount             int                `json:"works_count"`
	CitedByCount           int                `json:"cited_by_count"`
	Institution            string             `json:"institution"`
	FieldOfStudy           *string            `json:"field_of_study"`
	Expertise              []string           `json:"expertise"`
	Skills                 []string           `json:"skills"`
	Tools                  []string           `json:"tools"`
	AcademicHistory        []string           `json:"academic_history"`
	Works                  []Work             `json:"works"`
	InnovationScore        *float64           `json:"innovation_score"`
	MetricsComputed        bool               `json:"metrics_computed"`
	LLMActive              bool               `json:"llm_active"`
	AverageCreativity      float64            `json:"average_creativity"`
	AverageComplexity      float64            `json:"average_complexity"`
	AverageSkillScore      float64            `json:"average_skill_score"`
	AverageImpact          float64            `json:"average_impact"`
	AverageActivity        float64            `json:"average_activity"`
	DisruptionScore        float64            `json:"disruption_score"`
	CitationAcceleration   float64            `json:"citation_acceleration"`
	FutureImpactScore      float64            `json:"future_impact_score"`
	NetworkCentrality      float64            `json:"network_centrality"`
	SemanticNovelty        float64            `json:"semantic_novelty"`
	InterdisciplinaryIndex float64            `json:"interdisciplinary_index"`
	PolicyPatentScore      float64            `json:"policy_patent_score"`
	OpenScienceScore       float64            `json:"open_science_score"`
	CollaborationDiversity float64            `json:"collaboration_diversity"`
	ResearchConsistency    float64            `json:"research_consistency"`
	NextPrediction         *string            `json:"next_prediction"`
	SimilarResearchers     []AuthorSuggestion `json:"similar_researchers"`
}

type refreshAuthorResponse struct {
	Status   string `json:"status"`
	AuthorID string `json:"author_id"`
}

// ── HTTP handlers ────────────────────────────────────────────────────────────

// SearchAuthor handles GET /search_author (+ /api/v1/search_author). Public,
// matching the Python route.
func SearchAuthor(c *gin.Context) {
	if _, ok := c.GetQuery("name"); !ok {
		c.JSON(http.StatusUnprocessableEntity, gin.H{
			"detail": "name is required", "code": "validation_error",
		})
		return
	}
	name := c.Query("name")
	rawID := c.Query("id")
	focus := c.Query("focus")

	cleanID := ""
	if strings.TrimSpace(rawID) != "" {
		cleanID = cleanAuthorID(rawID)
	}

	var cacheKey string
	if cleanID != "" {
		cacheKey = "id:" + cleanID
	} else {
		f := ""
		if focus != "" {
			f = strings.ToLower(strings.TrimSpace(focus))
		}
		cacheKey = strings.ToLower(strings.TrimSpace(name)) + ":" + f
	}

	ctx, cancel := context.WithTimeout(c.Request.Context(), searchAuthorTimeout)
	defer cancel()

	// ── 1. profile_cache (Postgres L2) ──────────────────────────────────────
	if raw, ok := cache.PgGet(ctx, profileCacheName, cacheKey); ok {
		var cached AuthorResponse
		if json.Unmarshal(raw, &cached) == nil {
			c.JSON(http.StatusOK, normalizeAuthorResponse(cached))
			return
		}
	}

	// ── 2. Postgres researcher_metrics ──────────────────────────────────────
	if cleanID != "" {
		if row, ok := pgGetResearcherMetrics(ctx, cleanID); ok {
			works := pgGetResearcherWorks(ctx, cleanID)
			if len(works) == 0 && firestore.Available() {
				if doc, found, err := firestore.GetDoc(ctx, "global_researchers", cleanID); err == nil && found {
					works = worksFromFirestoreDoc(doc)
				}
			}
			field := strDeref(row.FieldOfStudy)
			similarQuery := firstNonEmpty(sliceFirst(row.Expertise), field)
			excludeID := row.OpenalexID
			if excludeID == "" {
				excludeID = cleanID
			}
			similar := fetchSimilarAuthors(ctx, similarQuery, excludeID)
			resp := assembleFromPG(row, works, similar)
			if err := cache.PgSet(ctx, profileCacheName, cacheKey, resp, profileCacheTTL()); err != nil {
				slog.Warn("search_author: profile_cache write failed", "key", cacheKey, "err", err)
			}
			c.JSON(http.StatusOK, resp)
			return
		}
	}

	// ── 3. Firestore global_researchers ─────────────────────────────────────
	if firestore.Available() {
		if cleanID != "" {
			if doc, found, err := firestore.GetDoc(ctx, "global_researchers", cleanID); err != nil {
				slog.Warn("search_author: firestore get failed", "id", cleanID, "err", err)
			} else if found {
				resp := assembleFromFirestore(ctx, doc)
				if err := cache.PgSet(ctx, profileCacheName, cacheKey, resp, profileCacheTTL()); err != nil {
					slog.Warn("search_author: profile_cache write failed", "key", cacheKey, "err", err)
				}
				c.JSON(http.StatusOK, resp)
				return
			}
		} else if resp, ok := firestoreNameLookup(ctx, name); ok {
			if err := cache.PgSet(ctx, profileCacheName, cacheKey, resp, profileCacheTTL()); err != nil {
				slog.Warn("search_author: profile_cache write failed", "key", cacheKey, "err", err)
			}
			c.JSON(http.StatusOK, resp)
			return
		}
	}

	// ── 4. OpenAlex — source of truth ──────────────────────────────────────
	author, resolvedID := resolveOpenAlexAuthor(ctx, cleanID, name, focus)
	if author == nil || resolvedID == "" {
		c.JSON(http.StatusNotFound, gin.H{"detail": "Author not found on OpenAlex"})
		return
	}

	resp := buildOpenAlexResponse(ctx, author, resolvedID, name, focus)
	if err := cache.PgSet(ctx, profileCacheName, cacheKey, resp, profileCacheTTL()); err != nil {
		slog.Warn("search_author: profile_cache write failed", "key", cacheKey, "err", err)
	}
	if llmActive() {
		teleportHook(firstNonEmpty(author.ID, resolvedID))
	}
	c.JSON(http.StatusOK, resp)
}

// RefreshAuthor handles GET /refresh_author (+ /api/v1/refresh_author). Public.
// It clears the cached profile, re-resolves the author id and kicks the Python
// teleport worker.
func RefreshAuthor(c *gin.Context) {
	if _, ok := c.GetQuery("name"); !ok {
		c.JSON(http.StatusUnprocessableEntity, gin.H{
			"detail": "name is required", "code": "validation_error",
		})
		return
	}
	name := c.Query("name")

	ctx, cancel := context.WithTimeout(c.Request.Context(), refreshAuthorTimeout)
	defer cancel()

	cacheKey := strings.ToLower(strings.TrimSpace(name))
	if err := cache.PgDelete(ctx, profileCacheName, cacheKey); err != nil {
		slog.Warn("refresh_author: profile_cache delete failed", "key", cacheKey, "err", err)
	}
	if err := cache.PgDelete(ctx, suggestionsCacheName, cacheKey); err != nil {
		slog.Warn("refresh_author: suggestions_cache delete failed", "key", cacheKey, "err", err)
	}

	results, err := openAlexClient.SearchAuthors(ctx, name, 1)
	if err != nil {
		slog.Warn("refresh_author: openalex search failed", "name", name, "err", err)
	}
	if len(results) > 0 {
		authorID := results[0].ID
		teleportHook(authorID)
		c.JSON(http.StatusOK, refreshAuthorResponse{Status: "Refresh started", AuthorID: authorID})
		return
	}
	c.JSON(http.StatusNotFound, gin.H{"detail": "Author not found for refresh"})
}

// ── OpenAlex resolution + assembly ──────────────────────────────────────────

// resolveOpenAlexAuthor mirrors the id / name+focus resolution block of the
// Python search_author OpenAlex tier. Returns (nil, "") when nothing resolves.
func resolveOpenAlexAuthor(ctx context.Context, cleanID, name, focus string) (*openalex.Author, string) {
	if cleanID != "" {
		a, err := openAlexClient.FetchAuthorByID(ctx, cleanID)
		if err != nil || a == nil {
			return nil, ""
		}
		return a, cleanID
	}

	results, err := openAlexClient.SearchAuthors(ctx, name, 10)
	if err != nil || len(results) == 0 {
		return nil, ""
	}

	var queryTokens []string
	for _, t := range strings.Fields(strings.ToLower(name)) {
		if len(t) > 2 {
			queryTokens = append(queryTokens, t)
		}
	}
	nameOK := func(candName string) bool {
		for _, tok := range queryTokens {
			if !strings.Contains(candName, tok) {
				return false
			}
		}
		return true
	}

	var picked *openalex.Author
	if focus != "" {
		nf := strings.ToLower(focus)
		for i := range results {
			cn := strings.ToLower(results[i].DisplayName)
			if len(queryTokens) > 0 && !nameOK(cn) {
				continue
			}
			for _, co := range results[i].XConcepts {
				cnm := strings.ToLower(co.DisplayName)
				if strings.Contains(nf, cnm) || strings.Contains(cnm, nf) {
					picked = &results[i]
					break
				}
			}
			if picked != nil {
				break
			}
		}
	}
	if picked == nil {
		for i := range results {
			cn := strings.ToLower(results[i].DisplayName)
			if len(queryTokens) > 0 && !nameOK(cn) {
				continue
			}
			picked = &results[i]
			break
		}
	}
	if picked == nil {
		picked = &results[0]
	}
	return picked, cleanAuthorID(picked.ID)
}

// buildOpenAlexResponse ports the OpenAlex-tier AuthorResponse assembly
// (authors.py lines ~521-643).
func buildOpenAlexResponse(ctx context.Context, a *openalex.Author, resolvedID, name, focus string) AuthorResponse {
	field, expertise := openalex.ExtractFieldAndExpertise(a)
	targetDiscipline := field
	if focus != "" {
		targetDiscipline = focus
	}

	rawWorks, err := openAlexClient.FetchAuthorWorks(ctx, resolvedID, a.ORCID, 50, "")
	if err != nil {
		slog.Warn("search_author: works fetch failed", "id", resolvedID, "err", err)
	}
	filtered := rawWorks
	if targetDiscipline != "" {
		filtered = filtered[:0:0]
		for _, w := range rawWorks {
			if isWorkRelevantToDiscipline(w, targetDiscipline) {
				filtered = append(filtered, w)
			}
		}
	}

	works := make([]Work, 0, len(filtered))
	citedSum := 0
	for _, w := range filtered {
		title := w.Title
		if strings.TrimSpace(title) == "" {
			continue
		}
		journal := w.PrimaryLocation.Source.DisplayName
		impact := w.PrimaryLocation.Source.TwoYrMeanCitedness
		citations := w.CitedByCount
		citedSum += citations

		var authorsList []string
		for _, as := range w.Authorships {
			an := as.Author.DisplayName
			aid := as.Author.ID
			if an != "" && aid != "" {
				authorsList = append(authorsList, an+"|"+aid)
			}
		}

		works = append(works, Work{
			ID:               strPtr(w.ID),
			Title:            strPtr(title),
			Year:             intPtrNonZero(w.PublicationYear),
			DOI:              strPtrNonEmpty(w.DOI),
			Journal:          strPtrNonEmpty(journal),
			IsOpenAccess:     w.OpenAccess.IsOA,
			Citations:        citations,
			CreativityScore:  0.0,
			ComplexityScore:  0.0,
			ImpactFactor:     roundTo2(impact),
			DisruptionScore:  0.0,
			SemanticNovelty:  0.0,
			OpenScienceScore: 0.0,
			Authors:          authorsList,
		})
	}

	academicHistory := academicHistoryFromAffiliations(a.Affiliations)

	similarQuery := field
	if len(expertise) > 0 && expertise[0] != "" {
		similarQuery = expertise[0]
	}
	similar := fetchSimilarAuthors(ctx, similarQuery, a.ID)

	resp := defaultAuthorResponse()
	resp.ID = firstNonEmpty(a.ID, resolvedID)
	resp.DisplayName = firstNonEmpty(a.DisplayName, name)
	resp.ORCID = strPtrNonEmpty(a.ORCID)
	resp.HIndex = a.SummaryStats.HIndex
	resp.I10Index = a.SummaryStats.I10Index
	resp.WorksCount = len(works)
	resp.CitedByCount = citedSum
	resp.Institution = institutionName(a.LastKnownInstitutions)
	fld := field
	resp.FieldOfStudy = &fld
	resp.Expertise = orEmptyStrings(expertise)
	resp.AcademicHistory = academicHistory
	resp.Works = works
	resp.InnovationScore = nil
	resp.MetricsComputed = false
	resp.LLMActive = llmActive()
	resp.NextPrediction = nil
	resp.SimilarResearchers = orEmptySuggestions(similar)
	return resp
}

// academicHistoryFromAffiliations mirrors the affiliations → "Inst (2001–2005)"
// derivation. Institutions are emitted in first-seen order, then stable-sorted
// by their earliest year.
func academicHistoryFromAffiliations(affs []openalex.Affiliation) []string {
	type span struct{ lo, hi int }
	order := make([]string, 0, len(affs))
	hist := make(map[string]*span, len(affs))
	for _, aff := range affs {
		instName := aff.Institution.DisplayName
		if instName == "" || len(aff.Years) == 0 {
			continue
		}
		lo, hi := aff.Years[0], aff.Years[0]
		for _, y := range aff.Years {
			if y < lo {
				lo = y
			}
			if y > hi {
				hi = y
			}
		}
		if e, ok := hist[instName]; ok {
			if lo < e.lo {
				e.lo = lo
			}
			if hi > e.hi {
				e.hi = hi
			}
		} else {
			hist[instName] = &span{lo, hi}
			order = append(order, instName)
		}
	}
	sort.SliceStable(order, func(i, j int) bool {
		return hist[order[i]].lo < hist[order[j]].lo
	})
	out := make([]string, 0, len(order))
	for _, n := range order {
		e := hist[n]
		if e.lo != e.hi {
			out = append(out, fmt.Sprintf("%s (%d–%d)", n, e.lo, e.hi))
		} else {
			out = append(out, fmt.Sprintf("%s (%d)", n, e.lo))
		}
	}
	return out
}

// ── fetch_similar_authors port ─────────────────────────────────────────────

// simCandidate carries only the derived author id — the display name /
// institution on the source authorship are re-fetched from the full profile,
// exactly as the Python helper does.
type simCandidate struct {
	id string
}

// fetchSimilarAuthors ports app/api/v1/endpoints/authors.py::fetch_similar_authors:
// derive candidate ids from the authorships of topic-matched works (never an
// author-name search — decisions/0005), then hydrate up to 5 profiles.
func fetchSimilarAuthors(ctx context.Context, queryTerm, excludeID string) []AuthorSuggestion {
	out := []AuthorSuggestion{}
	qt := strings.ToLower(strings.TrimSpace(queryTerm))
	if queryTerm == "" || qt == "multidisciplinary" || qt == "researcher" || qt == "general research" {
		return out
	}

	works, err := openAlexClient.SearchWorks(ctx, queryTerm, 20, "")
	if err != nil {
		slog.Warn("fetch_similar_authors: search_works failed", "q", queryTerm, "err", err)
		return out
	}
	candidates := deriveSimilarAuthorsFromWorks(works, excludeID, 5, queryTerm)
	if len(candidates) == 0 {
		return out
	}

	profiles := make([]*openalex.Author, len(candidates))
	var wg sync.WaitGroup
	for i := range candidates {
		wg.Add(1)
		go func(i int) {
			defer wg.Done()
			if a, err := openAlexClient.FetchAuthorByID(ctx, candidates[i].id); err == nil {
				profiles[i] = a
			}
		}(i)
	}
	wg.Wait()

	for _, a := range profiles {
		if a == nil {
			continue
		}
		inst := institutionName(a.LastKnownInstitutions)
		field, expertise := openalex.ExtractFieldAndExpertise(a)
		scoreConcepts := expertise
		if len(scoreConcepts) == 0 {
			scoreConcepts = []string{field}
		}
		score := computeQueryMatchScore(queryTerm, scoreConcepts)

		h := a.SummaryStats.HIndex
		wc := a.WorksCount
		dn := a.DisplayName
		if dn == "" {
			dn = "Unknown"
		}
		fld := field
		out = append(out, AuthorSuggestion{
			ID:              a.ID,
			DisplayName:     dn,
			Institution:     inst,
			FieldOfStudy:    &fld,
			HIndex:          &h,
			InnovationScore: score,
			WorksCount:      &wc,
		})
		if len(out) >= 5 {
			break
		}
	}
	return out
}

// deriveSimilarAuthorsFromWorks ports
// app/services/data/openalex_service.py::derive_similar_authors_from_works.
func deriveSimilarAuthorsFromWorks(works []openalex.Work, excludeID string, limit int, discipline string) []simCandidate {
	excludeClean := ""
	if excludeID != "" {
		excludeClean = cleanAuthorID(excludeID)
	}

	relevant := works
	if discipline != "" {
		relevant = relevant[:0:0]
		for _, w := range works {
			if workTopicMatches(w, discipline) {
				relevant = append(relevant, w)
			}
		}
	}

	seen := make(map[string]bool)
	out := make([]simCandidate, 0, limit)
	for _, w := range relevant {
		for _, as := range w.Authorships {
			aid := cleanAuthorID(as.Author.ID)
			if aid == "" || aid == excludeClean || seen[aid] {
				continue
			}
			seen[aid] = true
			out = append(out, simCandidate{id: aid})
			if len(out) >= limit {
				return out
			}
		}
	}
	return out
}

// workTopicMatches ports openalex_service.py::_work_topic_matches — structured
// concept/topic classification only, no freeform title/abstract scan.
func workTopicMatches(w openalex.Work, topic string) bool {
	var topicWords []string
	for _, x := range strings.Fields(strings.ToLower(topic)) {
		if len(x) > 3 {
			topicWords = append(topicWords, x)
		}
	}
	if len(topicWords) == 0 {
		return true
	}
	terms := make(map[string]bool)
	for _, c := range w.Concepts {
		if c.DisplayName != "" {
			terms[strings.ToLower(c.DisplayName)] = true
		}
	}
	for _, t := range w.Topics {
		if t.DisplayName != "" {
			terms[strings.ToLower(t.DisplayName)] = true
		}
		for _, fo := range []openalex.FieldObj{t.Field, t.Subfield, t.Domain} {
			if fo.DisplayName != "" {
				terms[strings.ToLower(fo.DisplayName)] = true
			}
		}
	}
	for term := range terms {
		for _, wd := range topicWords {
			if strings.Contains(term, wd) {
				return true
			}
		}
	}
	return false
}

// computeQueryMatchScore ports authors.py::compute_query_match_score.
func computeQueryMatchScore(query string, concepts []string) int {
	if query == "" || len(concepts) == 0 {
		return 75
	}
	queryTokens := make(map[string]bool)
	for _, t := range strings.Fields(query) {
		t = strings.ToLower(strings.TrimSpace(t))
		if len(t) > 2 {
			queryTokens[t] = true
		}
	}
	if len(queryTokens) == 0 {
		return 75
	}
	conceptsNorm := make(map[string]bool)
	for _, c := range concepts {
		cc := strings.ToLower(strings.TrimSpace(c))
		if cc != "" {
			conceptsNorm[cc] = true
		}
	}
	matchCount := 0
	for qt := range queryTokens {
		for c := range conceptsNorm {
			if strings.Contains(c, qt) || strings.Contains(qt, c) {
				matchCount++
				break
			}
		}
	}
	union := make(map[string]bool, len(queryTokens)+len(conceptsNorm))
	for k := range queryTokens {
		union[k] = true
	}
	for k := range conceptsNorm {
		union[k] = true
	}
	sim := 0.0
	if len(union) > 0 {
		sim = float64(matchCount) / float64(len(union))
	}
	v := int(60 + sim*100)
	if v > 99 {
		v = 99
	}
	if v < 60 {
		v = 60
	}
	return v
}

// ── Postgres researcher_metrics / researcher_works ─────────────────────────

type pgResearcherMetrics struct {
	OpenalexID             string
	DisplayName            string
	ORCID                  *string
	HIndex                 *int
	I10Index               *int
	WorksCount             *int
	CitedByCount           *int
	CurrentInstitution     *string
	FieldOfStudy           *string
	Expertise              []string
	AcademicHistory        []string
	Skills                 []string
	Tools                  []string
	AverageCreativity      *float64
	AverageComplexity      *float64
	AverageSkillScore      *float64
	AverageImpact          *float64
	AverageActivity        *float64
	DisruptionScore        *float64
	CitationAcceleration   *float64
	FutureImpactScore      *float64
	NetworkCentrality      *float64
	SemanticNovelty        *float64
	InterdisciplinaryIndex *float64
	PolicyPatentScore      *float64
	OpenScienceScore       *float64
	CollaborationDiversity *float64
	ResearchConsistency    *float64
	InnovationScore        *float64
	NextPrediction         *string
	MetricsComputed        *bool
}

func pgGetResearcherMetrics(ctx context.Context, cleanID string) (pgResearcherMetrics, bool) {
	var r pgResearcherMetrics
	if db.Pool == nil {
		return r, false
	}
	var expertiseJSON, academicJSON, skillsJSON, toolsJSON []byte
	err := db.Pool.QueryRow(ctx, `
		SELECT openalex_id, display_name, orcid, h_index, i10_index, works_count,
		       cited_by_count, current_institution, field_of_study,
		       expertise, academic_history, skills, tools,
		       average_creativity, average_complexity, average_skill_score,
		       average_impact, average_activity, disruption_score,
		       citation_acceleration, future_impact_score, network_centrality,
		       semantic_novelty, interdisciplinary_index, policy_patent_score,
		       open_science_score, collaboration_diversity, research_consistency,
		       innovation_score, next_prediction, metrics_computed
		FROM researcher_metrics
		WHERE openalex_id = $1 AND expires_at > (now() at time zone 'utc')
	`, cleanID).Scan(
		&r.OpenalexID, &r.DisplayName, &r.ORCID, &r.HIndex, &r.I10Index, &r.WorksCount,
		&r.CitedByCount, &r.CurrentInstitution, &r.FieldOfStudy,
		&expertiseJSON, &academicJSON, &skillsJSON, &toolsJSON,
		&r.AverageCreativity, &r.AverageComplexity, &r.AverageSkillScore,
		&r.AverageImpact, &r.AverageActivity, &r.DisruptionScore,
		&r.CitationAcceleration, &r.FutureImpactScore, &r.NetworkCentrality,
		&r.SemanticNovelty, &r.InterdisciplinaryIndex, &r.PolicyPatentScore,
		&r.OpenScienceScore, &r.CollaborationDiversity, &r.ResearchConsistency,
		&r.InnovationScore, &r.NextPrediction, &r.MetricsComputed,
	)
	if err != nil {
		return r, false
	}
	r.Expertise = jsonStringList(expertiseJSON)
	r.AcademicHistory = jsonStringList(academicJSON)
	r.Skills = jsonStringList(skillsJSON)
	r.Tools = jsonStringList(toolsJSON)
	return r, true
}

func pgGetResearcherWorks(ctx context.Context, cleanID string) []Work {
	if db.Pool == nil {
		return nil
	}
	rows, err := db.Pool.Query(ctx, `
		SELECT work_openalex_id, title, publication_year, doi, journal,
		       is_open_access, citations, creativity_score, complexity_score,
		       impact_factor, disruption_score, semantic_novelty, open_science_score
		FROM researcher_works
		WHERE author_openalex_id = $1
	`, cleanID)
	if err != nil {
		slog.Warn("search_author: researcher_works read failed", "id", cleanID, "err", err)
		return nil
	}
	defer rows.Close()

	out := make([]Work, 0)
	for rows.Next() {
		var (
			id             string
			title          string
			year           *int
			doi, journal   *string
			isOA           *bool
			citations      *int
			creativity     *float64
			complexity     *float64
			impact         *float64
			disruption     *float64
			semanticNov    *float64
			openScienceSco *float64
		)
		if err := rows.Scan(&id, &title, &year, &doi, &journal, &isOA, &citations,
			&creativity, &complexity, &impact, &disruption, &semanticNov, &openScienceSco); err != nil {
			continue
		}
		idCopy := id
		titleCopy := title
		out = append(out, Work{
			ID:               &idCopy,
			Title:            &titleCopy,
			Year:             year,
			DOI:              doi,
			Journal:          journal,
			IsOpenAccess:     boolDeref(isOA),
			Citations:        intDeref(citations),
			CreativityScore:  floatDeref(creativity),
			ComplexityScore:  floatDeref(complexity),
			ImpactFactor:     floatDeref(impact),
			DisruptionScore:  floatDeref(disruption),
			SemanticNovelty:  floatDeref(semanticNov),
			OpenScienceScore: floatDeref(openScienceSco),
			Authors:          nil,
		})
	}
	return out
}

func assembleFromPG(row pgResearcherMetrics, works []Work, similar []AuthorSuggestion) AuthorResponse {
	r := defaultAuthorResponse()
	r.ID = row.OpenalexID
	r.DisplayName = row.DisplayName
	r.ORCID = row.ORCID
	r.HIndex = intDeref(row.HIndex)
	r.I10Index = intDeref(row.I10Index)
	r.WorksCount = intDeref(row.WorksCount)
	r.CitedByCount = intDeref(row.CitedByCount)
	r.Institution = strDerefOr(row.CurrentInstitution, "Independent Researcher")
	fld := strDerefOr(row.FieldOfStudy, "Multidisciplinary")
	r.FieldOfStudy = &fld
	r.Expertise = orEmptyStrings(row.Expertise)
	r.Skills = orEmptyStrings(row.Skills)
	r.Tools = orEmptyStrings(row.Tools)
	r.AcademicHistory = orEmptyStrings(row.AcademicHistory)
	if len(works) > 0 {
		r.Works = works
	}
	if row.InnovationScore != nil && *row.InnovationScore != 0 {
		v := math.Trunc(*row.InnovationScore)
		r.InnovationScore = &v
	}
	r.MetricsComputed = boolDeref(row.MetricsComputed) && llmActive()
	r.LLMActive = llmActive()
	r.AverageCreativity = floatDeref(row.AverageCreativity)
	r.AverageComplexity = floatDeref(row.AverageComplexity)
	r.AverageSkillScore = floatDeref(row.AverageSkillScore)
	r.AverageImpact = floatDeref(row.AverageImpact)
	r.AverageActivity = floatDeref(row.AverageActivity)
	r.DisruptionScore = floatDeref(row.DisruptionScore)
	r.CitationAcceleration = floatDeref(row.CitationAcceleration)
	r.FutureImpactScore = floatDeref(row.FutureImpactScore)
	r.NetworkCentrality = floatDeref(row.NetworkCentrality)
	r.SemanticNovelty = floatDeref(row.SemanticNovelty)
	r.InterdisciplinaryIndex = floatDeref(row.InterdisciplinaryIndex)
	r.PolicyPatentScore = floatDeref(row.PolicyPatentScore)
	r.OpenScienceScore = floatDeref(row.OpenScienceScore)
	r.CollaborationDiversity = floatDeref(row.CollaborationDiversity)
	r.ResearchConsistency = floatDeref(row.ResearchConsistency)
	r.NextPrediction = row.NextPrediction
	r.SimilarResearchers = orEmptySuggestions(similar)
	return r
}

// ── Firestore global_researchers ──────────────────────────────────────────

type firestoreResearcherDoc struct {
	OpenalexID             string   `json:"openalex_id"`
	DisplayName            string   `json:"display_name"`
	ORCID                  *string  `json:"orcid"`
	HIndex                 int      `json:"h_index"`
	I10Index               int      `json:"i10_index"`
	WorksCount             int      `json:"works_count"`
	CitedByCount           int      `json:"cited_by_count"`
	CurrentInstitution     *string  `json:"current_institution"`
	FieldOfStudy           *string  `json:"field_of_study"`
	Expertise              []string `json:"expertise"`
	Skills                 []string `json:"skills"`
	Tools                  []string `json:"tools"`
	AcademicHistory        []string `json:"academic_history"`
	Works                  []Work   `json:"works"`
	InnovationScore        *float64 `json:"innovation_score"`
	MetricsComputed        bool     `json:"metrics_computed"`
	AverageCreativity      float64  `json:"average_creativity"`
	AverageComplexity      float64  `json:"average_complexity"`
	AverageSkillScore      float64  `json:"average_skill_score"`
	AverageImpact          float64  `json:"average_impact"`
	AverageActivity        float64  `json:"average_activity"`
	DisruptionScore        float64  `json:"disruption_score"`
	CitationAcceleration   float64  `json:"citation_acceleration"`
	FutureImpactScore      float64  `json:"future_impact_score"`
	NetworkCentrality      float64  `json:"network_centrality"`
	SemanticNovelty        float64  `json:"semantic_novelty"`
	InterdisciplinaryIndex float64  `json:"interdisciplinary_index"`
	PolicyPatentScore      float64  `json:"policy_patent_score"`
	OpenScienceScore       float64  `json:"open_science_score"`
	CollaborationDiversity float64  `json:"collaboration_diversity"`
	ResearchConsistency    float64  `json:"research_consistency"`
	NextPrediction         *string  `json:"next_prediction"`
}

// firestoreNameLookup mirrors the Python route's name-only Firestore branch:
// a `display_name == name` collection query, capped to a single hit. Ported
// via firestoreQueryEqHook (the swappable seam over firestore.QueryEq) rather
// than firestore.GetDoc, since there is no doc id to look up by. Returns
// (AuthorResponse{}, false) on no hit, a query error, or when Firestore is
// unavailable — the caller falls through to OpenAlex exactly as it does for
// the id-path GetDoc branch.
func firestoreNameLookup(ctx context.Context, name string) (AuthorResponse, bool) {
	docs, err := firestoreQueryEqHook(ctx, "global_researchers", "display_name", name, 1)
	if err != nil {
		slog.Warn("search_author: firestore query failed", "name", name, "err", err)
		return AuthorResponse{}, false
	}
	if len(docs) == 0 {
		return AuthorResponse{}, false
	}
	return assembleFromFirestore(ctx, docs[0]), true
}

func worksFromFirestoreDoc(doc map[string]any) []Work {
	raw, ok := doc["works"]
	if !ok {
		return nil
	}
	b, err := json.Marshal(raw)
	if err != nil {
		return nil
	}
	var ws []Work
	if json.Unmarshal(b, &ws) != nil {
		return nil
	}
	return ws
}

func assembleFromFirestore(ctx context.Context, doc map[string]any) AuthorResponse {
	var d firestoreResearcherDoc
	if b, err := json.Marshal(doc); err == nil {
		_ = json.Unmarshal(b, &d)
	}

	field := ""
	if d.FieldOfStudy != nil && *d.FieldOfStudy != "" {
		field = *d.FieldOfStudy
	} else if len(d.Expertise) > 0 {
		field = d.Expertise[0]
	}
	similarQuery := field
	if len(d.Expertise) > 0 && d.Expertise[0] != "" {
		similarQuery = d.Expertise[0]
	}
	similar := fetchSimilarAuthors(ctx, similarQuery, d.OpenalexID)

	r := defaultAuthorResponse()
	r.ID = d.OpenalexID
	r.DisplayName = d.DisplayName
	r.ORCID = d.ORCID
	r.HIndex = d.HIndex
	r.I10Index = d.I10Index
	r.WorksCount = d.WorksCount
	r.CitedByCount = d.CitedByCount
	r.Institution = strDerefOr(d.CurrentInstitution, "Independent Researcher")
	if _, present := doc["field_of_study"]; present {
		r.FieldOfStudy = d.FieldOfStudy
	} else {
		def := "Multidisciplinary"
		r.FieldOfStudy = &def
	}
	r.Expertise = orEmptyStrings(d.Expertise)
	r.Skills = orEmptyStrings(d.Skills)
	r.Tools = orEmptyStrings(d.Tools)
	r.AcademicHistory = orEmptyStrings(d.AcademicHistory)
	if len(d.Works) > 0 {
		r.Works = d.Works
	}
	r.InnovationScore = d.InnovationScore
	r.MetricsComputed = llmActive() && d.MetricsComputed
	r.LLMActive = llmActive()
	r.AverageCreativity = d.AverageCreativity
	r.AverageComplexity = d.AverageComplexity
	r.AverageSkillScore = d.AverageSkillScore
	r.AverageImpact = d.AverageImpact
	r.AverageActivity = d.AverageActivity
	r.DisruptionScore = d.DisruptionScore
	r.CitationAcceleration = d.CitationAcceleration
	r.FutureImpactScore = d.FutureImpactScore
	r.NetworkCentrality = d.NetworkCentrality
	r.SemanticNovelty = d.SemanticNovelty
	r.InterdisciplinaryIndex = d.InterdisciplinaryIndex
	r.PolicyPatentScore = d.PolicyPatentScore
	r.OpenScienceScore = d.OpenScienceScore
	r.CollaborationDiversity = d.CollaborationDiversity
	r.ResearchConsistency = d.ResearchConsistency
	r.NextPrediction = d.NextPrediction
	r.SimilarResearchers = orEmptySuggestions(similar)
	return r
}

// ── teleport handoff (Go → Python) ────────────────────────────────────────

// fireTeleport POSTs, fire-and-forget, to the Python internal teleport route.
// Errors are logged and swallowed — the enrichment is best-effort.
func fireTeleport(authorID string) {
	base := os.Getenv("PYTHON_BACKEND_URL")
	if base == "" {
		base = "http://localhost:8000"
	}
	endpoint := strings.TrimRight(base, "/") + "/api/v1/internal/teleport/" + cleanAuthorID(authorID)
	token := os.Getenv("INTERNAL_API_TOKEN")
	go func() {
		if err := postTeleport(endpoint, token); err != nil {
			slog.Warn("teleport handoff failed", "endpoint", endpoint, "err", err)
		}
	}()
}

func postTeleport(endpoint, token string) error {
	ctx, cancel := context.WithTimeout(context.Background(), 12*time.Second)
	defer cancel()
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, endpoint, nil)
	if err != nil {
		return err
	}
	if token != "" {
		req.Header.Set("X-Internal-Token", token)
	}
	resp, err := teleportHTTP.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	_, _ = io.Copy(io.Discard, resp.Body)
	if resp.StatusCode >= 300 {
		return fmt.Errorf("teleport endpoint returned HTTP %d", resp.StatusCode)
	}
	return nil
}

// ── small helpers ────────────────────────────────────────────────────────

// llmActive is the Go stand-in for Python is_llm_working(). It live-checks
// Python's own view — a short-timeout GET {PYTHON_BACKEND_URL}/api/v1/ai_status,
// whose `llm_active` boolean already tracks the in-process LLM_LIMIT_EXCEEDED
// 15-min cooldown (app/services/ai/llm_service.py::is_llm_working) — rather
// than only inferring it from whether a provider key is configured. The
// result is cached for llmActiveCacheTTL so a burst of requests does not turn
// into a burst of cross-service calls. On timeout, transport error, a
// non-200, or an unparseable body (Python down or unreachable) it falls back
// to the env-presence check (OPENROUTER_API_KEY / GROQ_API) — never blocks or
// fails the caller.
const llmActiveCacheTTL = 30 * time.Second

// llmActiveTimeout is a var (not a const) so tests can shrink it to keep a
// timeout-fallback test fast instead of actually waiting out the production
// value.
var llmActiveTimeout = 500 * time.Millisecond

var llmActiveHTTPClient = &http.Client{}

var (
	llmActiveMu       sync.Mutex
	llmActiveCached   bool
	llmActiveCachedAt time.Time
)

func llmActive() bool {
	llmActiveMu.Lock()
	if time.Since(llmActiveCachedAt) < llmActiveCacheTTL {
		v := llmActiveCached
		llmActiveMu.Unlock()
		return v
	}
	llmActiveMu.Unlock()

	v := probeLLMActive()

	llmActiveMu.Lock()
	llmActiveCached = v
	llmActiveCachedAt = time.Now()
	llmActiveMu.Unlock()
	return v
}

// probeLLMActive does the actual cross-service check; llmActive is the cached
// wrapper around it.
func probeLLMActive() bool {
	base := os.Getenv("PYTHON_BACKEND_URL")
	if base == "" {
		base = "http://localhost:8000"
	}
	ctx, cancel := context.WithTimeout(context.Background(), llmActiveTimeout)
	defer cancel()

	req, err := http.NewRequestWithContext(ctx, http.MethodGet,
		strings.TrimRight(base, "/")+"/api/v1/ai_status", nil)
	if err != nil {
		return envLLMActive()
	}
	resp, err := llmActiveHTTPClient.Do(req)
	if err != nil {
		return envLLMActive()
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return envLLMActive()
	}
	var body struct {
		LLMActive bool `json:"llm_active"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&body); err != nil {
		return envLLMActive()
	}
	return body.LLMActive
}

// envLLMActive is the fallback: whether an LLM provider key is configured
// (OPENROUTER_API_KEY / GROQ_API). It does not track Python's in-process
// LLM_LIMIT_EXCEEDED cooldown — that is exactly the gap probeLLMActive closes
// when Python is reachable.
func envLLMActive() bool {
	return os.Getenv("OPENROUTER_API_KEY") != "" || os.Getenv("GROQ_API") != ""
}

// resetLLMActiveCacheForTest clears the cache so a test can force a fresh
// probe. Test-only seam, same spirit as teleportHook.
func resetLLMActiveCacheForTest() {
	llmActiveMu.Lock()
	llmActiveCached = false
	llmActiveCachedAt = time.Time{}
	llmActiveMu.Unlock()
}

func profileCacheTTL() time.Duration {
	if v := os.Getenv("CACHE_TTL_PROFILE_SECONDS"); v != "" {
		if n, err := strconv.Atoi(v); err == nil && n > 0 {
			return time.Duration(n) * time.Second
		}
	}
	return time.Hour
}

func defaultAuthorResponse() AuthorResponse {
	return AuthorResponse{
		Expertise:          []string{},
		Skills:             []string{},
		Tools:              []string{},
		AcademicHistory:    []string{},
		Works:              []Work{},
		SimilarResearchers: []AuthorSuggestion{},
	}
}

func normalizeAuthorResponse(r AuthorResponse) AuthorResponse {
	if r.Expertise == nil {
		r.Expertise = []string{}
	}
	if r.Skills == nil {
		r.Skills = []string{}
	}
	if r.Tools == nil {
		r.Tools = []string{}
	}
	if r.AcademicHistory == nil {
		r.AcademicHistory = []string{}
	}
	if r.Works == nil {
		r.Works = []Work{}
	}
	if r.SimilarResearchers == nil {
		r.SimilarResearchers = []AuthorSuggestion{}
	}
	return r
}

func roundTo2(x float64) float64 { return math.Round(x*100) / 100 }

func jsonStringList(b []byte) []string {
	if len(b) == 0 {
		return nil
	}
	var out []string
	if json.Unmarshal(b, &out) != nil {
		return nil
	}
	return out
}

func sliceFirst(xs []string) string {
	if len(xs) > 0 {
		return xs[0]
	}
	return ""
}

func firstNonEmpty(a, b string) string {
	if a != "" {
		return a
	}
	return b
}

func orEmptyStrings(xs []string) []string {
	if xs == nil {
		return []string{}
	}
	return xs
}

func orEmptySuggestions(xs []AuthorSuggestion) []AuthorSuggestion {
	if xs == nil {
		return []AuthorSuggestion{}
	}
	return xs
}

func strPtr(s string) *string { return &s }

func strPtrNonEmpty(s string) *string {
	if s == "" {
		return nil
	}
	return &s
}

func intPtrNonZero(n int) *int {
	if n == 0 {
		return nil
	}
	return &n
}

func strDeref(p *string) string {
	if p == nil {
		return ""
	}
	return *p
}

func strDerefOr(p *string, def string) string {
	if p == nil || *p == "" {
		return def
	}
	return *p
}

func intDeref(p *int) int {
	if p == nil {
		return 0
	}
	return *p
}

func floatDeref(p *float64) float64 {
	if p == nil {
		return 0.0
	}
	return *p
}

func boolDeref(p *bool) bool {
	return p != nil && *p
}
