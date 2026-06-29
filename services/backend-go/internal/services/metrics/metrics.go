// Package metrics implements the 10 Modern Research Metrics in pure Go.
// These are pure mathematical computations with no external dependencies —
// exactly the kind of logic that should run in Go, not Python.
package metrics

import (
	"math"
)

// DisruptionScore computes the D-Index: D = (N1 - N2) / (N1 + N2 + N3).
//
//   - N1: papers citing only this work (disruptive — it displaced prior work)
//   - N2: papers citing both this work and its references (consolidating)
//   - N3: papers citing only the references (this work had no direct influence)
func DisruptionScore(n1, n2, n3 int) float64 {
	denom := n1 + n2 + n3
	if denom == 0 {
		return 0
	}
	return round3(float64(n1-n2) / float64(denom))
}

// CitationAcceleration computes the second-order difference in yearly citations.
// Requires at least 3 data points sorted ascending by year.
// A positive value indicates accelerating citation growth.
func CitationAcceleration(yearlyCitations []int) int {
	if len(yearlyCitations) < 3 {
		return 0
	}
	c := yearlyCitations
	return c[len(c)-1] - 2*c[len(c)-2] + c[len(c)-3]
}

// FutureImpact is a linear approximation of predicted future impact (0–100).
// Weights: early citations × 2.5, journal score × 15, h-index × 0.5.
func FutureImpact(earlyCitations int, journalScore float64, hIndex int) float64 {
	score := float64(earlyCitations)*2.5 + journalScore*15 + float64(hIndex)*0.5
	return math.Min(round1(score), 100.0)
}

// NetworkCentrality returns a betweenness-centrality proxy (0–100) for a node
// within a co-authorship graph represented as an adjacency list.
// Uses a simplified BFS-based betweenness approximation suitable for small graphs.
func NetworkCentrality(graph map[string][]string, nodeID string) float64 {
	if len(graph) < 2 {
		return 100.0
	}
	// Count shortest paths that pass through nodeID.
	nodes := make([]string, 0, len(graph))
	for n := range graph {
		nodes = append(nodes, n)
	}

	totalPaths := 0
	pathsThrough := 0

	for i, s := range nodes {
		for _, t := range nodes[i+1:] {
			if s == t {
				continue
			}
			total, through := bfsPathCount(graph, s, t, nodeID)
			totalPaths += total
			pathsThrough += through
		}
	}

	if totalPaths == 0 {
		return 0
	}
	return round1(float64(pathsThrough) / float64(totalPaths) * 100)
}

// bfsPathCount returns (totalShortestPaths, pathsThroughNode) between src and dst.
func bfsPathCount(graph map[string][]string, src, dst, via string) (int, int) {
	type nodeInfo struct {
		count   int
		through int // paths that passed through `via`
	}
	visited := map[string]nodeInfo{src: {count: 1}}
	queue := []string{src}

	for len(queue) > 0 {
		cur := queue[0]
		queue = queue[1:]
		curInfo := visited[cur]
		for _, nb := range graph[cur] {
			info, seen := visited[nb]
			if !seen {
				queue = append(queue, nb)
				info = nodeInfo{}
			}
			info.count += curInfo.count
			if cur == via || curInfo.through > 0 {
				info.through += curInfo.count
			}
			visited[nb] = info
		}
	}

	info := visited[dst]
	return info.count, info.through
}

// SemanticNovelty returns 1 − max_cosine_similarity over peer embeddings,
// scaled to 0–100. A score of 100 means the work is maximally novel.
func SemanticNovelty(embedding []float64, peers [][]float64) float64 {
	if len(peers) == 0 {
		return 100.0
	}
	maxSim := 0.0
	for _, p := range peers {
		sim := cosineSim(embedding, p)
		if sim > maxSim {
			maxSim = sim
		}
	}
	return round1((1.0 - maxSim) * 100)
}

func cosineSim(a, b []float64) float64 {
	if len(a) != len(b) {
		return 0
	}
	var dot, normA, normB float64
	for i := range a {
		dot += a[i] * b[i]
		normA += a[i] * a[i]
		normB += b[i] * b[i]
	}
	if normA == 0 || normB == 0 {
		return 0
	}
	return dot / (math.Sqrt(normA) * math.Sqrt(normB))
}

// InterdisciplinaryIndex computes a Shannon entropy over topic counts, normalised to 0–100.
// Higher scores indicate research that spans more distinct disciplines.
func InterdisciplinaryIndex(topicCounts map[string]int) float64 {
	if len(topicCounts) == 0 {
		return 0
	}
	total := 0
	for _, c := range topicCounts {
		total += c
	}
	entropy := 0.0
	for _, c := range topicCounts {
		p := float64(c) / float64(total)
		if p > 0 {
			entropy -= p * math.Log(p)
		}
	}
	// Normalise: max entropy for 4 domains ≈ ln(4) ≈ 1.38
	return math.Min(round1((entropy/1.2)*100), 100.0)
}

