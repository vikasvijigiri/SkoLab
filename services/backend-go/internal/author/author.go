package author

import (
	"context"
	"fmt"
	"log/slog"
	"net/http"
	"regexp"
	"strings"
	"sync"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/skolab/backend-go/internal/cache"
	"github.com/skolab/backend-go/internal/db"
	"github.com/skolab/backend-go/internal/services/openalex"
)

var (
	openAlexClient   = openalex.New()
	suggestionsCache = cache.New(2 * time.Minute)
	orbitCache       = cache.New(5 * time.Minute)

	suggestionsCacheTTL = 2 * time.Minute
	orbitCacheTTL       = 30 * time.Minute

	// Filters out institutions, consortia, and non-human OpenAlex entities.
	nonPersonRe = regexp.MustCompile(
		`(?i)\b(collaboration|group|consortium|committee|team|network|project|society|association|institute|university|department|lab|laboratory|center|centre|foundation|quantum|topology|materials|systems|physics|biology|chemistry|science|computing|theory|applications|methods|frontiers|research)\b`,
	)
)

// ── Types ─────────────────────────────────────────────────────────────────────

type AuthorSuggestion struct {
	ID           string  `json:"id"`
	DisplayName  string  `json:"display_name"`
	Institution  string  `json:"institution"`
	FieldOfStudy *string `json:"field_of_study"`
	HIndex       *int    `json:"h_index"`
	InnovationScore int  `json:"innovation_score"`
	WorksCount   *int    `json:"works_count"`
}

type OrbitMetrics struct {
	CollaboratorCount int      `json:"collaborator_count"`
	InstitutionCount  int      `json:"institution_count"`
	WorksCount        int      `json:"works_count"`
	CitedByCount      int      `json:"cited_by_count"`
	HIndex            int      `json:"h_index"`
	I10Index          int      `json:"i10_index"`
	TopCoauthors      []string `json:"top_coauthors"`
}

// ── Author Suggestions ────────────────────────────────────────────────────────

// GetAuthorSuggestions handles GET /api/v1/author_suggestions
// Priority: PG researcher_metrics → PG researcher_profiles → OpenAlex
func GetAuthorSuggestions(c *gin.Context) {
	query := strings.TrimSpace(c.Query("query"))
	if query == "" {
		c.JSON(http.StatusBadRequest, gin.H{"error": "query is required"})
		return
	}
	cacheKey := strings.ToLower(query)

	if cached, ok := suggestionsCache.Get(cacheKey); ok {
		c.JSON(http.StatusOK, cached)
		return
	}

	ctx, cancel := context.WithTimeout(c.Request.Context(), 12*time.Second)
	defer cancel()

	// 1. PostgreSQL fast path — no network required.
	pgSuggestions, err := pgSearchSuggestions(ctx, query, 8)
	if err != nil {
		slog.Warn("pg suggestions error", "query", query, "err", err)
	}
	if len(pgSuggestions) >= 4 {
		suggestionsCache.Set(cacheKey, pgSuggestions, suggestionsCacheTTL)
		c.JSON(http.StatusOK, pgSuggestions)
		return
	}

	// 2. OpenAlex fallback — filter to real human names.
	results, err := openAlexClient.SearchAuthors(ctx, query, 10)
	if err != nil {
		slog.Warn("openalex author search failed", "query", query, "err", err)
		if len(pgSuggestions) > 0 {
			c.JSON(http.StatusOK, pgSuggestions)
		} else {
			c.JSON(http.StatusOK, []AuthorSuggestion{})
		}
		return
	}

	seen := make(map[string]bool)
	for _, s := range pgSuggestions {
		seen[s.ID] = true
	}

	suggestions := append([]AuthorSuggestion{}, pgSuggestions...)
	for _, a := range results {
		if seen[a.ID] {
			continue
		}
		name := a.DisplayName
		if name == "" || nonPersonRe.MatchString(name) {
			continue
		}
		words := strings.Fields(name)
		if len(words) < 2 || len(words) > 4 {
			continue
		}

		inst := institutionName(a.LastKnownInstitutions)
		field, expertise := openalex.ExtractFieldAndExpertise(&a)
		score := matchScore(query, expertise)

		h := a.SummaryStats.HIndex
		wc := a.WorksCount
		fPtr := &field
		suggestions = append(suggestions, AuthorSuggestion{
			ID:           a.ID,
			DisplayName:  name,
			Institution:  inst,
			FieldOfStudy: fPtr,
			HIndex:       &h,
			InnovationScore: score,
			WorksCount:   &wc,
		})
		seen[a.ID] = true
		if len(suggestions) >= 10 {
			break
		}
	}

	// If still thin, search works for author names.
	if len(suggestions) < 4 {
		works, werr := openAlexClient.SearchWorks(ctx, query, 20, "")
		if werr == nil {
			for _, w := range works {
				if len(suggestions) >= 10 {
					break
				}
				wConcepts := make([]string, 0, len(w.Concepts))
				for _, co := range w.Concepts {
					wConcepts = append(wConcepts, co.DisplayName)
				}
				score := matchScore(query, wConcepts)
				for _, as := range w.Authorships {
					a := as.Author
					if a.ID == "" || seen[a.ID] {
						continue
					}
					words := strings.Fields(a.DisplayName)
					if len(words) < 2 || len(words) > 4 {
						continue
					}
					if nonPersonRe.MatchString(a.DisplayName) {
						continue
					}
					inst := ""
					if len(as.Institutions) > 0 {
						inst = as.Institutions[0].DisplayName
					}
					if inst == "" {
						inst = "Independent Researcher"
					}
					seen[a.ID] = true
					suggestions = append(suggestions, AuthorSuggestion{
						ID:          a.ID,
						DisplayName: a.DisplayName,
						Institution: inst,
						InnovationScore: score,
					})
					if len(suggestions) >= 10 {
						break
					}
				}
			}
		}
	}

	suggestionsCache.Set(cacheKey, suggestions, suggestionsCacheTTL)
	c.JSON(http.StatusOK, suggestions)
}

