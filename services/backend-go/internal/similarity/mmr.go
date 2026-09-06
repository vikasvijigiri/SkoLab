package similarity

// mmrItem is one candidate for MMR re-ranking: a relevance score already
// computed against the query, plus the candidate's own vector for the
// redundancy term.
type mmrItem struct {
	relevance float64
	vec       vec384
}

// mmrLambda: 0 = max diversity, 1 = max relevance. Matches the Python
// engine.py MMR_LAMBDA so both surfaces feel the same.
const mmrLambda = 0.6

// mmrSelect returns the indices of `k` items chosen by Maximal Marginal
// Relevance: each pick maximises λ·relevance − (1−λ)·max cosine-to-an-
// already-picked item. Ported from
// app/domains/recommendation/engine.py::mmr_diversify.
func mmrSelect(items []mmrItem, k int) []int {
	n := len(items)
	if n == 0 || k <= 0 {
		return nil
	}
	if k > n {
		k = n
	}
	selected := make([]int, 0, k)
	picked := make([]bool, n)

	for len(selected) < k {
		bestIdx, bestScore := -1, -1e18
		for i := 0; i < n; i++ {
			if picked[i] {
				continue
			}
			redundancy := 0.0
			for _, s := range selected {
				if c := cosine(items[i].vec, items[s].vec); c > redundancy {
					redundancy = c
				}
			}
			score := mmrLambda*items[i].relevance - (1-mmrLambda)*redundancy
			if score > bestScore {
				bestScore, bestIdx = score, i
			}
		}
		if bestIdx < 0 {
			break
		}
		picked[bestIdx] = true
		selected = append(selected, bestIdx)
	}
	return selected
}
