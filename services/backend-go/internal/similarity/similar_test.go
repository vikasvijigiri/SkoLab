package similarity

import (
	"math"
	"testing"
)

func approx(a, b float64) bool { return math.Abs(a-b) < 1e-6 }

func TestVecLiteralRoundTrip(t *testing.T) {
	in := vec384{0.1, -0.25, 0.3333333, 1, 0}
	lit := vecLiteral(in)
	if lit != "[0.1000000,-0.2500000,0.3333333,1.0000000,0.0000000]" {
		t.Fatalf("unexpected literal: %s", lit)
	}
	out := parseVecText(lit)
	if len(out) != len(in) {
		t.Fatalf("len mismatch: %d vs %d", len(out), len(in))
	}
	for i := range in {
		if !approx(float64(in[i]), float64(out[i])) {
			t.Fatalf("element %d: %v != %v", i, in[i], out[i])
		}
	}
}

func TestParseVecTextEmpty(t *testing.T) {
	if v := parseVecText("[]"); v != nil {
		t.Fatalf("empty vector should parse to nil, got %v", v)
	}
	if v := parseVecText("[bad]"); v != nil {
		t.Fatalf("malformed vector should parse to nil, got %v", v)
	}
}

func TestCosine(t *testing.T) {
	a := vec384{1, 0, 0}
	if got := cosine(a, a); !approx(got, 1) {
		t.Fatalf("cosine(a,a) = %v, want 1", got)
	}
	b := vec384{0, 1, 0}
	if got := cosine(a, b); !approx(got, 0) {
		t.Fatalf("cosine(orthogonal) = %v, want 0", got)
	}
	neg := vec384{-1, 0, 0}
	if got := cosine(a, neg); got != 0 {
		t.Fatalf("cosine(opposite) clamped to 0, got %v", got)
	}
	if got := cosine(a, vec384{1, 0}); got != 0 {
		t.Fatalf("cosine(mismatched len) = %v, want 0", got)
	}
}

func TestJaccardSim(t *testing.T) {
	cases := []struct {
		a, b []string
		want float64
	}{
		{nil, []string{"x"}, 0},
		{[]string{"Quantum"}, []string{"quantum"}, 1},
		// {quantum,spin} vs {quantum computing,spin}: exact {spin}=1,
		// "quantum" ⊂ "quantum computing" → +0.5, union=3 → 1.5/3 = 0.5
		{[]string{"quantum", "spin"}, []string{"quantum computing", "spin"}, 0.5},
		{[]string{"physics"}, []string{"biology"}, 0},
	}
	for _, tc := range cases {
		if got := jaccardSim(tc.a, tc.b); !approx(got, tc.want) {
			t.Fatalf("jaccardSim(%v,%v) = %v, want %v", tc.a, tc.b, got, tc.want)
		}
	}
}

func TestOverlapFracAndCountShared(t *testing.T) {
	a := []string{"W1", "W2", "W3"}
	b := []string{"w2", "W3", "W9", "W10"}
	if n := countShared(a, b); n != 2 {
		t.Fatalf("countShared = %d, want 2", n)
	}
	// min(|a|,|b|) = 3 → 2/3
	if got := overlapFrac(a, b); !approx(got, 2.0/3.0) {
		t.Fatalf("overlapFrac = %v, want 0.667", got)
	}
	if got := overlapFrac(nil, b); got != 0 {
		t.Fatalf("overlapFrac(empty) = %v, want 0", got)
	}
}

func TestClampLimit(t *testing.T) {
	cases := []struct {
		in       string
		def, max int
		want     int
	}{
		{"", 10, 25, 10},
		{"0", 10, 25, 10},
		{"-3", 10, 25, 10},
		{"5", 10, 25, 5},
		{"999", 10, 25, 25},
		{"nope", 8, 25, 8},
	}
	for _, tc := range cases {
		if got := clampLimit(tc.in, tc.def, tc.max); got != tc.want {
			t.Fatalf("clampLimit(%q,%d,%d) = %d, want %d", tc.in, tc.def, tc.max, got, tc.want)
		}
	}
}

