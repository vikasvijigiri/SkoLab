package metrics

import (
	"net/http"
	"os"

	"github.com/gin-gonic/gin"
)

// ComputeRequest mirrors Input, with JSON tags matching the field names
// Python's researcher_worker.py already computes and previously passed to
// its own (now-retired) duplicate of this same math.
type ComputeRequest struct {
	N1              int            `json:"n1"`
	N2              int            `json:"n2"`
	N3              int            `json:"n3"`
	YearlyCitations []int          `json:"yearly_citations"`
	EarlyCitations  int            `json:"early_citations"`
	JournalScore    float64        `json:"journal_score"`
	HIndex          int            `json:"h_index"`
	TopicCounts     map[string]int `json:"topic_counts"`
	PolicyCites     int            `json:"policy_cites"`
	PatentCites     int            `json:"patent_cites"`
	HasCode         bool           `json:"has_code"`
	HasData         bool           `json:"has_data"`
	IsOpenAccess    bool           `json:"is_open_access"`
	HasPreprint     bool           `json:"has_preprint"`
	Countries       []string       `json:"countries"`
}

// checkInternalToken mirrors app/api/v1/endpoints/internal.py's
// _check_internal_token exactly: when INTERNAL_API_TOKEN is unset, the check
// is skipped (local dev needs no configuration); when set, the caller's
// X-Internal-Token header must match.
func checkInternalToken(c *gin.Context) bool {
	expected := os.Getenv("INTERNAL_API_TOKEN")
	if expected == "" {
		return true
	}
	if c.GetHeader("X-Internal-Token") != expected {
		c.JSON(http.StatusUnauthorized, gin.H{"error": "invalid internal token"})
		return false
	}
	return true
}

// ComputeHandler handles POST /internal/compute_metrics -- the only caller is
// researcher_worker.py's teleport enrichment worker (Python has no other
// route to the Go gateway; see main.go's registration comment for why this
// one exists). Not proxied to browsers.
func ComputeHandler(c *gin.Context) {
	if !checkInternalToken(c) {
		return
	}

	var req ComputeRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	result := Compute(Input{
		N1:              req.N1,
		N2:              req.N2,
		N3:              req.N3,
		YearlyCitations: req.YearlyCitations,
		EarlyCitations:  req.EarlyCitations,
		JournalScore:    req.JournalScore,
		HIndex:          req.HIndex,
		TopicCounts:     req.TopicCounts,
		PolicyCites:     req.PolicyCites,
		PatentCites:     req.PatentCites,
		HasCode:         req.HasCode,
		HasData:         req.HasData,
		IsOpenAccess:    req.IsOpenAccess,
		HasPreprint:     req.HasPreprint,
		Countries:       req.Countries,
		// Embedding/PeerEmbeddings deliberately omitted: the teleport worker's
		// semantic_novelty is its own inline proxy formula, not a call into
		// this package's SemanticNovelty (see main.go's registration comment).
	})

	c.JSON(http.StatusOK, result)
}
