package author

// network.go — GET /network_collaborators, ported from the Python pipeline
// (services/backend/app/services/platform/pipeline/network.py + the
// app/api/v1/endpoints/authors.py handler) as part of "Python is LLM-only"
// (decisions/0002; docs/plans/2026-09-04-network-collaborators-to-go.md).
//
// No LLM, no embeddings: depth-1 + depth-2 co-author fan-out over OpenAlex
// works, Jaccard similarity on shared coauthors/concepts, and read + write-back
// to Postgres (researcher_profiles, researcher_connections, plus the legacy
// pipeline:: cache_entries blob).

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"net/url"
	"os"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/jackc/pgx/v5"
	"github.com/skolab/backend-go/internal/db"
	"github.com/skolab/backend-go/internal/services/openalex"
)

// NetworkCollaborator is one row of the bare JSON array returned by
// GET /network_collaborators. Field set matches the Python live-compute row
// (app/schemas/authors_extra.py NetworkCollaborator, extra="allow"; TS
// apps/web/src/lib/types.ts). `depth` is present on Python live rows and
// tolerated by both the Pydantic model and the (looser) TS type.
type NetworkCollaborator struct {
	ID                 string `json:"id"`
	Name               string `json:"name"`
	Institution        string `json:"institution"`
	Field              string `json:"field"`
	ConnectionPath     string `json:"connection_path"`
	RelevanceScore     int    `json:"relevance_score"`
	PapersCollaborated int    `json:"papers_collaborated"`
	TotalPublications  int    `json:"total_publications"`
	HIndex             int    `json:"h_index"`
	Depth              int    `json:"depth"`
}

type netParams struct {
	authorID   string
	excludeIDs []string
	field      string
	name       string
	limit      int
	offset     int
}

// GetNetworkCollaborators handles GET /network_collaborators.
//
// TODO: wire internal/cache once feat/go-cache-firestore-heatmap lands — the
// outer PgBackedCache(name="network_collaborators", ttl=1h) tier from the
// Python route is not ported yet. The researcher_connections 24h fast-path and
// the legacy pipeline:: cache_entries blob below cover the hot case. See
// docs/plans/2026-09-04-network-collaborators-to-go.md ("Cache tier — deferred").
func GetNetworkCollaborators(c *gin.Context) {
	authorID := strings.TrimSpace(c.Query("author_id"))
	if authorID == "" {
		c.JSON(http.StatusBadRequest, gin.H{"error": "author_id is required"})
		return
	}

	p := netParams{
		authorID:   authorID,
		excludeIDs: splitCSV(c.Query("exclude_ids")),
		field:      c.Query("field"),
		name:       c.Query("name"),
		limit:      clampInt(queryInt(c, "limit", 20), 1, 100),
		offset:     queryInt(c, "offset", 0),
	}
	if p.offset < 0 {
		p.offset = 0
	}

	ctx, cancel := context.WithTimeout(c.Request.Context(), 30*time.Second)
	defer cancel()

	rows, status, err := computeNetworkCollaborators(ctx, p)
	if err != nil {
		c.JSON(status, gin.H{"error": err.Error()})
		return
	}
	if rows == nil {
		rows = []NetworkCollaborator{}
	}
	c.JSON(http.StatusOK, rows)
}

