// Package similarity is the read side of the SkoLab similarity engine: kNN over
// the pgvector store the Python teleport worker populates (work_embeddings /
// author_embeddings), blended with the co-authorship graph and shared-concept
// signals, MMR-diversified. Embedding *computation* stays in Python
// (decisions/0010); this package only queries and ranks.
package similarity

import (
	"context"
	"math"
	"strconv"
	"strings"

	"github.com/skolab/backend-go/internal/db"
)

// vec384 is a fixed-width embedding row.
type vec384 = []float32

const embedDim = 384

// cleanID normalises "https://openalex.org/W123" / "W123" → "W123".
func cleanID(raw string) string {
	raw = strings.TrimSpace(raw)
	if raw == "" {
		return ""
	}
	raw = strings.TrimRight(raw, "/")
	if i := strings.LastIndexByte(raw, '/'); i >= 0 {
		raw = raw[i+1:]
	}
	return raw
}

// vecLiteral formats a vector as pgvector's text input form: "[0.1,0.2,...]".
func vecLiteral(v vec384) string {
	var b strings.Builder
	b.Grow(len(v)*10 + 2)
	b.WriteByte('[')
	for i, x := range v {
		if i > 0 {
			b.WriteByte(',')
		}
		b.WriteString(strconv.FormatFloat(float64(x), 'f', 7, 32))
	}
	b.WriteByte(']')
	return b.String()
}

// parseVecText parses pgvector's text output "[0.1,0.2,...]" back to a slice.
func parseVecText(s string) vec384 {
	s = strings.TrimSpace(s)
	s = strings.TrimPrefix(s, "[")
	s = strings.TrimSuffix(s, "]")
	if s == "" {
		return nil
	}
	parts := strings.Split(s, ",")
	out := make(vec384, 0, len(parts))
	for _, p := range parts {
		f, err := strconv.ParseFloat(strings.TrimSpace(p), 32)
		if err != nil {
			return nil
		}
		out = append(out, float32(f))
	}
	return out
}

// cosine of two L2-normalised vectors, clamped to [0,1]. Falls back to a manual
// dot product; the stored vectors are already normalised by the writer.
func cosine(a, b vec384) float64 {
	if len(a) == 0 || len(a) != len(b) {
		return 0
	}
	var dot, na, nb float64
	for i := range a {
		dot += float64(a[i]) * float64(b[i])
		na += float64(a[i]) * float64(a[i])
		nb += float64(b[i]) * float64(b[i])
	}
	if na == 0 || nb == 0 {
		return 0
	}
	c := dot / (math.Sqrt(na) * math.Sqrt(nb))
	if c < 0 {
		return 0
	}
	if c > 1 {
		return 1
	}
	return c
}

// ── work_embeddings reads ───────────────────────────────────────────────────

type workRow struct {
	ID       string
	Vec      vec384
	Title    string
	Concepts []string
	Refs     []string
	Year     int
	Dist     float64 // pgvector cosine distance to the query (kNN only)
}

// getWork returns the stored row for one work, ok=false when absent or no DB.
func getWork(ctx context.Context, workID string) (workRow, bool) {
	if db.Pool == nil {
		return workRow{}, false
	}
	var (
		r       workRow
		vecText string
		year    *int
	)
	err := db.Pool.QueryRow(ctx, `
		SELECT work_id, embedding::text, COALESCE(title, ''),
		       COALESCE(concepts, '{}'), COALESCE(referenced_works, '{}'),
		       publication_year
		FROM work_embeddings WHERE work_id = $1
	`, cleanID(workID)).Scan(&r.ID, &vecText, &r.Title, &r.Concepts, &r.Refs, &year)
	if err != nil {
		return workRow{}, false
	}
	r.Vec = parseVecText(vecText)
	if year != nil {
		r.Year = *year
	}
	if len(r.Vec) != embedDim {
		return workRow{}, false
	}
	return r, true
}

// knnWorks returns the `limit` nearest works to qvec, excluding excludeID.
func knnWorks(ctx context.Context, qvec vec384, excludeID string, limit int) []workRow {
	if db.Pool == nil || len(qvec) != embedDim {
		return nil
	}
	rows, err := db.Pool.Query(ctx, `
		SELECT work_id, embedding::text, COALESCE(title, ''),
		       COALESCE(concepts, '{}'), COALESCE(referenced_works, '{}'),
		       publication_year, embedding <=> $1::vector AS dist
		FROM work_embeddings
		WHERE work_id <> $2
		ORDER BY embedding <=> $1::vector
		LIMIT $3
	`, vecLiteral(qvec), cleanID(excludeID), limit)
	if err != nil {
		return nil
	}
	defer rows.Close()

	out := make([]workRow, 0, limit)
	for rows.Next() {
		var (
			r       workRow
			vecText string
			year    *int
		)
		if err := rows.Scan(&r.ID, &vecText, &r.Title, &r.Concepts, &r.Refs, &year, &r.Dist); err != nil {
			continue
		}
		r.Vec = parseVecText(vecText)
		if year != nil {
			r.Year = *year
		}
		out = append(out, r)
	}
	return out
}

