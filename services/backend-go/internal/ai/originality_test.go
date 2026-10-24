package ai

import (
	"context"
	"errors"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/skolab/backend-go/internal/quota"
)

const copied = "Spin waves in ferromagnetic thin films propagate with a dispersion that depends strongly on the film thickness and the applied magnetic field"

func TestProseKeepsTheBodyAndDropsMarkup(t *testing.T) {
	src := `\documentclass{article}\usepackage{amsmath}\title{Ignored preamble}
\begin{document}
\section{Introduction}
Spin waves~\cite{k} are \emph{fascinating} (see Fig.~\ref{f}). % private note
\begin{equation} E = \hbar \omega \end{equation}
They carry $k$-dependent energy.
\begin{thebibliography}{9}\bibitem{k} Kittel, Introduction.\end{thebibliography}
\end{document}`
	got := Prose(src)
	want := "Introduction Spin waves are fascinating (see Fig. ). They carry -dependent energy."
	if got != want {
		t.Fatalf("got  %q\nwant %q", got, want)
	}
}

func TestSplitFindsSentencesButNotDecimalsOrAbbreviations(t *testing.T) {
	got := split("The value is 3.5 percent. See e.g. the appendix. Done! Next? yes 4 items. Last one")
	var texts []string
	for _, s := range got {
		texts = append(texts, s.text)
	}
	want := []string{"The value is 3.5 percent.", "See e.g. the appendix.", "Done!", "Next? yes 4 items.", "Last one"}
	if strings.Join(texts, "|") != strings.Join(want, "|") {
		t.Fatalf("%q", texts)
	}
}

func TestTokensAndShingles(t *testing.T) {
	if got := tokens("Spin-Waves, ÉTÉ 2026!"); strings.Join(got, " ") != "spin waves été 2026" {
		t.Fatalf("%q", got)
	}
	s := shingles([]string{"a", "b", "c", "d", "e", "f", "a", "b", "c", "d", "e"})
	if len(s) != 6 || s["a b c d e"] != 0 {
		t.Fatalf("%v", s)
	}
	if len(shingles([]string{"one", "two"})) != 1 || len(shingles(nil)) != 0 {
		t.Fatal("short shingles")
	}
}

func TestProbesSpreadOverTheDocumentAndPreferDistinctiveWords(t *testing.T) {
	var sentences []sentence
	for i := 0; i < 30; i++ {
		text := strings.Repeat("plain word ", 7)
		if i%10 == 3 {
			text = strings.Repeat("extraordinarily distinctive terminology ", 5)
		}
		sentences = append(sentences, sentence{text, tokens(text)})
	}
	sentences = append(sentences, sentence{"too short", tokens("too short")})
	got := probes(sentences, 3)
	if len(got) != 3 {
		t.Fatalf("%d probes", len(got))
	}
	for _, p := range got {
		if !strings.Contains(p.text, "distinctive") {
			t.Fatalf("picked %q", p.text)
		}
	}
	if len(probes(sentences[:2], 5)) != 2 || probes(sentences, 0) != nil {
		t.Fatal("small inputs")
	}
	if q := query(tokens(copied)); len(strings.Fields(q)) != probeWords {
		t.Fatalf("query %q", q)
	}
}

func texDocument(paragraphs ...string) string {
	return `\begin{document}` + strings.Join(paragraphs, "\n\n") + `\end{document}`
}