// computeNetworkCollaborators mirrors NetworkMixin.get_network_collaborators:
// fast-path DB reads first, then a full OpenAlex computation with write-back.
// Returns (rows, httpStatusOnError, err).
func computeNetworkCollaborators(ctx context.Context, p netParams) ([]NetworkCollaborator, int, error) {
	cleanID := cleanAuthorID(p.authorID)
	authorID := p.authorID

	// Dynamic name → id resolution (Python: clean_id in {"", "fallback_seed"}).
	if (cleanID == "" || cleanID == "fallback_seed") && p.name != "" {
		if resolved := resolveAuthorIDByName(ctx, p.name, p.field); resolved != "" {
			authorID = resolved
			cleanID = cleanAuthorID(resolved)
		}
	}
	if cleanID == "" || cleanID == "fallback_seed" {
		return nil, http.StatusBadRequest,
			errors.New("no valid author ID provided for collaborator network extraction")
	}

	excludeSet := make(map[string]bool, len(p.excludeIDs)+1)
	for _, e := range p.excludeIDs {
		excludeSet[e] = true
	}

	// ── 1. researcher_connections fast-path (24h TTL) ─────────────────────────
	fastExclude := make(map[string]bool, len(excludeSet)+1)
	for k := range excludeSet {
		fastExclude[k] = true
	}
	fastExclude[cleanID] = true
	if cached, hit := readCachedConnections(ctx, cleanID, fastExclude, p.field); hit {
		return sliceWindow(cached, p.offset, p.limit), http.StatusOK, nil
	}

	// ── 2. legacy pipeline:: cache_entries blob (1h TTL) ─────────────────────
	if blob, hit := readPipelineBlob(ctx, cleanID, p.field); hit {
		filtered := make([]NetworkCollaborator, 0, len(blob))
		for _, cb := range blob {
			if !excludeSet[cleanAuthorID(cb.ID)] {
				filtered = append(filtered, cb)
			}
		}
		return sliceWindow(filtered, p.offset, p.limit), http.StatusOK, nil
	}

	// ── 3. Full OpenAlex computation ─────────────────────────────────────────
	excludeSet[cleanID] = true

	profile, err := openAlexClient.FetchAuthorByID(ctx, authorID)
	if err != nil || profile == nil {
		return nil, http.StatusNotFound,
			fmt.Errorf("author with ID '%s' not found on OpenAlex", authorID)
	}
	primaryName := profile.DisplayName
	if primaryName == "" {
		primaryName = "Main Author"
	}
	upsertResearcherProfile(ctx, profile, 7)

	var targetFields []string
	if p.field != "" {
		for _, f := range strings.Split(p.field, ",") {
			f = strings.ToLower(strings.TrimSpace(f))
			if f != "" {
				targetFields = append(targetFields, f)
			}
		}
	} else {
		for _, co := range profile.XConcepts {
			if co.DisplayName != "" {
				targetFields = append(targetFields, strings.ToLower(co.DisplayName))
			}
		}
		if len(targetFields) == 0 {
			if f := pgResearcherMetricsField(ctx, cleanID); f != "" {
				targetFields = []string{strings.ToLower(f)}
			}
		}
	}

	isRelevant := func(candidateFields []string) bool {
		if p.field == "" {
			return true
		}
		for _, cf := range candidateFields {
			if isFieldSemanticallyRelevant(cf, "", p.field) {
				return true
			}
		}
		return false
	}

	fetchWorks := func(ctx context.Context, authCleanID string, maxWorks int) []openalex.Work {
		works, err := openAlexClient.FetchAuthorWorks(ctx, authCleanID, "", maxWorks, "")
		if err != nil {
			return nil
		}
		if p.field == "" {
			return works
		}
		out := make([]openalex.Work, 0, len(works))
		for _, w := range works {
			if isWorkRelevantToDiscipline(w, p.field) {
				out = append(out, w)
			}
		}
		return out
	}

	d1Works := fetchWorks(ctx, cleanID, 100)
	if len(d1Works) == 0 {
		return nil, http.StatusNotFound,
			fmt.Errorf("no publications found for author '%s' (%s) on OpenAlex", primaryName, cleanID)
	}
	sort.SliceStable(d1Works, func(i, j int) bool {
		if d1Works[i].CitedByCount != d1Works[j].CitedByCount {
			return d1Works[i].CitedByCount > d1Works[j].CitedByCount
		}
		return d1Works[i].PublicationYear > d1Works[j].PublicationYear
	})

	// ── depth 1 ─────────────────────────────────────────────────────────────
	type d1Entry struct {
		id, name, institution, field, sharedPaper string
		jointCount                                int
		workConcepts                              []string
	}
	d1Order := make([]string, 0)
	d1Map := make(map[string]*d1Entry)
	for _, w := range d1Works {
		title := w.Title
		if title == "" {
			title = "Research Paper"
		}
		var wc []string
		for _, co := range w.Concepts {
			if co.DisplayName != "" {
				wc = append(wc, co.DisplayName)
			}
		}
		wf := "Researcher"
		if len(wc) > 0 {
			wf = wc[0]
		}
		for _, as := range w.Authorships {
			aid := as.Author.ID
			if aid == "" || excludeSet[cleanAuthorID(aid)] {
				continue
			}
			if e, ok := d1Map[aid]; ok {
				e.jointCount++
				continue
			}
			nm := as.Author.DisplayName
			if nm == "" {
				nm = "Unknown"
			}
			inst := "Independent Researcher"
			if len(as.Institutions) > 0 {
				inst = as.Institutions[0].DisplayName
			}
			d1Map[aid] = &d1Entry{
				id: aid, name: nm, institution: inst, field: wf,
				sharedPaper: title, jointCount: 1, workConcepts: wc,
			}
			d1Order = append(d1Order, aid)
		}
	}

	// Top 10 direct co-authors by joint collaboration count (stable).
	d1List := make([]*d1Entry, 0, len(d1Order))
	for _, id := range d1Order {
		d1List = append(d1List, d1Map[id])
	}
	sort.SliceStable(d1List, func(i, j int) bool { return d1List[i].jointCount > d1List[j].jointCount })
	if len(d1List) > 10 {
		d1List = d1List[:10]
	}

	// ── depth 2 (concurrent works fetch per d1) ─────────────────────────────
	d2WorksByParent := make([][]openalex.Work, len(d1List))
	var wg sync.WaitGroup
	for i := range d1List {
		wg.Add(1)
		go func(i int, cid string) {
			defer wg.Done()
			d2WorksByParent[i] = fetchWorks(ctx, cid, 20)
		}(i, cleanAuthorID(d1List[i].id))
	}
	wg.Wait()

	type d2Entry struct {
		id, name, institution, field, connectionPath string
		workConcepts                                 []string
	}
	d2Order := make([]string, 0)
	d2Map := make(map[string]*d2Entry)
	for i, d1 := range d1List {
		for _, w := range d2WorksByParent[i] {
			var wc []string
			for _, co := range w.Concepts {
				if co.DisplayName != "" {
					wc = append(wc, co.DisplayName)
				}
			}
			wf := "Expert Collaborator"
			if len(wc) > 0 {
				wf = wc[0]
			}
			for _, as := range w.Authorships {
				aid := as.Author.ID
				if aid == "" || excludeSet[cleanAuthorID(aid)] {
					continue
				}
				if _, ok := d1Map[aid]; ok {
					continue
				}
				if _, ok := d2Map[aid]; ok {
					continue
				}
				nm := as.Author.DisplayName
				if nm == "" {
					nm = "Unknown"
				}
				inst := "Independent Researcher"
				if len(as.Institutions) > 0 {
					inst = as.Institutions[0].DisplayName
				}
				d2Map[aid] = &d2Entry{
					id: aid, name: nm, institution: inst, field: wf,
					connectionPath: fmt.Sprintf("Collaborates with %s (connected via %s)", d1.name, primaryName),
					workConcepts:   wc,
				}
				d2Order = append(d2Order, aid)
			}
		}
	}

	// ── batch stats (h-index / works_count / concepts) ─────────────────────
	allIDs := make([]string, 0, len(d1Order)+len(d2Order))
	for _, id := range d1Order {
		allIDs = append(allIDs, cleanAuthorID(id))
	}
	for _, id := range d2Order {
		allIDs = append(allIDs, cleanAuthorID(id))
	}
	stats := make(map[string]authorStats, len(allIDs))
	if len(allIDs) > 0 {
		fetchAuthorStatsBatch(ctx, allIDs, stats)
		missing := make([]string, 0)
		for _, id := range allIDs {
			if _, ok := stats[id]; !ok {
				missing = append(missing, id)
			}
		}
		if len(missing) > 0 {
			fillStatsFromDB(ctx, missing, stats)
		}
	}

	// ── build the collaborator pool ────────────────────────────────────────
	pool := make([]NetworkCollaborator, 0, len(d1Order)+len(d2Order))
	for _, aid := range d1Order {
		d1 := d1Map[aid]
		st := stats[cleanAuthorID(aid)]
		cand := append(append([]string{}, d1.workConcepts...), st.concepts...)
		if !isRelevant(cand) {
			continue
		}
		if st.raw != nil {
			upsertResearcherProfile(ctx, st.raw, 7)
		}
		sim := computeJaccardSimilarity(targetFields, cand)
		fld := d1.field
		if fld == "" {
			fld = "Researcher"
		}
		pool = append(pool, NetworkCollaborator{
			ID:                 aid,
			Name:               d1.name,
			Institution:        d1.institution,
			Field:              fld,
			ConnectionPath:     fmt.Sprintf("Co-authored '%s' with %s", d1.sharedPaper, primaryName),
			RelevanceScore:     clampInt(int(80+sim*40+float64(d1.jointCount*2)), 80, 99),
			PapersCollaborated: d1.jointCount,
			TotalPublications:  st.worksCount,
			HIndex:             st.hIndex,
			Depth:              1,
		})
	}
	for _, aid := range d2Order {
		d2 := d2Map[aid]
		st := stats[cleanAuthorID(aid)]
		cand := append(append([]string{}, d2.workConcepts...), st.concepts...)
		if !isRelevant(cand) {
			continue
		}
		if st.raw != nil {
			upsertResearcherProfile(ctx, st.raw, 7)
		}
		sim := computeJaccardSimilarity(targetFields, cand)
		pool = append(pool, NetworkCollaborator{
			ID:                 aid,
			Name:               d2.name,
			Institution:        d2.institution,
			Field:              d2.field,
			ConnectionPath:     d2.connectionPath,
			RelevanceScore:     clampInt(int(60+sim*100), 60, 99),
			PapersCollaborated: 0,
			TotalPublications:  st.worksCount,
			HIndex:             st.hIndex,
			Depth:              2,
		})
	}
	sort.SliceStable(pool, func(i, j int) bool { return pool[i].RelevanceScore > pool[j].RelevanceScore })

	if len(pool) > 0 {
		writeConnections(ctx, cleanID, pool)
		writePipelineBlob(ctx, cleanID, p.field, pool)
	}

	final := make([]NetworkCollaborator, 0, len(pool))
	for _, cb := range pool {
		if !excludeSet[cleanAuthorID(cb.ID)] {
			final = append(final, cb)
		}
	}
	return sliceWindow(final, p.offset, p.limit), http.StatusOK, nil
}