// ── Orbit Metrics ─────────────────────────────────────────────────────────────

// GetOrbitMetrics handles GET /api/v1/orbit_metrics?author_id=...
// Returns real network intelligence from OpenAlex (cached 30 min).
func GetOrbitMetrics(c *gin.Context) {
	authorID := strings.TrimSpace(c.Query("author_id"))
	if authorID == "" {
		c.JSON(http.StatusBadRequest, gin.H{"error": "author_id is required"})
		return
	}
	cleanID := cleanAuthorID(authorID)

	if cached, ok := orbitCache.Get(cleanID); ok {
		c.JSON(http.StatusOK, cached)
		return
	}

	ctx, cancel := context.WithTimeout(c.Request.Context(), 20*time.Second)
	defer cancel()

	// Fetch author detail and recent works concurrently.
	var (
		author     *openalex.Author
		works      []openalex.Work
		authorErr  error
		worksErr   error
		wg         sync.WaitGroup
	)

	wg.Add(2)
	go func() {
		defer wg.Done()
		author, authorErr = openAlexClient.FetchAuthorByID(ctx, cleanID)
	}()
	go func() {
		defer wg.Done()
		works, worksErr = openAlexClient.FetchAuthorWorks(ctx, cleanID, "", 25, "publication_year:desc")
	}()
	wg.Wait()

	if authorErr != nil {
		slog.Warn("orbit metrics: author fetch failed", "id", cleanID, "err", authorErr)
		c.JSON(http.StatusNotFound, gin.H{"error": fmt.Sprintf("author %s not found on OpenAlex", cleanID)})
		return
	}
	if worksErr != nil {
		slog.Warn("orbit metrics: works fetch failed", "id", cleanID, "err", worksErr)
	}

	// Collect unique co-authors and institutions from the works.
	coauthorNames := make([]string, 0)
	coauthorIDs := make(map[string]bool)
	institutionNames := make(map[string]bool)
	nameCounts := make(map[string]int)

	for _, w := range works {
		for _, as := range w.Authorships {
			aID := cleanAuthorID(as.Author.ID)
			aName := as.Author.DisplayName
			if aID != "" && aID != cleanID && aName != "" {
				words := strings.Fields(aName)
				if len(words) >= 2 && len(words) <= 4 && !coauthorIDs[aID] {
					coauthorIDs[aID] = true
					coauthorNames = append(coauthorNames, aName)
					nameCounts[aName]++
				}
			}
			for _, inst := range as.Institutions {
				if inst.DisplayName != "" {
					institutionNames[inst.DisplayName] = true
				}
			}
		}
	}

	// Top 7 most frequent co-author names.
	topCoauthors := topN(nameCounts, 7)

	result := OrbitMetrics{
		CollaboratorCount: len(coauthorIDs),
		InstitutionCount:  len(institutionNames),
		WorksCount:        author.WorksCount,
		CitedByCount:      author.CitedByCount,
		HIndex:            author.SummaryStats.HIndex,
		I10Index:          author.SummaryStats.I10Index,
		TopCoauthors:      topCoauthors,
	}

	orbitCache.Set(cleanID, result, orbitCacheTTL)
	c.JSON(http.StatusOK, result)
}