// ── author_embeddings reads ─────────────────────────────────────────────────

type authorRow struct {
	ID          string
	Vec         vec384
	Concepts    []string
	Coauthors   []string
	Institution string
	WorksCount  int
	HIndex      int
	Dist        float64
}

func getAuthor(ctx context.Context, authorID string) (authorRow, bool) {
	if db.Pool == nil {
		return authorRow{}, false
	}
	var (
		r       authorRow
		vecText string
		inst    *string
		wc, hi  *int
	)
	err := db.Pool.QueryRow(ctx, `
		SELECT author_id, embedding::text, COALESCE(concepts, '{}'),
		       COALESCE(coauthor_ids, '{}'), institution, works_count, h_index
		FROM author_embeddings WHERE author_id = $1
	`, cleanID(authorID)).Scan(&r.ID, &vecText, &r.Concepts, &r.Coauthors, &inst, &wc, &hi)
	if err != nil {
		return authorRow{}, false
	}
	r.Vec = parseVecText(vecText)
	if inst != nil {
		r.Institution = *inst
	}
	if wc != nil {
		r.WorksCount = *wc
	}
	if hi != nil {
		r.HIndex = *hi
	}
	if len(r.Vec) != embedDim {
		return authorRow{}, false
	}
	return r, true
}

// knnAuthors returns the `limit` nearest authors to qvec, excluding excludeID.
func knnAuthors(ctx context.Context, qvec vec384, excludeID string, limit int) []authorRow {
	if db.Pool == nil || len(qvec) != embedDim {
		return nil
	}
	rows, err := db.Pool.Query(ctx, `
		SELECT author_id, embedding::text, COALESCE(concepts, '{}'),
		       COALESCE(coauthor_ids, '{}'), institution, works_count, h_index,
		       embedding <=> $1::vector AS dist
		FROM author_embeddings
		WHERE author_id <> $2
		ORDER BY embedding <=> $1::vector
		LIMIT $3
	`, vecLiteral(qvec), cleanID(excludeID), limit)
	if err != nil {
		return nil
	}
	defer rows.Close()

	out := make([]authorRow, 0, limit)
	for rows.Next() {
		var (
			r       authorRow
			vecText string
			inst    *string
			wc, hi  *int
		)
		if err := rows.Scan(&r.ID, &vecText, &r.Concepts, &r.Coauthors, &inst, &wc, &hi, &r.Dist); err != nil {
			continue
		}
		r.Vec = parseVecText(vecText)
		if inst != nil {
			r.Institution = *inst
		}
		if wc != nil {
			r.WorksCount = *wc
		}
		if hi != nil {
			r.HIndex = *hi
		}
		out = append(out, r)
	}
	return out
}

// excludedPeers returns the set of author OpenAlex ids the requesting user is
// already connected to — user_circles.peer_id plus accepted connections
// resolved to openalex ids. Empty when userID is blank or there is no DB.
func excludedPeers(ctx context.Context, userID string) map[string]bool {
	excl := map[string]bool{}
	if db.Pool == nil || strings.TrimSpace(userID) == "" {
		return excl
	}
	// user_circles.peer_id is a users.id; map to that user's openalex_id.
	rows, err := db.Pool.Query(ctx, `
		SELECT u.openalex_id
		FROM user_circles uc
		JOIN users u ON u.id = uc.peer_id
		WHERE uc.user_id = $1 AND u.openalex_id IS NOT NULL AND u.openalex_id <> ''
		UNION
		SELECT u.openalex_id
		FROM connections c
		JOIN users u ON u.id = c.connected_user_id
		WHERE c.user_id = $1 AND c.status = 'accepted'
		  AND u.openalex_id IS NOT NULL AND u.openalex_id <> ''
	`, userID)
	if err != nil {
		return excl
	}
	defer rows.Close()
	for rows.Next() {
		var oid string
		if rows.Scan(&oid) == nil {
			excl[cleanID(oid)] = true
		}
	}
	return excl
}