// ── OpenAlex helpers ────────────────────────────────────────────────────────

// resolveAuthorIDByName mirrors NetworkMixin._resolve_author_id_by_name.
func resolveAuthorIDByName(ctx context.Context, name, field string) string {
	results, err := openAlexClient.SearchAuthors(ctx, name, 10)
	if err != nil || len(results) == 0 {
		return ""
	}
	var queryTokens []string
	for _, t := range strings.Fields(strings.ToLower(name)) {
		if len(t) > 2 {
			queryTokens = append(queryTokens, t)
		}
	}
	var nameMatches []openalex.Author
	for _, cand := range results {
		cn := strings.ToLower(cand.DisplayName)
		ok := true
		for _, tok := range queryTokens {
			if !strings.Contains(cn, tok) {
				ok = false
				break
			}
		}
		if ok {
			nameMatches = append(nameMatches, cand)
		}
	}
	normField := strings.ToLower(field)
	if normField != "" {
		for _, cand := range nameMatches {
			for _, co := range cand.XConcepts {
				cnm := strings.ToLower(co.DisplayName)
				if cnm != "" && (strings.Contains(cnm, normField) || strings.Contains(normField, cnm)) {
					return cand.ID
				}
			}
		}
	}
	if len(nameMatches) > 0 {
		return nameMatches[0].ID
	}
	return results[0].ID
}