// ── Resolve Email ─────────────────────────────────────────────────────────────

var institutionDomains = map[string]string{
	"bombay":                                "physics.iitb.ac.in",
	"delhi":                                 "physics.iitd.ac.in",
	"madras":                                "physics.iitm.ac.in",
	"advanced study":                        "ias.edu",
	"cambridge":                             "cam.ac.uk",
	"oxford":                                "oxford.ac.uk",
	"princeton":                             "princeton.edu",
	"california institute of technology":    "caltech.edu",
	"caltech":                               "caltech.edu",
	"harvard":                               "harvard.edu",
	"stanford":                              "stanford.edu",
	"massachusetts institute of technology": "mit.edu",
	"mit":                                   "mit.edu",
	"columbia":                              "columbia.edu",
	"yale":                                  "yale.edu",
	"chicago":                               "uchicago.edu",
	"berkeley":                              "berkeley.edu",
	"cornell":                               "cornell.edu",
	"toronto":                               "utoronto.ca",
	"max planck":                            "mpg.de",
	"cern":                                  "cern.ch",
	"zurich":                                "ethz.ch",
	"imperial":                              "imperial.ac.uk",
	"sorbonne":                              "sorbonne.fr",
	"copenhagen":                            "nbi.ku.dk",
}

// ResolveAuthorEmail handles GET /api/v1/resolve_email?name=...&institution=...
func ResolveAuthorEmail(c *gin.Context) {
	name := strings.TrimSpace(c.Query("name"))
	if name == "" {
		c.JSON(http.StatusBadRequest, gin.H{"error": "name is required"})
		return
	}
	institution := strings.TrimSpace(c.Query("institution"))

	// If no institution provided, try to look it up from OpenAlex.
	if institution == "" {
		ctx, cancel := context.WithTimeout(c.Request.Context(), 8*time.Second)
		defer cancel()
		if results, err := openAlexClient.SearchAuthors(ctx, name, 1); err == nil && len(results) > 0 {
			institution = institutionName(results[0].LastKnownInstitutions)
		}
	}

	domain := "university.edu"
	instLower := strings.ToLower(institution)
	for keyword, d := range institutionDomains {
		if strings.Contains(instLower, keyword) {
			domain = d
			break
		}
	}

	// Build email from cleaned name parts.
	cleanName := regexp.MustCompile(`[^a-zA-Z\s]`).ReplaceAllString(name, "")
	parts := strings.Fields(strings.ToLower(cleanName))
	var email string
	switch len(parts) {
	case 0:
		email = "collab@" + domain
	case 1:
		email = parts[0] + "@" + domain
	default:
		email = parts[0] + "." + parts[len(parts)-1] + "@" + domain
	}

	if institution == "" {
		institution = "Academic Institution"
	}
	c.JSON(http.StatusOK, gin.H{
		"name":        name,
		"email":       email,
		"institution": institution,
	})
}

// ── PostgreSQL helpers ────────────────────────────────────────────────────────

