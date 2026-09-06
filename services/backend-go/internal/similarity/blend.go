package similarity

import (
	"fmt"
	"os"
	"sort"
	"strconv"
	"strings"
)

// Blend weights for /similar_researchers. Sum need not be 1 — the final score
// is used only for ordering + MMR, not shown as a percentage. Override any of
// them with SIM_W_* env vars for live tuning without a redeploy.
var (
	wEmbedding = envFloat("SIM_W_EMBEDDING", 0.50) // topical closeness (vector cosine)
	wCoauthor  = envFloat("SIM_W_COAUTHOR", 0.25)  // shared-collaborator Jaccard
	wConcept   = envFloat("SIM_W_CONCEPT", 0.15)   // shared field/topic Jaccard
	wCohort    = envFloat("SIM_W_COHORT", 0.10)    // same institution
)

func envFloat(key string, def float64) float64 {
	if v := strings.TrimSpace(os.Getenv(key)); v != "" {
		if f, err := strconv.ParseFloat(v, 64); err == nil {
			return f
		}
	}
	return def
}

// jaccardSim mirrors author.computeJaccardSimilarity (itself a port of the
// Python NetworkMixin helper): exact set overlap + 0.5 credit per term that is
// a substring of some term in the other set, over the union, capped at 1.0.
// Copied rather than imported to keep similarity free of an import cycle with
// the author package (which re-points search_author at this engine).
func jaccardSim(a, b []string) float64 {
	sa, sb := toLowerSet(a), toLowerSet(b)
	if len(sa) == 0 || len(sb) == 0 {
		return 0
	}
	exact := map[string]bool{}
	for u := range sa {
		if sb[u] {
			exact[u] = true
		}
	}
	partial := 0.0
	for u := range sa {
		if exact[u] {
			continue
		}
		for c := range sb {
			if strings.Contains(u, c) || strings.Contains(c, u) {
				partial += 0.5
				break
			}
		}
	}
	union := map[string]bool{}
	for k := range sa {
		union[k] = true
	}
	for k := range sb {
		union[k] = true
	}
	v := (float64(len(exact)) + partial) / float64(len(union))
	if v > 1 {
		return 1
	}
	return v
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

// countShared returns how many entries of a appear (lower-cased) in b.
func countShared(a, b []string) int {
	sb := toLowerSet(b)
	n := 0
	for _, x := range a {
		if sb[strings.ToLower(strings.TrimSpace(x))] {
			n++
		}
	}
	return n
}

// overlapFrac is |a ∩ b| / min(|a|,|b|) — used for bibliographic coupling on
// referenced_works, where set sizes vary a lot and Jaccard would under-credit
// a short reference list that is fully contained in a long one.
func overlapFrac(a, b []string) float64 {
	if len(a) == 0 || len(b) == 0 {
		return 0
	}
	shared := countShared(a, b)
	d := len(a)
	if len(b) < d {
		d = len(b)
	}
	return float64(shared) / float64(d)
}

// scoredResearcher is a blended candidate before MMR.
type scoredResearcher struct {
	row     authorRow
	score   float64
	cosine  float64
	coJac   float64 // shared-collaborator Jaccard (component of score)
	shared  int
	sameOrg bool
}

// blendResearcher computes the weighted similarity of cand to me.
func blendResearcher(me, cand authorRow) scoredResearcher {
	cos := 1 - cand.Dist // pgvector <=> is cosine distance
	if cand.Dist == 0 && len(me.Vec) == embedDim && len(cand.Vec) == embedDim {
		cos = cosine(me.Vec, cand.Vec)
	}
	if cos < 0 {
		cos = 0
	}
	coJac := jaccardSim(me.Coauthors, cand.Coauthors)
	conJac := jaccardSim(me.Concepts, cand.Concepts)
	sameOrg := me.Institution != "" &&
		strings.EqualFold(strings.TrimSpace(me.Institution), strings.TrimSpace(cand.Institution))
	orgBonus := 0.0
	if sameOrg {
		orgBonus = 1.0
	}
	s := wEmbedding*cos + wCoauthor*coJac + wConcept*conJac + wCohort*orgBonus
	return scoredResearcher{
		row:     cand,
		score:   s,
		cosine:  cos,
		coJac:   coJac,
		shared:  countShared(me.Coauthors, cand.Coauthors),
		sameOrg: sameOrg,
	}
}

// whyResearcher builds the human "why this person" string, most-informative
// signal first.
func whyResearcher(s scoredResearcher, field string) string {
	parts := make([]string, 0, 3)
	if s.sameOrg {
		parts = append(parts, "same institution")
	} else if field != "" {
		parts = append(parts, "works in "+field)
	}
	if s.shared == 1 {
		parts = append(parts, "1 shared collaborator")
	} else if s.shared > 1 {
		parts = append(parts, fmt.Sprintf("%d shared collaborators", s.shared))
	}
	parts = append(parts, fmt.Sprintf("%.0f%% topical match", s.cosine*100))
	return strings.Join(parts, " · ")
}

// sortByScoreDesc is a stable order for deterministic output before MMR.
func sortByScoreDesc(xs []scoredResearcher) {
	sort.SliceStable(xs, func(i, j int) bool { return xs[i].score > xs[j].score })
}