type authorStats struct {
	worksCount  int
	hIndex      int
	concepts    []string
	institution string
	raw         *openalex.Author // set only when sourced from the OpenAlex batch
}

var oaHTTP = &http.Client{Timeout: 20 * time.Second}

func openAlexEmail() string {
	if e := os.Getenv("OPENALEX_EMAIL"); e != "" {
		return e
	}
	return "support@skolab.open"
}

// fetchAuthorStatsBatch mirrors the Python fetch_stats_chunk loop: chunks of 50
// ids against GET /authors?filter=openalex:a|b|... Best-effort; failures leave
// the id unfilled for the DB fallback.
func fetchAuthorStatsBatch(ctx context.Context, ids []string, out map[string]authorStats) {
	var mu sync.Mutex
	var wg sync.WaitGroup
	for i := 0; i < len(ids); i += 50 {
		end := i + 50
		if end > len(ids) {
			end = len(ids)
		}
		chunk := ids[i:end]
		wg.Add(1)
		go func(chunk []string) {
			defer wg.Done()
			q := url.Values{}
			q.Set("filter", "openalex:"+strings.Join(chunk, "|"))
			q.Set("per_page", "50")
			q.Set("mailto", openAlexEmail())
			req, err := http.NewRequestWithContext(ctx, http.MethodGet,
				"https://api.openalex.org/authors?"+q.Encode(), nil)
			if err != nil {
				return
			}
			resp, err := oaHTTP.Do(req)
			if err != nil {
				return
			}
			defer resp.Body.Close()
			if resp.StatusCode != http.StatusOK {
				return
			}
			body, err := io.ReadAll(resp.Body)
			if err != nil {
				return
			}
			var parsed struct {
				Results []openalex.Author `json:"results"`
			}
			if json.Unmarshal(body, &parsed) != nil {
				return
			}
			mu.Lock()
			for idx := range parsed.Results {
				a := parsed.Results[idx]
				short := cleanAuthorID(a.ID)
				var concepts []string
				for _, co := range a.XConcepts {
					if co.DisplayName != "" {
						concepts = append(concepts, co.DisplayName)
					}
				}
				inst := "Independent Researcher"
				if len(a.LastKnownInstitutions) > 0 && a.LastKnownInstitutions[0].DisplayName != "" {
					inst = a.LastKnownInstitutions[0].DisplayName
				}
				ac := a
				out[short] = authorStats{
					worksCount:  a.WorksCount,
					hIndex:      a.SummaryStats.HIndex,
					concepts:    concepts,
					institution: inst,
					raw:         &ac,
				}
			}
			mu.Unlock()
		}(chunk)
	}
	wg.Wait()
}

// ── Postgres helpers (native pgx via db.Pool) ──────────────────────────────

func inClause(start, n int) string {
	parts := make([]string, n)
	for i := 0; i < n; i++ {
		parts[i] = "$" + strconv.Itoa(start+i)
	}
	return " (" + strings.Join(parts, ",") + ")"
}