func TestCheckFindsCopiedSentencesAndCreditsTheSource(t *testing.T) {
	original := "Our own measurements were collected at the national laboratory over three consecutive winters using a novel setup."
	latex := texDocument(copied+" in our experiments.", original, "A further sentence that is entirely our own wording and appears in no published abstract anywhere.")
	var queries []string
	search := func(_ context.Context, q string) ([]Work, error) {
		queries = append(queries, q)
		return []Work{
			{ID: "W1", Title: "Magnon dispersion", Year: 2019, URL: "https://doi.org/10.1/x", DOI: "10.1/x", Abstract: "We show that " + copied + " in yttrium iron garnet."},
			{ID: "W2", Title: "Unrelated", Abstract: "Nothing in common with the manuscript at all."},
		}, nil
	}
	result, err := Check(context.Background(), latex, search, 5)
	if err != nil {
		t.Fatal(err)
	}
	if result.Probes != len(queries) || result.Probes == 0 || result.SourcesChecked != 2 || result.Partial {
		t.Fatalf("%+v", result)
	}
	if len(result.Matches) != 1 || result.Matches[0].SourceID != "W1" || result.Matches[0].Overlap < 0.9 || !strings.Contains(result.Matches[0].Excerpt, "spin waves") {
		t.Fatalf("matches %+v", result.Matches)
	}
	if len(result.Sources) != 1 || result.Sources[0].Sentences != 1 || result.Sources[0].Year != 2019 || result.Sources[0].DOI != "10.1/x" {
		t.Fatalf("sources %+v", result.Sources)
	}
	if result.Score <= 20 || result.Score >= 60 || result.MatchedWords >= result.Words {
		t.Fatalf("score %v (%d of %d words)", result.Score, result.MatchedWords, result.Words)
	}
}

func TestCheckOfOriginalWorkScoresZero(t *testing.T) {
	latex := texDocument(strings.Repeat("Entirely original prose about a brand new phenomenon observed in our laboratory today. ", 5))
	result, err := Check(context.Background(), latex, func(context.Context, string) ([]Work, error) {
		return []Work{{ID: "W9", Title: "Other", Abstract: "Different words describing something else completely and unrelated."}}, nil
	}, 3)
	if err != nil || result.Score != 0 || len(result.Matches) != 0 || len(result.Sources) != 0 {
		t.Fatalf("%+v %v", result, err)
	}
}

func TestCheckRefusesTooLittleProse(t *testing.T) {
	if _, err := Check(context.Background(), texDocument("Short text, $x=1$."), nil, 3); !errors.Is(err, ErrTooShort) {
		t.Fatal(err)
	}
}

func TestCheckStopsWhenTheBudgetRunsOutAndKeepsWhatItFound(t *testing.T) {
	latex := texDocument(strings.Repeat(copied+". ", 4), strings.Repeat("Original long sentence with many different words to search for here today. ", 4))
	calls := 0
	result, err := Check(context.Background(), latex, func(context.Context, string) ([]Work, error) {
		calls++
		if calls > 1 {
			return nil, ErrBudget
		}
		return []Work{{ID: "W1", Abstract: copied}}, nil
	}, 5)
	if err != nil || !result.Partial || result.Probes != 1 || len(result.Matches) == 0 {
		t.Fatalf("%+v %v", result, err)
	}
}

func TestCheckFailsOnASearchErrorOrCancellation(t *testing.T) {
	latex := texDocument(strings.Repeat(copied+". ", 3))
	if _, err := Check(context.Background(), latex, func(context.Context, string) ([]Work, error) { return nil, errors.New("down") }, 3); err == nil {
		t.Fatal("a search failure must fail the check")
	}
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	if _, err := Check(ctx, latex, func(context.Context, string) ([]Work, error) { return nil, nil }, 3); !errors.Is(err, context.Canceled) {
		t.Fatal(err)
	}
}

