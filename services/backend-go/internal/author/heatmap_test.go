package author

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/gin-gonic/gin"
)

func init() { gin.SetMode(gin.TestMode) }

// A missing author_id must 400 before any cache / Firestore / OpenAlex call, so
// this path is safe to run with no DB and no network.
func TestGetCitationHeatmap_RequiresAuthorID(t *testing.T) {
	w := httptest.NewRecorder()
	c, _ := gin.CreateTestContext(w)
	c.Request = httptest.NewRequest(http.MethodGet, "/api/v1/citation_heatmap", nil)

	GetCitationHeatmap(c)

	if w.Code != http.StatusBadRequest {
		t.Fatalf("status = %d, want %d", w.Code, http.StatusBadRequest)
	}
}

func TestHeatmapFromDoc_CoercesFirestoreShapes(t *testing.T) {
	// Firestore-ish: numbers arrive as float64/int64, arrays as []interface{}.
	doc := map[string]any{
		"years":               []any{float64(2021), float64(2022), float64(2023)},
		"citations":           []any{float64(10), float64(25), float64(40)},
		"works":               []any{float64(2), float64(3), float64(1)},
		"institutional_reach": float64(12),
		"h_index":             float64(8),
		"last_synced":         "2026-09-04T00:00:00Z", // dropped by the caller; harmless here
	}

	hm := heatmapFromDoc(doc)

	if got := []int{2021, 2022, 2023}; !equalInts(hm.Years, got) {
		t.Errorf("Years = %v, want %v", hm.Years, got)
	}
	if got := []int{10, 25, 40}; !equalInts(hm.Citations, got) {
		t.Errorf("Citations = %v, want %v", hm.Citations, got)
	}
	if got := []int{2, 3, 1}; !equalInts(hm.Works, got) {
		t.Errorf("Works = %v, want %v", hm.Works, got)
	}
	if hm.InstitutionalReach != 12 {
		t.Errorf("InstitutionalReach = %v, want 12", hm.InstitutionalReach)
	}
	if hm.HIndex != 8 {
		t.Errorf("HIndex = %d, want 8", hm.HIndex)
	}
}

func TestHeatmapFromDoc_EmptyMapGivesEmptySlicesNotNil(t *testing.T) {
	hm := heatmapFromDoc(map[string]any{})
	if hm.Years == nil || hm.Citations == nil || hm.Works == nil {
		t.Fatalf("empty doc produced nil slices: %+v", hm)
	}
	b, _ := json.Marshal(hm)
	// Serialised empty heatmap must carry [] arrays, matching Python's shape.
	if string(b) != `{"years":[],"citations":[],"works":[],"institutional_reach":0,"h_index":0}` {
		t.Errorf("empty heatmap JSON = %s", b)
	}
}

func equalInts(a, b []int) bool {
	if len(a) != len(b) {
		return false
	}
	for i := range a {
		if a[i] != b[i] {
			return false
		}
	}
	return true
}