// readCachedConnections is the 24h researcher_connections fast-path. Returns
// (rows, true) whenever the table had any live row for this author — even if
// the post-filter list is empty — matching the Python `if cached_rows:` guard.
func readCachedConnections(ctx context.Context, cleanID string, excl map[string]bool, field string) ([]NetworkCollaborator, bool) {
	if db.Pool == nil {
		return nil, false
	}
	now := time.Now().UTC()
	rows, err := db.Pool.Query(ctx, `
		SELECT connection_openalex_id, connection_name, connection_institution,
		       connection_field, connection_path, relevance_score,
		       papers_collaborated, total_publications, h_index, depth
		FROM researcher_connections
		WHERE author_openalex_id = $1 AND expires_at > $2
		ORDER BY relevance_score DESC
	`, cleanID, now)
	if err != nil {
		slog.Warn("network: researcher_connections read failed", "id", cleanID, "err", err)
		return nil, false
	}
	defer rows.Close()

	out := make([]NetworkCollaborator, 0)
	hadRows := false
	for rows.Next() {
		hadRows = true
		var (
			id, nm                            string
			inst, fld, path                   *string
			rel, papers, totalPubs, hi, depth *int
		)
		if err := rows.Scan(&id, &nm, &inst, &fld, &path, &rel, &papers, &totalPubs, &hi, &depth); err != nil {
			continue
		}
		if excl[cleanAuthorID(id)] {
			continue
		}
		nc := NetworkCollaborator{
			ID:                 id,
			Name:               nm,
			Institution:        strDefault(inst, "Independent Researcher"),
			Field:              strDefault(fld, "Researcher"),
			ConnectionPath:     strVal(path),
			RelevanceScore:     intVal(rel),
			PapersCollaborated: intVal(papers),
			TotalPublications:  intVal(totalPubs),
			HIndex:             intVal(hi),
			Depth:              intVal(depth),
		}
		if field != "" && !isFieldSemanticallyRelevant(nc.Field, nc.ConnectionPath, field) {
			continue
		}
		out = append(out, nc)
	}
	if !hadRows {
		return nil, false
	}
	return out, true
}

// readPipelineBlob reads the legacy PgBackedCache(name="pipeline") blob:
// cache_entries.cache_key = "pipeline::network_collaborators_<id>_<field>",
// value wrapped as {"v": {"collaborators": [...]}}.
func readPipelineBlob(ctx context.Context, cleanID, field string) ([]NetworkCollaborator, bool) {
	if db.Pool == nil {
		return nil, false
	}
	key := "pipeline::network_collaborators_" + cleanID + "_" + field
	now := time.Now().UTC()
	var raw []byte
	err := db.Pool.QueryRow(ctx,
		`SELECT data FROM cache_entries WHERE cache_key = $1 AND expires_at > $2`,
		key, now).Scan(&raw)
	if err != nil {
		if !errors.Is(err, pgx.ErrNoRows) {
			slog.Warn("network: pipeline blob read failed", "key", key, "err", err)
		}
		return nil, false
	}
	var env struct {
		V struct {
			Collaborators []NetworkCollaborator `json:"collaborators"`
		} `json:"v"`
	}
	if json.Unmarshal(raw, &env) != nil || env.V.Collaborators == nil {
		return nil, false
	}
	return env.V.Collaborators, true
}

// upsertResearcherProfile mirrors AuthorChatMixin._upsert_researcher_profile.
// Best-effort: a write failure is logged, never fatal.
func upsertResearcherProfile(ctx context.Context, a *openalex.Author, ttlDays int) {
	if db.Pool == nil || a == nil || a.ID == "" {
		return
	}
	clean := cleanAuthorID(a.ID)
	inst := "Independent Researcher"
	if len(a.LastKnownInstitutions) > 0 && a.LastKnownInstitutions[0].DisplayName != "" {
		inst = a.LastKnownInstitutions[0].DisplayName
	}
	field, concepts := openalex.ExtractFieldAndExpertise(a)
	now := time.Now().UTC()
	expires := now.AddDate(0, 0, ttlDays)
	conceptsJSON, _ := json.Marshal(concepts)
	rawJSON, _ := json.Marshal(a)
	dn := a.DisplayName
	if dn == "" {
		dn = "Unknown"
	}
	_, err := db.Pool.Exec(ctx, `
		INSERT INTO researcher_profiles
			(openalex_id, display_name, institution, field_of_study, h_index,
			 works_count, concepts, raw_profile, last_synced, expires_at)
		VALUES ($1,$2,$3,$4,$5,$6,$7::json,$8::json,$9,$10)
		ON CONFLICT (openalex_id) DO UPDATE SET
			display_name = EXCLUDED.display_name,
			institution = EXCLUDED.institution,
			field_of_study = EXCLUDED.field_of_study,
			h_index = EXCLUDED.h_index,
			works_count = EXCLUDED.works_count,
			concepts = EXCLUDED.concepts,
			raw_profile = EXCLUDED.raw_profile,
			last_synced = EXCLUDED.last_synced,
			expires_at = EXCLUDED.expires_at
	`, clean, dn, inst, field, a.SummaryStats.HIndex,
		a.WorksCount, string(conceptsJSON), string(rawJSON), now, expires)
	if err != nil {
		slog.Warn("network: researcher_profiles upsert failed", "id", clean, "err", err)
	}
}