func TestOpenAlexSearchRebuildsAbstractsAndKeepsTheKeyPrivate(t *testing.T) {
	var got url.Values
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		got = r.URL.Query()
		if r.URL.Path != "/works" {
			http.NotFound(w, r)
			return
		}
		_, _ = w.Write([]byte(`{"results":[
			{"id":"https://openalex.org/W1","doi":"https://doi.org/10.1/x","title":"T","publication_year":2020,
			 "abstract_inverted_index":{"waves":[1],"Spin":[0],"propagate":[2]}},
			{"id":"https://openalex.org/W2","doi":null,"title":"U","primary_location":{"landing_page_url":"https://arxiv.org/abs/1"}},
			{"id":"https://openalex.org/W3","title":"V"}]}`))
	}))
	defer server.Close()
	works, err := OpenAlex{BaseURL: server.URL, Key: "secret-key", Client: server.Client()}.Search(context.Background(), "spin waves")
	if err != nil {
		t.Fatal(err)
	}
	if got.Get("search") != "spin waves" || got.Get("api_key") != "secret-key" || got.Get("per_page") != "5" {
		t.Fatalf("%v", got)
	}
	if len(works) != 3 || works[0].Abstract != "Spin waves propagate" || works[0].DOI != "10.1/x" || works[0].URL != "https://doi.org/10.1/x" || works[0].Year != 2020 {
		t.Fatalf("%+v", works[0])
	}
	if works[1].URL != "https://arxiv.org/abs/1" || works[2].URL != "https://openalex.org/W3" {
		t.Fatalf("%+v", works[1:])
	}
}

func TestOpenAlexFailures(t *testing.T) {
	for status, want := range map[int]error{http.StatusTooManyRequests: ErrBudget, http.StatusInternalServerError: nil} {
		server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) { w.WriteHeader(status) }))
		_, err := OpenAlex{BaseURL: server.URL, Key: "secret-key"}.Search(context.Background(), "q")
		server.Close()
		if err == nil || (want != nil && !errors.Is(err, want)) || strings.Contains(err.Error(), "secret-key") {
			t.Fatalf("%d: %v", status, err)
		}
	}
	garbled := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) { _, _ = w.Write([]byte("<html>")) }))
	defer garbled.Close()
	if _, err := (OpenAlex{BaseURL: garbled.URL, Key: "secret-key"}).Search(context.Background(), "q"); err == nil || strings.Contains(err.Error(), "secret-key") {
		t.Fatal(err)
	}
	if _, err := (OpenAlex{BaseURL: "http://127.0.0.1:1", Key: "secret-key"}).Search(context.Background(), "q"); err == nil || strings.Contains(err.Error(), "secret-key") {
		t.Fatal(err)
	}
}

func TestAbstractIgnoresAbsurdPositions(t *testing.T) {
	if got := abstract(map[string][]int{"b": {1}, "a": {0}, "x": {-1, 99999}}); got != "a b" {
		t.Fatalf("%q", got)
	}
}

// ── Budgets ────────────────────────────────────────────────────────────────

type memBudgets struct {
	mu   sync.Mutex
	used map[string]int
	down bool
}

func newBudgets() *memBudgets { return &memBudgets{used: map[string]int{}} }

func (b *memBudgets) Charge(_ context.Context, key string, cost, limit int) (int, func(), error) {
	b.mu.Lock()
	defer b.mu.Unlock()
	if b.down {
		return 0, func() {}, errors.New("ledger down")
	}
	if b.used[key]+cost > limit {
		return 0, func() {}, exceeded(limit)
	}
	b.used[key] += cost
	return limit - b.used[key], func() {
		b.mu.Lock()
		defer b.mu.Unlock()
		b.used[key] -= cost
	}, nil
}

func (b *memBudgets) Left(_ context.Context, key string, limit int) int {
	b.mu.Lock()
	defer b.mu.Unlock()
	return max(limit-b.used[key], 0)
}

func TestBudgetedSearchChargesEachSearchAndStopsAtTheLimit(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Query().Get("search") == "fail" {
			w.WriteHeader(http.StatusBadGateway)
			return
		}
		_, _ = w.Write([]byte(`{"results":[]}`))
	}))
	defer server.Close()
	b := newBudgets()
	search := BudgetedSearch(b, 2, OpenAlex{BaseURL: server.URL})
	if _, err := search(context.Background(), "a"); err != nil {
		t.Fatal(err)
	}
	if _, err := search(context.Background(), "fail"); err == nil || b.used[searchKey] != 1 {
		t.Fatalf("a failed search is refunded: %v %d", err, b.used[searchKey])
	}
	if _, err := search(context.Background(), "b"); err != nil {
		t.Fatal(err)
	}
	if _, err := search(context.Background(), "c"); !errors.Is(err, ErrBudget) {
		t.Fatalf("over the limit: %v", err)
	}
	b.down = true
	if _, err := search(context.Background(), "d"); err == nil || errors.Is(err, ErrBudget) {
		t.Fatalf("ledger down: %v", err)
	}
}

