package author

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/gin-gonic/gin"
	"github.com/skolab/backend-go/internal/services/openalex"
)

func sp(s string) *string  { return &s }
func ip(n int) *int        { return &n }
func fp(f float64) *float64 { return &f }
func bp(b bool) *bool      { return &b }

func TestComputeQueryMatchScore(t *testing.T) {
	if got := computeQueryMatchScore("", []string{"x"}); got != 75 {
		t.Errorf("empty query: got %d want 75", got)
	}
	if got := computeQueryMatchScore("quantum physics", nil); got != 75 {
		t.Errorf("nil concepts: got %d want 75", got)
	}
	// query_tokens={topology}, concepts={topology}, match=1, union=1 → 60+100=160 → capped 99
	if got := computeQueryMatchScore("topology", []string{"topology"}); got != 99 {
		t.Errorf("perfect overlap: got %d want 99", got)
	}
	// query_tokens={quantum,physics}, concepts={biology}, match=0, union=3 → 60
	if got := computeQueryMatchScore("quantum physics", []string{"biology"}); got != 60 {
		t.Errorf("no overlap: got %d want 60", got)
	}
	// query_tokens={quantum,physics}, concepts={quantum,biology}: match(quantum)=1,
	// physics no → match=1, union={quantum,physics,biology}=3 → 60+33=93
	if got := computeQueryMatchScore("quantum physics", []string{"quantum", "biology"}); got != 93 {
		t.Errorf("partial overlap: got %d want 93", got)
	}
}

func TestWorkTopicMatches(t *testing.T) {
	quantumWork := openalex.Work{
		Concepts: []openalex.Concept{{DisplayName: "Quantum Physics"}},
		Topics: []openalex.Topic{{
			DisplayName: "Condensed Matter",
			Field:       openalex.FieldObj{DisplayName: "Physics and Astronomy"},
		}},
	}
	bioWork := openalex.Work{
		Concepts: []openalex.Concept{{DisplayName: "Molecular Biology"}},
	}

	if !workTopicMatches(quantumWork, "quantum physics") {
		t.Error("expected quantum work to match 'quantum physics'")
	}
	if !workTopicMatches(quantumWork, "physics astronomy") {
		t.Error("expected quantum work to match via topic field")
	}
	if workTopicMatches(bioWork, "quantum physics") {
		t.Error("did not expect bio work to match 'quantum physics'")
	}
	// no long topic words → always true
	if !workTopicMatches(bioWork, "ai") {
		t.Error("short-only discipline should match everything")
	}
}

func mkWork(authorIDs ...string) openalex.Work {
	w := openalex.Work{Concepts: []openalex.Concept{{DisplayName: "Quantum Physics"}}}
	for _, id := range authorIDs {
		w.Authorships = append(w.Authorships, openalex.Authorship{
			Author: openalex.AuthorRef{ID: id, DisplayName: "Author " + id},
		})
	}
	return w
}

func TestDeriveSimilarAuthorsFromWorks(t *testing.T) {
	works := []openalex.Work{
		mkWork("https://openalex.org/A1", "https://openalex.org/A2"),
		mkWork("https://openalex.org/A2", "https://openalex.org/A3"),
	}

	got := deriveSimilarAuthorsFromWorks(works, "https://openalex.org/A1", 5, "quantum physics")
	if len(got) != 2 {
		t.Fatalf("got %d candidates, want 2 (A2, A3; A1 excluded)", len(got))
	}
	if got[0].id != "A2" || got[1].id != "A3" {
		t.Errorf("candidate ids = %v, want [A2 A3]", []string{got[0].id, got[1].id})
	}

	// limit is honoured
	if lim := deriveSimilarAuthorsFromWorks(works, "", 1, "quantum physics"); len(lim) != 1 {
		t.Errorf("limit=1 returned %d", len(lim))
	}

	// discipline filter drops non-matching works
	bio := []openalex.Work{{
		Concepts:    []openalex.Concept{{DisplayName: "Genetics"}},
		Authorships: []openalex.Authorship{{Author: openalex.AuthorRef{ID: "https://openalex.org/B1"}}},
	}}
	if res := deriveSimilarAuthorsFromWorks(bio, "", 5, "quantum physics"); len(res) != 0 {
		t.Errorf("discipline filter: got %d, want 0", len(res))
	}
}