// writeConnections replaces this author's researcher_connections rows in one
// transaction (Python: delete-all then per-row insert, 24h TTL). Best-effort.
func writeConnections(ctx context.Context, cleanID string, pool []NetworkCollaborator) {
	if db.Pool == nil {
		return
	}
	now := time.Now().UTC()
	expires := now.Add(24 * time.Hour)
	tx, err := db.Pool.Begin(ctx)
	if err != nil {
		slog.Warn("network: connections tx begin failed", "id", cleanID, "err", err)
		return
	}
	defer func() { _ = tx.Rollback(ctx) }()

	if _, err := tx.Exec(ctx,
		`DELETE FROM researcher_connections WHERE author_openalex_id = $1`, cleanID); err != nil {
		slog.Warn("network: connections delete failed", "id", cleanID, "err", err)
		return
	}
	for _, r := range pool {
		if _, err := tx.Exec(ctx, `
			INSERT INTO researcher_connections
				(author_openalex_id, connection_openalex_id, connection_name,
				 connection_institution, connection_field, depth, connection_path,
				 relevance_score, papers_collaborated, total_publications, h_index,
				 last_synced, expires_at)
			VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,$13)
		`, cleanID, r.ID, r.Name, r.Institution, r.Field, r.Depth, r.ConnectionPath,
			r.RelevanceScore, r.PapersCollaborated, r.TotalPublications, r.HIndex, now, expires); err != nil {
			slog.Warn("network: connection insert failed", "id", cleanID, "err", err)
			return
		}
	}
	if err := tx.Commit(ctx); err != nil {
		slog.Warn("network: connections commit failed", "id", cleanID, "err", err)
	}
}

// writePipelineBlob mirrors _save_to_postgres(cache_key, {"collaborators": ...})
// for PgBackedCache(name="pipeline"), 1h TTL.
func writePipelineBlob(ctx context.Context, cleanID, field string, pool []NetworkCollaborator) {
	if db.Pool == nil {
		return
	}
	key := "pipeline::network_collaborators_" + cleanID + "_" + field
	now := time.Now().UTC()
	expires := now.Add(1 * time.Hour)
	data, err := json.Marshal(map[string]any{"v": map[string]any{"collaborators": pool}})
	if err != nil {
		return
	}
	if _, err := db.Pool.Exec(ctx, `
		INSERT INTO cache_entries (cache_key, data, last_synced, expires_at)
		VALUES ($1, $2::json, $3, $4)
		ON CONFLICT (cache_key) DO UPDATE SET
			data = EXCLUDED.data,
			last_synced = EXCLUDED.last_synced,
			expires_at = EXCLUDED.expires_at
	`, key, string(data), now, expires); err != nil {
		slog.Warn("network: pipeline blob write failed", "key", key, "err", err)
	}
}

func pgResearcherMetricsField(ctx context.Context, cleanID string) string {
	if db.Pool == nil {
		return ""
	}
	var f *string
	if err := db.Pool.QueryRow(ctx,
		`SELECT field_of_study FROM researcher_metrics WHERE openalex_id = $1 LIMIT 1`,
		cleanID).Scan(&f); err != nil {
		return ""
	}
	return strVal(f)
}

// fillStatsFromDB fills missing author stats from researcher_profiles, then
// researcher_metrics — the same two-table order as the Python fallback.
func fillStatsFromDB(ctx context.Context, missing []string, out map[string]authorStats) {
	if db.Pool == nil || len(missing) == 0 {
		return
	}
	variants := func(ids []string) []string {
		v := make([]string, 0, len(ids)*2)
		for _, m := range ids {
			v = append(v, m, "https://openalex.org/"+m)
		}
		return v
	}
	queryTable := func(baseSQL string, ids []string) {
		if len(ids) == 0 {
			return
		}
		args := make([]any, len(ids))
		for i, v := range ids {
			args[i] = v
		}
		rows, err := db.Pool.Query(ctx, baseSQL+inClause(1, len(ids)), args...)
		if err != nil {
			slog.Warn("network: stats DB fallback query failed", "err", err)
			return
		}
		defer rows.Close()
		for rows.Next() {
			var (
				id      string
				wc, hi  *int
				jsonCol []byte
				inst    *string
			)
			if rows.Scan(&id, &wc, &hi, &jsonCol, &inst) != nil {
				continue
			}
			short := cleanAuthorID(id)
			if _, ok := out[short]; ok {
				continue
			}
			var concepts []string
			if len(jsonCol) > 0 {
				_ = json.Unmarshal(jsonCol, &concepts)
			}
			out[short] = authorStats{
				worksCount:  intVal(wc),
				hIndex:      intVal(hi),
				concepts:    concepts,
				institution: strDefault(inst, "Independent Researcher"),
			}
		}
	}

	queryTable(`SELECT openalex_id, works_count, h_index, concepts, institution
	            FROM researcher_profiles WHERE openalex_id IN`, variants(missing))

	var still []string
	for _, m := range missing {
		if _, ok := out[m]; !ok {
			still = append(still, m)
		}
	}
	if len(still) == 0 {
		return
	}
	queryTable(`SELECT openalex_id, works_count, h_index, expertise, current_institution
	            FROM researcher_metrics WHERE openalex_id IN`, variants(still))
}

// ── Pure helpers ───────────────────────────────────────────────────────────