func exceeded(limit int) error {
	return &quota.Exceeded{Window: "daily", Limit: limit, RetryAfter: time.Hour}
}

func TestCheckCatchesLightlyEditedTextButNotTheSameTopic(t *testing.T) {
	source := "Proteins are essential to life, and understanding their structure can facilitate a mechanistic understanding of their function. Structural coverage is bottlenecked by the months to years of painstaking effort required to determine a single protein structure."
	edited := "Proteins are vital to life, and knowing their structure can facilitate a mechanistic understanding of how they function. Structural coverage is limited by the months to years of painstaking work needed to determine one protein structure."
	sameField := "We trained a compact graph network on twenty thousand experimentally solved protein structures from our own crystallography pipeline. Its predicted residue contacts agreed with the deposited coordinates for most small soluble domains we tested."
	search := func(context.Context, string) ([]Work, error) {
		return []Work{{ID: "W1", Title: "Highly accurate protein structure prediction", Abstract: source}}, nil
	}
	got, err := Check(context.Background(), texDocument(edited), search, 3)
	if err != nil || len(got.Matches) != 2 || got.Score != 100 {
		t.Fatalf("edited: %+v %v", got, err)
	}
	for _, m := range got.Matches {
		if m.Kind != "edited" || m.Overlap < 0.7 || m.SourceID != "W1" {
			t.Fatalf("%+v", m)
		}
	}
	if got, err := Check(context.Background(), texDocument(sameField), search, 3); err != nil || len(got.Matches) != 0 {
		t.Fatalf("same topic, own words: %+v %v", got, err)
	}
	if got, _ := Check(context.Background(), texDocument(source), search, 3); len(got.Matches) != 2 || got.Matches[0].Kind != "copied" {
		t.Fatalf("copied: %+v", got.Matches)
	}
}

func TestContentWordsSkipShortAndCommonWords(t *testing.T) {
	got := contentWords(tokens("We show that the protein structures were very stable, with them."))
	if len(got) != 3 || !got["protein"] || !got["structures"] || !got["stable"] {
		t.Fatalf("%v", got)
	}
}

func TestFrontMatterIsNotProse(t *testing.T) {
	latex := `\begin{document}
\title{Classical ice rules, quantum lines}
\author{Vikas Vijigiri}
\email{vikasvikki@iopb.res.in}
\affiliation[IOP]{Institute of Physics, Bhubaneswar-751005, Odisha, India}
\author*{K.G. Paulson\thanks{Corresponding {\em author}}}
\date{\today}
\titlerunning{kept: not front matter}
\maketitle
Squaric acid is a layered hydrogen-bonded crystal.
\end{document}`
	got := Prose(latex)
	for _, gone := range []string{"Classical ice", "Vijigiri", "iopb", "Bhubaneswar", "Paulson", "Corresponding", "author"} {
		if strings.Contains(got, gone) {
			t.Errorf("%q is still in %q", gone, got)
		}
	}
	if !strings.Contains(got, "Squaric acid is a layered hydrogen-bonded crystal.") || !strings.Contains(got, "kept: not front matter") {
		t.Fatalf("prose lost: %q", got)
	}
	// A command alone, without an argument, is left as it is.
	if WithoutFrontMatter(`\title alone`) != `\title alone` {
		t.Fatal("changed a bare command")
	}
	// The windows the AI-writing estimate scores leave it out too.
	for _, w := range Paragraphs(latex + strings.Repeat(" More prose about the crystal and its protons.", 40)) {
		if strings.Contains(w, "Vijigiri") {
			t.Fatal("front matter in a window")
		}
	}
}