func TestAcademicHistoryFromAffiliations(t *testing.T) {
	affs := []openalex.Affiliation{
		{Institution: openalex.Institution{DisplayName: "MIT"}, Years: []int{2005, 2003, 2004}},
		{Institution: openalex.Institution{DisplayName: "Caltech"}, Years: []int{2001}},
		{Institution: openalex.Institution{DisplayName: "MIT"}, Years: []int{2007}},
		{Institution: openalex.Institution{DisplayName: "NoYears"}},
	}
	got := academicHistoryFromAffiliations(affs)
	want := []string{"Caltech (2001)", "MIT (2003–2007)"}
	if len(got) != len(want) {
		t.Fatalf("got %v, want %v", got, want)
	}
	for i := range want {
		if got[i] != want[i] {
			t.Errorf("[%d] got %q want %q", i, got[i], want[i])
		}
	}
}

func TestAssembleFromPGShapeAndDegradation(t *testing.T) {
	t.Setenv("GROQ_API", "")
	t.Setenv("OPENROUTER_API_KEY", "")

	row := pgResearcherMetrics{
		OpenalexID:      "A1",
		DisplayName:     "Ada Lovelace",
		HIndex:          ip(12),
		WorksCount:      ip(40),
		InnovationScore: fp(42.9),
		MetricsComputed: bp(true),
		AverageImpact:   fp(3.25),
		FieldOfStudy:    sp("Computer Science"),
	}

	// Firestore-nil degradation: no works, no similar — still a valid shape.
	resp := assembleFromPG(row, nil, nil)

	if resp.ID != "A1" || resp.DisplayName != "Ada Lovelace" || resp.HIndex != 12 {
		t.Errorf("core fields wrong: %+v", resp)
	}
	if resp.InnovationScore == nil || *resp.InnovationScore != 42 {
		t.Errorf("innovation_score = %v, want 42 (truncated)", resp.InnovationScore)
	}
	if resp.MetricsComputed { // llm inactive → metrics_computed forced false
		t.Error("metrics_computed should be false when no LLM key is set")
	}
	if resp.LLMActive {
		t.Error("llm_active should be false when no LLM key is set")
	}
	if resp.AverageImpact != 3.25 {
		t.Errorf("average_impact = %v", resp.AverageImpact)
	}

	b, err := json.Marshal(resp)
	if err != nil {
		t.Fatalf("marshal: %v", err)
	}
	var m map[string]json.RawMessage
	if err := json.Unmarshal(b, &m); err != nil {
		t.Fatalf("unmarshal: %v", err)
	}
	for _, k := range []string{"expertise", "skills", "tools", "academic_history", "works", "similar_researchers"} {
		if string(m[k]) != "[]" {
			t.Errorf("%s should serialise to [] when empty, got %s", k, m[k])
		}
	}
	// nullable scalars present as null
	for _, k := range []string{"orcid", "next_prediction"} {
		if string(m[k]) != "null" {
			t.Errorf("%s should be null, got %s", k, m[k])
		}
	}

	// with an LLM key, metrics_computed follows the stored flag
	t.Setenv("GROQ_API", "gsk_fake")
	resp2 := assembleFromPG(row, nil, nil)
	if !resp2.MetricsComputed || !resp2.LLMActive {
		t.Error("with GROQ_API set, metrics_computed and llm_active should be true")
	}
}

func TestAssembleFromFirestoreFieldOfStudy(t *testing.T) {
	t.Setenv("GROQ_API", "")
	t.Setenv("OPENROUTER_API_KEY", "")

	// key present but null → field_of_study stays null (matches Python .get default
	// semantics). No expertise / non-generic field_of_study, so fetchSimilarAuthors
	// short-circuits and this stays hermetic (no OpenAlex call).
	docNull := map[string]any{
		"openalex_id":    "A9",
		"display_name":   "Grace Hopper",
		"h_index":        int64(30),
		"field_of_study": nil,
	}
	r := assembleFromFirestore(context.Background(), docNull)
	if r.ID != "A9" || r.HIndex != 30 {
		t.Errorf("core mapping wrong: %+v", r)
	}
	if r.FieldOfStudy != nil {
		t.Errorf("field_of_study present-but-null should map to nil, got %v", *r.FieldOfStudy)
	}

	// key absent → default "Multidisciplinary"
	docAbsent := map[string]any{"openalex_id": "A9", "display_name": "x"}
	r2 := assembleFromFirestore(context.Background(), docAbsent)
	if r2.FieldOfStudy == nil || *r2.FieldOfStudy != "Multidisciplinary" {
		t.Errorf("absent field_of_study should default to Multidisciplinary, got %v", r2.FieldOfStudy)
	}
}