// computeJaccardSimilarity mirrors NetworkMixin._compute_jaccard_similarity:
// exact-set overlap plus 0.5 credit per set1 term that is a substring of (or
// contains) some set2 term, over the union size, capped at 1.0.
func computeJaccardSimilarity(list1, list2 []string) float64 {
	if len(list1) == 0 || len(list2) == 0 {
		return 0.0
	}
	set1 := toLowerSet(list1)
	set2 := toLowerSet(list2)
	if len(set1) == 0 || len(set2) == 0 {
		return 0.0
	}
	exact := make(map[string]bool)
	for u := range set1 {
		if set2[u] {
			exact[u] = true
		}
	}
	partial := 0.0
	for u := range set1 {
		if exact[u] {
			continue
		}
		for c := range set2 {
			if strings.Contains(u, c) || strings.Contains(c, u) {
				partial += 0.5
				break
			}
		}
	}
	union := make(map[string]bool, len(set1)+len(set2))
	for k := range set1 {
		union[k] = true
	}
	for k := range set2 {
		union[k] = true
	}
	if len(union) == 0 {
		return 0.0
	}
	res := (float64(len(exact)) + partial) / float64(len(union))
	if res > 1.0 {
		return 1.0
	}
	return res
}

func toLowerSet(xs []string) map[string]bool {
	out := make(map[string]bool, len(xs))
	for _, x := range xs {
		x = strings.ToLower(strings.TrimSpace(x))
		if x != "" {
			out[x] = true
		}
	}
	return out
}

var networkDomainKeywords = map[string][]string{
	"phys":   {"phys", "quantum", "spin", "antiferromagnet", "squaric", "condensed", "superconduct", "particle", "magnetic", "optical", "fluid", "thermodynamic", "mechanics", "gravity", "energy", "matter", "cosmology", "phonon", "semiconductor", "crystallography", "spectroscopy", "resonance", "laser", "field", "relativity", "plasma", "astro", "nuclear"},
	"comput": {"comput", "learn", "intel", "neural", "vision", "algorithm", "software", "network", "image", "data", "robot", "nlp", "processing", "code", "programming", "cyber", "security", "database", "graphics", "web"},
	"cs":     {"comput", "learn", "intel", "neural", "vision", "algorithm", "software", "network", "image", "data", "robot", "nlp", "processing", "code", "programming", "cyber", "security", "database", "graphics", "web"},
	"ai":     {"comput", "learn", "intel", "neural", "vision", "algorithm", "software", "network", "image", "data", "robot", "nlp", "processing", "code", "programming", "cyber", "security", "database", "graphics", "web"},
	"bio":    {"chem", "bio", "molec", "gene", "crispr", "dna", "rna", "enzyme", "protein", "cell", "genom", "nuclease", "chromatin", "nucleic", "medical", "clinical", "health", "disease", "drug", "pharma", "biotech", "immunology", "microbiology"},
	"chem":   {"chem", "molec", "organ", "inorgan", "spectroscop", "synthes", "reaction", "cataly", "polymer", "materials", "electro", "nano"},
	"math":   {"math", "algebra", "calculus", "geometry", "topology", "statistics", "probability", "discrete", "theorem", "equation", "numerical", "optimiz"},
	"eng":    {"eng", "mechanic", "electric", "civil", "chemical", "aerospace", "material", "device", "circuit", "system", "nano", "sensor", "failure"},
}

// isFieldSemanticallyRelevant is the Go port of
// app/services/platform/pipeline/text_utils.py::is_field_semantically_relevant.
func isFieldSemanticallyRelevant(collabField, collabPath, discipline string) bool {
	if discipline == "" {
		return true
	}
	disc := strings.ToLower(strings.TrimSpace(discipline))
	cf := strings.ToLower(strings.TrimSpace(collabField))
	cp := strings.ToLower(strings.TrimSpace(collabPath))

	// NB: matches the Python quirk where an empty collab field ("" is a
	// substring of anything) returns true.
	if strings.Contains(cf, disc) || strings.Contains(disc, cf) {
		return true
	}
	if strings.Contains(cp, disc) {
		return true
	}

	stripped := strings.ReplaceAll(strings.ReplaceAll(disc, "and", ""), "&", "")
	var discWords, collabWords []string
	for _, w := range strings.Fields(stripped) {
		if len(w) > 2 {
			discWords = append(discWords, w)
		}
	}
	for _, w := range strings.Fields(cf) {
		if len(w) > 2 {
			collabWords = append(collabWords, w)
		}
	}
	for _, dw := range discWords {
		for _, cw := range collabWords {
			if strings.Contains(dw, cw) || strings.Contains(cw, dw) {
				return true
			}
		}
	}

	matched := make(map[string]bool)
	for stem, kws := range networkDomainKeywords {
		for _, dw := range discWords {
			if strings.Contains(dw, stem) {
				for _, k := range kws {
					matched[k] = true
				}
				break
			}
		}
	}
	for k := range matched {
		if strings.Contains(cf, k) {
			return true
		}
		for _, cw := range collabWords {
			if strings.Contains(cw, k) || strings.Contains(k, cw) {
				return true
			}
		}
	}
	return false
}