func TestCleanID(t *testing.T) {
	for in, want := range map[string]string{
		"https://openalex.org/W123": "W123",
		"W123":                      "W123",
		"https://openalex.org/A5/":  "A5",
		"":                          "",
	} {
		if got := cleanID(in); got != want {
			t.Fatalf("cleanID(%q) = %q, want %q", in, got, want)
		}
	}
}

func TestMMRSelectPrefersDiversity(t *testing.T) {
	// #0 and #1 are near-identical; #2 is orthogonal but slightly less
	// relevant. With λ=0.6 the second pick must be #2 (diverse), not #1.
	items := []mmrItem{
		{relevance: 0.95, vec: vec384{1, 0, 0}},
		{relevance: 0.94, vec: vec384{0.999, 0.001, 0}},
		{relevance: 0.80, vec: vec384{0, 1, 0}},
	}
	order := mmrSelect(items, 2)
	if len(order) != 2 || order[0] != 0 || order[1] != 2 {
		t.Fatalf("MMR order = %v, want [0 2]", order)
	}
}

func TestMMRSelectBounds(t *testing.T) {
	if mmrSelect(nil, 3) != nil {
		t.Fatal("nil items → nil")
	}
	items := []mmrItem{{relevance: 1, vec: vec384{1}}}
	if got := mmrSelect(items, 5); len(got) != 1 {
		t.Fatalf("k > n should clamp, got %d", len(got))
	}
}

func TestBlendResearcher(t *testing.T) {
	// Synthetic placeholders — the engine never hard-codes a field or
	// institution; these only exercise the set / case-fold math.
	me := authorRow{
		Vec:         vec384{1, 0, 0},
		Concepts:    []string{"Field Alpha"},
		Coauthors:   []string{"A1", "A2", "A3", "A4"},
		Institution: "Institution X",
	}
	// identical vector (dist 0 → cosine fallback = 1), 2 of 4 shared
	// collaborators, same concept, same institution.
	cand := authorRow{
		ID:          "A_cand",
		Vec:         vec384{1, 0, 0},
		Dist:        0,
		Concepts:    []string{"field alpha"},
		Coauthors:   []string{"A1", "A2", "X9"},
		Institution: "institution x",
	}
	s := blendResearcher(me, cand)
	if !approx(s.cosine, 1) {
		t.Fatalf("cosine = %v, want 1", s.cosine)
	}
	if s.shared != 2 {
		t.Fatalf("shared = %d, want 2", s.shared)
	}
	if !s.sameOrg {
		t.Fatal("sameOrg should be true (case-insensitive)")
	}
	// coauthor Jaccard: exact {a1,a2}=2, union {a1,a2,a3,a4,x9}=5 → 0.4
	if !approx(s.coJac, 0.4) {
		t.Fatalf("coJac = %v, want 0.4", s.coJac)
	}
	// score = 0.5*1 + 0.25*0.4 + 0.15*1 + 0.10*1 = 0.85
	if !approx(s.score, 0.85) {
		t.Fatalf("score = %v, want 0.85", s.score)
	}
}

func TestWhyResearcher(t *testing.T) {
	s := scoredResearcher{cosine: 0.79, shared: 3, sameOrg: true}
	got := whyResearcher(s, "Field Alpha")
	want := "same institution · 3 shared collaborators · 79% topical match"
	if got != want {
		t.Fatalf("whyResearcher = %q, want %q", got, want)
	}

	s2 := scoredResearcher{cosine: 0.6, shared: 1, sameOrg: false}
	got2 := whyResearcher(s2, "Field Alpha")
	want2 := "works in Field Alpha · 1 shared collaborator · 60% topical match"
	if got2 != want2 {
		t.Fatalf("whyResearcher = %q, want %q", got2, want2)
	}
}

func TestSortByScoreDesc(t *testing.T) {
	xs := []scoredResearcher{{score: 0.2}, {score: 0.9}, {score: 0.5}}
	sortByScoreDesc(xs)
	if xs[0].score != 0.9 || xs[1].score != 0.5 || xs[2].score != 0.2 {
		t.Fatalf("not sorted desc: %+v", xs)
	}
}