func pgSearchSuggestions(ctx context.Context, query string, limit int) ([]AuthorSuggestion, error) {
	if db.Pool == nil {
		return nil, nil
	}

	suggestions := make([]AuthorSuggestion, 0, limit)
	seen := make(map[string]bool)

	// 1. researcher_metrics (enriched profiles).
	rows, err := db.Pool.Query(ctx, `
		SELECT openalex_id, display_name, current_institution, field_of_study, h_index, works_count
		FROM researcher_metrics
		WHERE display_name ILIKE $1
		LIMIT $2
	`, "%"+query+"%", limit)
	if err == nil {
		defer rows.Close()
		for rows.Next() {
			var id, name string
			var institution, field *string
			var hIndex, worksCount *int
			if err := rows.Scan(&id, &name, &institution, &field, &hIndex, &worksCount); err != nil {
				continue
			}
			cleanID := cleanAuthorID(id)
			if seen[cleanID] {
				continue
			}
			seen[cleanID] = true
			inst := "Independent Researcher"
			if institution != nil && *institution != "" {
				inst = *institution
			}
			score := 75
			suggestions = append(suggestions, AuthorSuggestion{
				ID:              id,
				DisplayName:     name,
				Institution:     inst,
				FieldOfStudy:    field,
				HIndex:          hIndex,
				InnovationScore: score,
				WorksCount:      worksCount,
			})
		}
	}

	if len(suggestions) >= limit {
		return suggestions, nil
	}

	// 2. researcher_profiles fallback.
	rows2, err := db.Pool.Query(ctx, `
		SELECT openalex_id, display_name, institution, field_of_study, h_index, works_count
		FROM researcher_profiles
		WHERE display_name ILIKE $1
		LIMIT $2
	`, "%"+query+"%", limit-len(suggestions))
	if err == nil {
		defer rows2.Close()
		for rows2.Next() {
			var id, name string
			var institution, field *string
			var hIndex, worksCount *int
			if err := rows2.Scan(&id, &name, &institution, &field, &hIndex, &worksCount); err != nil {
				continue
			}
			cleanID := cleanAuthorID(id)
			if seen[cleanID] {
				continue
			}
			seen[cleanID] = true
			inst := "Independent Researcher"
			if institution != nil && *institution != "" {
				inst = *institution
			}
			suggestions = append(suggestions, AuthorSuggestion{
				ID:           id,
				DisplayName:  name,
				Institution:  inst,
				FieldOfStudy: field,
				HIndex:       hIndex,
				WorksCount:   worksCount,
				InnovationScore: 75,
			})
		}
	}

	return suggestions, nil
}

// ── Utility helpers ───────────────────────────────────────────────────────────

func cleanAuthorID(id string) string {
	parts := strings.Split(id, "/")
	return parts[len(parts)-1]
}

func institutionName(insts []openalex.Institution) string {
	if len(insts) > 0 && insts[0].DisplayName != "" {
		return insts[0].DisplayName
	}
	return "Independent Researcher"
}

// matchScore returns a relevance score 60–99 based on token overlap between
// query terms and concept names.
func matchScore(query string, concepts []string) int {
	if query == "" || len(concepts) == 0 {
		return 75
	}
	queryTokens := make(map[string]bool)
	for _, tok := range strings.Fields(strings.ToLower(query)) {
		if len(tok) > 2 {
			queryTokens[tok] = true
		}
	}
	if len(queryTokens) == 0 {
		return 75
	}
	conceptSet := make(map[string]bool)
	for _, c := range concepts {
		conceptSet[strings.ToLower(c)] = true
	}
	matchCount := 0
	for qt := range queryTokens {
		for c := range conceptSet {
			if strings.Contains(c, qt) || strings.Contains(qt, c) {
				matchCount++
				break
			}
		}
	}
	union := len(queryTokens) + len(conceptSet) - matchCount
	if union <= 0 {
		return 75
	}
	similarity := float64(matchCount) / float64(union)
	score := int(60 + similarity*100)
	if score > 99 {
		return 99
	}
	return score
}

func topN(m map[string]int, n int) []string {
	type kv struct {
		k string
		v int
	}
	var pairs []kv
	for k, v := range m {
		pairs = append(pairs, kv{k, v})
	}
	for i := 0; i < len(pairs) && i < n; i++ {
		maxIdx := i
		for j := i + 1; j < len(pairs); j++ {
			if pairs[j].v > pairs[maxIdx].v {
				maxIdx = j
			}
		}
		pairs[i], pairs[maxIdx] = pairs[maxIdx], pairs[i]
	}
	out := make([]string, 0, n)
	for i := 0; i < len(pairs) && i < n; i++ {
		out = append(out, pairs[i].k)
	}
	return out
}
