package author

import (
	"encoding/json"
	"math"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/gin-gonic/gin"
	"github.com/skolab/backend-go/internal/services/openalex"
)

func approxEq(a, b float64) bool { return math.Abs(a-b) < 1e-9 }

func TestComputeJaccardSimilarity(t *testing.T) {
	tests := []struct {
		name   string
		a, b   []string
		want   float64
	}{
		{"either empty -> 0", nil, []string{"x"}, 0},
		{"both empty -> 0", nil, nil, 0},
		{"all-blank entries -> 0", []string{"  ", ""}, []string{"physics"}, 0},
		{"disjoint, no substring -> 0", []string{"physics"}, []string{"biology"}, 0},
		{"identical single -> 1", []string{"Quantum"}, []string{"quantum"}, 1},
		// set1={quantum,spin}, set2={quantum computing,spin}: exact={spin} (1),
		// "quantum" is a substring of "quantum computing" -> +0.5.
		// union = {quantum, spin, quantum computing} = 3. (1 + 0.5) / 3 = 0.5.
		{"partial substring credit", []string{"quantum", "spin"}, []string{"quantum computing", "spin"}, 0.5},
	}
	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			got := computeJaccardSimilarity(tc.a, tc.b)
			if !approxEq(got, tc.want) {
				t.Fatalf("computeJaccardSimilarity(%v,%v) = %v, want %v", tc.a, tc.b, got, tc.want)
			}
		})
	}
}

func TestComputeJaccardSimilarity_CappedAtOne(t *testing.T) {
	a := []string{"quantum", "quantum spin", "quantum field"}
	b := []string{"quantum"}
	if got := computeJaccardSimilarity(a, b); got > 1.0 {
		t.Fatalf("similarity not capped: got %v", got)
	}
}

func TestIsFieldSemanticallyRelevant(t *testing.T) {
	tests := []struct {
		name                        string
		field, path, discipline     string
		want                        bool
	}{
		{"empty discipline -> true", "biology", "", "", true},
		{"direct substring", "Condensed Matter Physics", "", "physics", true},
		{"empty collab field -> true (python quirk)", "", "", "physics", true},
		{"domain keyword expansion", "Quantum Spin Liquids", "", "physics", true},
		{"unrelated -> false", "Medieval History", "", "physics", false},
		{"path substring match", "Unknown", "co-authored a physics paper", "physics", true},
	}
	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			if got := isFieldSemanticallyRelevant(tc.field, tc.path, tc.discipline); got != tc.want {
				t.Fatalf("isFieldSemanticallyRelevant(%q,%q,%q) = %v, want %v",
					tc.field, tc.path, tc.discipline, got, tc.want)
			}
		})
	}
}

func TestIsWorkRelevantToDiscipline(t *testing.T) {
	physWork := openalex.Work{
		Title:    "Superconducting qubit coherence",
		Concepts: []openalex.Concept{{DisplayName: "Quantum mechanics"}},
	}
	bioWork := openalex.Work{
		Title:    "CRISPR gene editing in maize",
		Concepts: []openalex.Concept{{DisplayName: "Genetics"}},
	}
	if !isWorkRelevantToDiscipline(physWork, "") {
		t.Fatal("empty discipline should always be relevant")
	}
	if !isWorkRelevantToDiscipline(physWork, "multidisciplinary") {
		t.Fatal("generic discipline should always be relevant")
	}
	if !isWorkRelevantToDiscipline(physWork, "physics") {
		t.Fatal("physics work should match 'physics' via term expansion")
	}
	if isWorkRelevantToDiscipline(bioWork, "physics") {
		t.Fatal("biology work should not match 'physics'")
	}
}

func TestReconstructAbstract(t *testing.T) {
	idx := map[string][]int{"world": {1}, "hello": {0}, "again": {2, 4}, "hello2": {3}}
	got := reconstructAbstract(idx)
	want := "hello world again hello2 again"
	if got != want {
		t.Fatalf("reconstructAbstract = %q, want %q", got, want)
	}
	if reconstructAbstract(nil) != "" {
		t.Fatal("nil index should give empty string")
	}
}

func TestSliceWindow(t *testing.T) {
	rows := []NetworkCollaborator{{ID: "a"}, {ID: "b"}, {ID: "c"}, {ID: "d"}}
	if got := sliceWindow(rows, 0, 2); len(got) != 2 || got[0].ID != "a" || got[1].ID != "b" {
		t.Fatalf("window(0,2) = %v", got)
	}
	if got := sliceWindow(rows, 3, 10); len(got) != 1 || got[0].ID != "d" {
		t.Fatalf("window(3,10) = %v", got)
	}
	if got := sliceWindow(rows, 99, 10); len(got) != 0 {
		t.Fatalf("out-of-range offset should give empty, got %v", got)
	}
	if got := sliceWindow(rows, -5, 2); len(got) != 2 || got[0].ID != "a" {
		t.Fatalf("negative offset should clamp to 0, got %v", got)
	}
}