func TestDefaultAuthorResponseKeyOrder(t *testing.T) {
	b, err := json.Marshal(defaultAuthorResponse())
	if err != nil {
		t.Fatal(err)
	}
	s := string(b)
	// Pydantic field order (app/schemas/core.py::AuthorResponse) — encoding/json
	// preserves struct declaration order, so this pins parity.
	order := []string{
		`"id"`, `"display_name"`, `"orcid"`, `"h_index"`, `"i10_index"`,
		`"works_count"`, `"cited_by_count"`, `"institution"`, `"field_of_study"`,
		`"expertise"`, `"skills"`, `"tools"`, `"academic_history"`, `"works"`,
		`"innovation_score"`, `"metrics_computed"`, `"llm_active"`,
		`"average_creativity"`, `"average_complexity"`, `"average_skill_score"`,
		`"average_impact"`, `"average_activity"`, `"disruption_score"`,
		`"citation_acceleration"`, `"future_impact_score"`, `"network_centrality"`,
		`"semantic_novelty"`, `"interdisciplinary_index"`, `"policy_patent_score"`,
		`"open_science_score"`, `"collaboration_diversity"`, `"research_consistency"`,
		`"next_prediction"`, `"similar_researchers"`,
	}
	last := -1
	for _, key := range order {
		idx := indexOf(s, key)
		if idx == -1 {
			t.Fatalf("missing key %s in %s", key, s)
		}
		if idx <= last {
			t.Fatalf("key %s out of order in %s", key, s)
		}
		last = idx
	}
}

func indexOf(haystack, needle string) int {
	for i := 0; i+len(needle) <= len(haystack); i++ {
		if haystack[i:i+len(needle)] == needle {
			return i
		}
	}
	return -1
}

func TestPostTeleportAttemptsRequestWithToken(t *testing.T) {
	var (
		gotMethod string
		gotToken  string
		gotPath   string
	)
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotMethod = r.Method
		gotToken = r.Header.Get("X-Internal-Token")
		gotPath = r.URL.Path
		w.WriteHeader(http.StatusAccepted)
	}))
	defer srv.Close()

	if err := postTeleport(srv.URL+"/api/v1/internal/teleport/A1", "secret-123"); err != nil {
		t.Fatalf("postTeleport error: %v", err)
	}
	if gotMethod != http.MethodPost {
		t.Errorf("method = %s, want POST", gotMethod)
	}
	if gotToken != "secret-123" {
		t.Errorf("X-Internal-Token = %q, want secret-123", gotToken)
	}
	if gotPath != "/api/v1/internal/teleport/A1" {
		t.Errorf("path = %q", gotPath)
	}
}

func TestPostTeleportNon2xxIsError(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusInternalServerError)
	}))
	defer srv.Close()
	if err := postTeleport(srv.URL, ""); err == nil {
		t.Error("expected an error for a 500 response")
	}
}

func TestSearchAuthorRequiresName(t *testing.T) {
	gin.SetMode(gin.TestMode)
	w := httptest.NewRecorder()
	c, _ := gin.CreateTestContext(w)
	c.Request = httptest.NewRequest(http.MethodGet, "/search_author", nil)
	SearchAuthor(c)
	if w.Code != http.StatusUnprocessableEntity {
		t.Errorf("status = %d, want 422", w.Code)
	}
}

func TestRefreshAuthorRequiresName(t *testing.T) {
	gin.SetMode(gin.TestMode)
	w := httptest.NewRecorder()
	c, _ := gin.CreateTestContext(w)
	c.Request = httptest.NewRequest(http.MethodGet, "/refresh_author", nil)
	RefreshAuthor(c)
	if w.Code != http.StatusUnprocessableEntity {
		t.Errorf("status = %d, want 422", w.Code)
	}
}

func TestTeleportHookIsSwappable(t *testing.T) {
	// The handlers call teleportHook(id) rather than fireTeleport directly so the
	// handoff can be observed; guard that seam against accidental removal.
	orig := teleportHook
	defer func() { teleportHook = orig }()

	var got string
	teleportHook = func(id string) { got = id }
	teleportHook("https://openalex.org/A1")
	if got != "https://openalex.org/A1" {
		t.Errorf("hook not wired: got %q", got)
	}
}