// PolicyPatentScore returns a weighted sum of policy and patent citations.
// Policy cite weight = 5, patent cite weight = 10.
func PolicyPatentScore(policyCites, patentCites int) int {
	return policyCites*5 + patentCites*10
}

// OpenScienceScore rewards openness across four dimensions (0–100):
// code availability, dataset availability, open access, preprint availability.
func OpenScienceScore(hasCode, hasData, isOpenAccess, hasPreprint bool) int {
	count := 0
	for _, b := range []bool{hasCode, hasData, isOpenAccess, hasPreprint} {
		if b {
			count++
		}
	}
	return int(float64(count) / 4.0 * 100)
}

// CollaborationDiversity computes Shannon entropy over collaborating countries (0–100).
// A score of 100 means perfectly balanced international collaboration.
func CollaborationDiversity(countries []string) float64 {
	if len(countries) == 0 {
		return 0
	}
	counts := make(map[string]int)
	for _, c := range countries {
		counts[c]++
	}
	total := float64(len(countries))
	entropy := 0.0
	for _, c := range counts {
		p := float64(c) / total
		entropy -= p * math.Log(p)
	}
	// Normalise to ~ln(10) ≈ 2.3 (10 diverse countries = near perfect score)
	return math.Min(round1((entropy/2.3)*100), 100.0)
}

// ResearchConsistency measures citation stability (0–100).
// High variance → low consistency. Score = 100 / (1 + sqrt(variance)).
func ResearchConsistency(citationsPerYear []int) float64 {
	if len(citationsPerYear) < 2 {
		return 100.0
	}
	mean := 0.0
	for _, c := range citationsPerYear {
		mean += float64(c)
	}
	mean /= float64(len(citationsPerYear))

	variance := 0.0
	for _, c := range citationsPerYear {
		diff := float64(c) - mean
		variance += diff * diff
	}
	variance /= float64(len(citationsPerYear))

	if variance == 0 {
		return 100.0
	}
	return round1(100.0 / (1.0 + math.Sqrt(variance)))
}

// ── Batch helper ─────────────────────────────────────────────────────────────

// Input holds all inputs for a single batch calculation.
type Input struct {
	N1, N2, N3       int
	YearlyCitations  []int
	EarlyCitations   int
	JournalScore     float64
	HIndex           int
	TopicCounts      map[string]int
	PolicyCites      int
	PatentCites      int
	HasCode          bool
	HasData          bool
	IsOpenAccess     bool
	HasPreprint      bool
	Countries        []string
	Embedding        []float64
	PeerEmbeddings   [][]float64
}

// Result holds all 10 computed metrics.
type Result struct {
	DisruptionScore        float64 `json:"disruption_score"`
	CitationAcceleration   int     `json:"citation_acceleration"`
	FutureImpact           float64 `json:"future_impact_score"`
	SemanticNovelty        float64 `json:"semantic_novelty"`
	InterdisciplinaryIndex float64 `json:"interdisciplinary_index"`
	PolicyPatentScore      int     `json:"policy_patent_score"`
	OpenScienceScore       int     `json:"open_science_score"`
	CollaborationDiversity float64 `json:"collaboration_diversity"`
	ResearchConsistency    float64 `json:"research_consistency"`
}

// Compute calculates all 10 metrics in one call.
func Compute(in Input) Result {
	return Result{
		DisruptionScore:        DisruptionScore(in.N1, in.N2, in.N3),
		CitationAcceleration:   CitationAcceleration(in.YearlyCitations),
		FutureImpact:           FutureImpact(in.EarlyCitations, in.JournalScore, in.HIndex),
		SemanticNovelty:        SemanticNovelty(in.Embedding, in.PeerEmbeddings),
		InterdisciplinaryIndex: InterdisciplinaryIndex(in.TopicCounts),
		PolicyPatentScore:      PolicyPatentScore(in.PolicyCites, in.PatentCites),
		OpenScienceScore:       OpenScienceScore(in.HasCode, in.HasData, in.IsOpenAccess, in.HasPreprint),
		CollaborationDiversity: CollaborationDiversity(in.Countries),
		ResearchConsistency:    ResearchConsistency(in.YearlyCitations),
	}
}

// ── Utilities ─────────────────────────────────────────────────────────────────

func round1(v float64) float64 { return math.Round(v*10) / 10 }
func round3(v float64) float64 { return math.Round(v*1000) / 1000 }