func TestSplitCSV(t *testing.T) {
	if got := splitCSV(""); got != nil {
		t.Fatalf("empty -> nil, got %v", got)
	}
	if got := splitCSV(" A1 , A2 ,"); len(got) != 3 || got[0] != "A1" || got[1] != "A2" || got[2] != "" {
		t.Fatalf("splitCSV trims and keeps trailing blank (python parity): %v", got)
	}
}

func TestClampInt(t *testing.T) {
	cases := [][3]int{{5, 1, 100}, {0, 1, 100}, {5000, 1, 100}, {50, 1, 100}}
	wants := []int{5, 1, 100, 50}
	for i, c := range cases {
		if got := clampInt(c[0], c[1], c[2]); got != wants[i] {
			t.Fatalf("clampInt%v = %d, want %d", c, got, wants[i])
		}
	}
}

// ── db.Pool == nil guards (no database in a unit test) ─────────────────────

func TestDBReaders_NilPoolAreSafe(t *testing.T) {
	// db.Pool is nil here — every DB helper must no-op / miss cleanly.
	if _, ok := readCachedConnections(t.Context(), "A1", map[string]bool{}, ""); ok {
		t.Fatal("readCachedConnections should miss with nil pool")
	}
	if _, ok := readPipelineBlob(t.Context(), "A1", ""); ok {
		t.Fatal("readPipelineBlob should miss with nil pool")
	}
	if f := pgResearcherMetricsField(t.Context(), "A1"); f != "" {
		t.Fatalf("pgResearcherMetricsField should be empty with nil pool, got %q", f)
	}
	// Writers must not panic with a nil pool.
	upsertResearcherProfile(t.Context(), &openalex.Author{ID: "https://openalex.org/A1"}, 7)
	writeConnections(t.Context(), "A1", []NetworkCollaborator{{ID: "A2", Depth: 1}})
	writePipelineBlob(t.Context(), "A1", "", []NetworkCollaborator{{ID: "A2"}})
	fillStatsFromDB(t.Context(), []string{"A2"}, map[string]authorStats{})
}

// ── handler: request-shape guard ──────────────────────────────────────────

func TestGetNetworkCollaborators_MissingAuthorIDIs400(t *testing.T) {
	gin.SetMode(gin.TestMode)
	r := gin.New()
	r.GET("/network_collaborators", GetNetworkCollaborators)

	req := httptest.NewRequest(http.MethodGet, "/network_collaborators", nil)
	w := httptest.NewRecorder()
	r.ServeHTTP(w, req)

	if w.Code != http.StatusBadRequest {
		t.Fatalf("status = %d, want 400", w.Code)
	}
	var body map[string]any
	if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
		t.Fatalf("body not JSON: %v", err)
	}
	if _, ok := body["error"]; !ok {
		t.Fatalf("expected an error key, got %s", w.Body.String())
	}
}

// ── response shape parity ────────────────────────────────────────────────

func TestNetworkCollaborator_JSONShape(t *testing.T) {
	row := NetworkCollaborator{
		ID: "https://openalex.org/A2", Name: "Grace Hopper", Institution: "USN",
		Field: "Computer Science", ConnectionPath: "Co-authored 'X' with Ada",
		RelevanceScore: 91, PapersCollaborated: 3, TotalPublications: 40, HIndex: 12, Depth: 1,
	}
	b, err := json.Marshal([]NetworkCollaborator{row})
	if err != nil {
		t.Fatalf("marshal: %v", err)
	}
	var arr []map[string]any
	if err := json.Unmarshal(b, &arr); err != nil {
		t.Fatalf("unmarshal: %v", err)
	}
	if len(arr) != 1 {
		t.Fatalf("want 1 element, got %d", len(arr))
	}
	got := arr[0]
	for _, k := range []string{
		"id", "name", "institution", "field", "connection_path",
		"relevance_score", "papers_collaborated", "total_publications", "h_index", "depth",
	} {
		if _, ok := got[k]; !ok {
			t.Fatalf("missing json key %q in %s", k, string(b))
		}
	}
	if got["relevance_score"].(float64) != 91 {
		t.Fatalf("relevance_score = %v, want 91", got["relevance_score"])
	}
}