var genericDisciplines = map[string]bool{
	"general research": true, "researcher": true, "multidisciplinary": true, "general": true,
}

// isWorkRelevantToDiscipline is the Go port of
// app/services/data/openalex_service.py::is_work_relevant_to_discipline.
func isWorkRelevantToDiscipline(w openalex.Work, discipline string) bool {
	if discipline == "" {
		return true
	}
	disc := strings.ToLower(strings.TrimSpace(discipline))
	if disc == "" || genericDisciplines[disc] {
		return true
	}

	kw := make(map[string]bool)
	for _, c := range w.Concepts {
		if c.DisplayName != "" {
			kw[strings.ToLower(c.DisplayName)] = true
		}
	}
	for _, t := range w.Topics {
		if t.DisplayName != "" {
			kw[strings.ToLower(t.DisplayName)] = true
		}
		for _, fo := range []openalex.FieldObj{t.Field, t.Subfield, t.Domain} {
			if fo.DisplayName != "" {
				kw[strings.ToLower(fo.DisplayName)] = true
			}
		}
	}
	if w.Title != "" {
		kw[strings.ToLower(w.Title)] = true
	}
	if j := w.PrimaryLocation.Source.DisplayName; j != "" {
		kw[strings.ToLower(j)] = true
	}
	if abs := reconstructAbstract(w.AbstractInvertedIndex); abs != "" {
		kw[strings.ToLower(abs)] = true
	}

	terms := make(map[string]bool)
	for _, t := range strings.Fields(disc) {
		if len(t) > 2 {
			terms[t] = true
		}
	}
	if len(terms) == 0 {
		terms[disc] = true
	}
	add := func(xs ...string) {
		for _, x := range xs {
			terms[x] = true
		}
	}
	if strings.Contains(disc, "phys") || strings.Contains(disc, "quantum") {
		add("phys", "quantum", "spin", "antiferromagnet", "squaric", "condensed", "superconduct", "particle", "magnetic", "optical", "fluid", "thermodynamic", "mechanics", "gravity", "energy", "matter", "cosmology")
	}
	if strings.Contains(disc, "comput") || strings.Contains(disc, "cs") || strings.Contains(disc, "ai") || strings.Contains(disc, "crypt") || strings.Contains(disc, "secur") {
		add("comput", "learn", "intel", "neural", "vision", "algorithm", "software", "network", "image", "data", "robot", "nlp", "processing", "crypt", "secur", "protocol", "key distribution")
	}
	if strings.Contains(disc, "biochem") || strings.Contains(disc, "bio") || strings.Contains(disc, "crispr") || strings.Contains(disc, "medic") || strings.Contains(disc, "health") {
		add("chem", "bio", "molec", "gene", "crispr", "dna", "rna", "enzyme", "protein", "cell", "genom", "nuclease", "chromatin", "nucleic", "medic", "health", "clinical")
	}
	if strings.Contains(disc, "chem") {
		add("chem", "molec", "organ", "inorgan", "spectroscop", "synthes", "reaction", "cataly")
	}

	for k := range kw {
		for t := range terms {
			if strings.Contains(k, t) {
				return true
			}
		}
	}
	return false
}

func reconstructAbstract(idx map[string][]int) string {
	if len(idx) == 0 {
		return ""
	}
	type wp struct {
		pos  int
		word string
	}
	var arr []wp
	for word, ps := range idx {
		for _, p := range ps {
			arr = append(arr, wp{p, word})
		}
	}
	sort.Slice(arr, func(i, j int) bool { return arr[i].pos < arr[j].pos })
	parts := make([]string, len(arr))
	for i, a := range arr {
		parts[i] = a.word
	}
	return strings.Join(parts, " ")
}

func sliceWindow(s []NetworkCollaborator, offset, limit int) []NetworkCollaborator {
	if offset < 0 {
		offset = 0
	}
	if offset >= len(s) {
		return []NetworkCollaborator{}
	}
	end := offset + limit
	if end > len(s) {
		end = len(s)
	}
	return s[offset:end]
}

func splitCSV(s string) []string {
	if strings.TrimSpace(s) == "" {
		return nil
	}
	parts := strings.Split(s, ",")
	out := make([]string, 0, len(parts))
	for _, p := range parts {
		out = append(out, strings.TrimSpace(p))
	}
	return out
}

func queryInt(c *gin.Context, key string, def int) int {
	v := strings.TrimSpace(c.Query(key))
	if v == "" {
		return def
	}
	n, err := strconv.Atoi(v)
	if err != nil {
		return def
	}
	return n
}

func clampInt(v, lo, hi int) int {
	if v < lo {
		return lo
	}
	if v > hi {
		return hi
	}
	return v
}

func strVal(p *string) string {
	if p == nil {
		return ""
	}
	return *p
}

func strDefault(p *string, def string) string {
	if p == nil || *p == "" {
		return def
	}
	return *p
}

func intVal(p *int) int {
	if p == nil {
		return 0
	}
	return *p
}
