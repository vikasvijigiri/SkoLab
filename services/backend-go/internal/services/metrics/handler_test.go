package metrics

import (
	"bytes"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/gin-gonic/gin"
)

func init() {
	gin.SetMode(gin.TestMode)
}

func router() *gin.Engine {
	r := gin.New()
	r.POST("/internal/compute_metrics", ComputeHandler)
	return r
}

func post(r *gin.Engine, body string, headers map[string]string) *httptest.ResponseRecorder {
	req := httptest.NewRequest(http.MethodPost, "/internal/compute_metrics", bytes.NewBufferString(body))
	req.Header.Set("Content-Type", "application/json")
	for k, v := range headers {
		req.Header.Set(k, v)
	}
	w := httptest.NewRecorder()
	r.ServeHTTP(w, req)
	return w
}

func TestComputeHandler_ReturnsAllNineMetrics(t *testing.T) {
	body := `{
		"n1": 40, "n2": 35, "n3": 25,
		"yearly_citations": [5, 8, 12, 20],
		"early_citations": 10, "journal_score": 2.5, "h_index": 15,
		"topic_counts": {"AI": 3, "Genomics": 1},
		"policy_cites": 0, "patent_cites": 0,
		"has_code": true, "has_data": false, "is_open_access": true, "has_preprint": true,
		"countries": ["US", "IN", "DE"]
	}`
	w := post(router(), body, nil)
	if w.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200: %s", w.Code, w.Body.String())
	}

	var result Result
	if err := json.Unmarshal(w.Body.Bytes(), &result); err != nil {
		t.Fatalf("response not valid Result JSON: %v (%s)", err, w.Body.String())
	}

	// Cross-check against calling the pure functions directly with the same
	// inputs -- the handler must not silently transform anything.
	want := Compute(Input{
		N1: 40, N2: 35, N3: 25,
		YearlyCitations: []int{5, 8, 12, 20},
		EarlyCitations:  10, JournalScore: 2.5, HIndex: 15,
		TopicCounts:  map[string]int{"AI": 3, "Genomics": 1},
		HasCode:      true, IsOpenAccess: true, HasPreprint: true,
		Countries: []string{"US", "IN", "DE"},
	})
	if result != want {
		t.Fatalf("result = %+v, want %+v", result, want)
	}
}

func TestComputeHandler_MalformedBodyIs400(t *testing.T) {
	w := post(router(), `not json`, nil)
	if w.Code != http.StatusBadRequest {
		t.Fatalf("status = %d, want 400", w.Code)
	}
}

func TestComputeHandler_WrongTokenIs401(t *testing.T) {
	t.Setenv("INTERNAL_API_TOKEN", "expected-secret")
	w := post(router(), `{}`, map[string]string{"X-Internal-Token": "wrong"})
	if w.Code != http.StatusUnauthorized {
		t.Fatalf("status = %d, want 401", w.Code)
	}
}

func TestComputeHandler_CorrectTokenIsAccepted(t *testing.T) {
	t.Setenv("INTERNAL_API_TOKEN", "expected-secret")
	w := post(router(), `{"h_index": 5}`, map[string]string{"X-Internal-Token": "expected-secret"})
	if w.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200: %s", w.Code, w.Body.String())
	}
}

func TestComputeHandler_UnsetTokenSkipsCheck(t *testing.T) {
	// Matches Python's _check_internal_token: an unset INTERNAL_API_TOKEN
	// means local dev needs no configuration.
	w := post(router(), `{"h_index": 5}`, nil)
	if w.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200 (no token configured -> check skipped)", w.Code)
	}
}
