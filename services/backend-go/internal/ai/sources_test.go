package ai

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

// serve answers every request with status and body, recording the request.
func serve(t *testing.T, status int, body string, seen *[]*http.Request) string {
	t.Helper()
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		copied := r.Clone(context.Background())
		if r.Body != nil {
			raw, _ := io.ReadAll(r.Body)
			copied.Header.Set("X-Test-Body", string(raw))
		}
		*seen = append(*seen, copied)
		w.WriteHeader(status)
		_, _ = io.WriteString(w, body)
	}))
	t.Cleanup(server.Close)
	return server.URL
}

const phrase = "dispersion of spin waves in thin magnetic films at room temperature"

func TestSemanticScholarReadsPassagesWithTheKey(t *testing.T) {
	var seen []*http.Request
	base := serve(t, 200, `{"data":[{"snippet":{"text":"We measured the dispersion of spin waves."},"paper":{"corpusId":12345,"title":"Spin waves"}},{"snippet":{"text":"no id"},"paper":{"title":"x"}}]}`, &seen)
	works, err := SemanticScholar{BaseURL: base, Key: "k2"}.Search(context.Background(), phrase)
	if err != nil || len(works) != 1 {
		t.Fatalf("%+v %v", works, err)
	}
	w := works[0]
	if w.ID != "s2:12345" || w.URL != "https://www.semanticscholar.org/p/12345" || w.Database != DatabaseFullText || !strings.Contains(w.Abstract, "dispersion") {
		t.Fatalf("%+v", w)
	}
	if seen[0].Header.Get("x-api-key") != "k2" || seen[0].URL.Path != "/graph/v1/snippet/search" || seen[0].URL.Query().Get("query") != phrase {
		t.Fatalf("%v %v", seen[0].Header, seen[0].URL)
	}
}

func TestCOREAsksForTheExactPhraseInFullTexts(t *testing.T) {
	var seen []*http.Request
	long := strings.Repeat("word ", maxSourceText)
	base := serve(t, 200, `{"results":[{"id":77,"doi":"10.1/x","title":"T","yearPublished":2019,"fullText":"`+long+`"},{"id":78,"title":"U","abstract":"only an abstract"}]}`, &seen)
	works, err := CORE{BaseURL: base, Key: "ck"}.Search(context.Background(), phrase)
	if err != nil || len(works) != 2 {
		t.Fatalf("%v %v", len(works), err)
	}
	if works[0].URL != "https://doi.org/10.1/x" || len(works[0].Abstract) != maxSourceText || works[0].Phrase != phrase || works[0].Year != 2019 {
		t.Fatalf("%+v", works[0].URL)
	}
	if works[1].URL != "https://core.ac.uk/works/78" || works[1].Abstract != "only an abstract" {
		t.Fatalf("%+v", works[1])
	}
	if seen[0].Header.Get("Authorization") != "Bearer ck" || seen[0].URL.Query().Get("q") != `fullText:"`+phrase+`"` {
		t.Fatalf("%v", seen[0].URL)
	}
}

func TestEuropePMCMarksPhraseHits(t *testing.T) {
	var seen []*http.Request
	base := serve(t, 200, `{"resultList":{"result":[{"id":"PMC1","source":"PMC","title":"Proteins","pubYear":"2015","abstractText":"<h4>Aim</h4>Folding <i>is</i> fast."},{"id":"2","source":"MED","doi":"10.2/y","title":"Y","pubYear":"x"}]}}`, &seen)
	works, err := EuropePMC{BaseURL: base}.Search(context.Background(), phrase)
	if err != nil || len(works) != 2 {
		t.Fatal(err)
	}
	if works[0].ID != "epmc:PMC:PMC1" || works[0].Abstract != "Aim Folding is fast." || works[0].Year != 2015 || works[0].URL != "https://europepmc.org/article/PMC/PMC1" || works[0].Phrase != phrase {
		t.Fatalf("%+v", works[0])
	}
	if works[1].URL != "https://doi.org/10.2/y" || works[1].Year != 0 {
		t.Fatalf("%+v", works[1])
	}
	if seen[0].URL.Query().Get("query") != `"`+phrase+`"` {
		t.Fatal(seen[0].URL)
	}
}

func TestArXivReadsTheAtomFeed(t *testing.T) {
	var seen []*http.Request
	feed := `<?xml version="1.0"?><feed xmlns="http://www.w3.org/2005/Atom"><entry><id>http://arxiv.org/abs/2101.00001v2</id><title>Spin
  waves</title><summary>  We measured
the dispersion. </summary><published>2021-01-01T00:00:00Z</published></entry><entry><title>no id</title></entry></feed>`
	base := serve(t, 200, feed, &seen)
	works, err := ArXiv{BaseURL: base}.Search(context.Background(), phrase)
	if err != nil || len(works) != 1 {
		t.Fatalf("%+v %v", works, err)
	}
	w := works[0]
	if w.ID != "arxiv:2101.00001v2" || w.Title != "Spin waves" || w.Abstract != "We measured the dispersion." || w.Year != 2021 || w.URL != "https://arxiv.org/abs/2101.00001v2" || w.Database != DatabasePreprints {
		t.Fatalf("%+v", w)
	}
	if !strings.HasPrefix(seen[0].URL.Query().Get("search_query"), `all:"`) {
		t.Fatal(seen[0].URL)
	}
}

func TestTavilyReadsPagesText(t *testing.T) {
	var seen []*http.Request
	base := serve(t, 200, `{"results":[{"url":"https://example.org/a","title":"A","content":"snippet","raw_content":"the whole page"},{"url":"https://example.org/b","title":"B","content":"just a snippet"},{"title":"no url"}]}`, &seen)
	works, err := Tavily{BaseURL: base, Key: "tk"}.Search(context.Background(), phrase)
	if err != nil || len(works) != 2 {
		t.Fatalf("%+v %v", works, err)
	}
	if works[0].ID != "web:https://example.org/a" || works[0].Abstract != "the whole page" || works[1].Abstract != "just a snippet" || works[0].Database != DatabaseWeb {
		t.Fatalf("%+v", works)
	}
	var sent map[string]any
	_ = json.Unmarshal([]byte(seen[0].Header.Get("X-Test-Body")), &sent)
	if seen[0].Method != http.MethodPost || seen[0].Header.Get("Authorization") != "Bearer tk" || sent["query"] != `"`+phrase+`"` || sent["include_raw_content"] != false {
		t.Fatalf("%v %v", seen[0].Header, sent)
	}
}

func TestSourcesReportRateLimitsAndFailuresWithoutTheirURL(t *testing.T) {
	var seen []*http.Request
	limited := serve(t, http.StatusTooManyRequests, `{}`, &seen)
	broken := serve(t, http.StatusInternalServerError, `oops`, &seen)
	garbled := serve(t, 200, `not json`, &seen)
	ctx := context.Background()
	if _, err := (SemanticScholar{BaseURL: limited}).Search(ctx, "q"); !errors.Is(err, ErrBudget) {
		t.Fatal(err)
	}
	if _, err := (ArXiv{BaseURL: limited}).Search(ctx, "q"); !errors.Is(err, ErrBudget) {
		t.Fatal(err)
	}
	for _, search := range []Searcher{CORE{BaseURL: broken, Key: "secret"}.Search, EuropePMC{BaseURL: garbled}.Search, Tavily{BaseURL: broken, Key: "secret"}.Search, ArXiv{BaseURL: garbled}.Search, ArXiv{BaseURL: broken}.Search} {
		_, err := search(ctx, "q")
		if err == nil || strings.Contains(err.Error(), "secret") || strings.Contains(err.Error(), "127.0.0.1") {
			t.Fatalf("%v", err)
		}
	}
	if _, err := (CORE{BaseURL: "http://127.0.0.1:1", Key: "secret"}).Search(ctx, "q"); err == nil || strings.Contains(err.Error(), "secret") {
		t.Fatal(err)
	}
}

func TestStripTags(t *testing.T) {
	if got := stripTags("<p>a <b>bold</b>\n move</p>"); got != "a bold move" {
		t.Fatal(got)
	}
}

func TestOpenAlexLabelsPreprints(t *testing.T) {
	var seen []*http.Request
	base := serve(t, 200, `{"results":[
		{"id":"W1","title":"On arXiv","primary_location":{"landing_page_url":"https://arxiv.org/abs/1","source":{"display_name":"arXiv (Cornell University)"}}},
		{"id":"W2","title":"In a journal","primary_location":{"source":{"display_name":"Physical Review B"}}},
		{"id":"W3","title":"Nowhere"},
		{"id":"W4","title":"bioRxiv","primary_location":{"source":{"display_name":"bioRxiv (Cold Spring Harbor Laboratory)"}}}]}`, &seen)
	works, err := OpenAlex{BaseURL: base}.Search(context.Background(), "q")
	if err != nil || len(works) != 4 {
		t.Fatal(err)
	}
	want := []string{DatabasePreprints, DatabasePublications, DatabasePublications, DatabasePreprints}
	for i, w := range works {
		if w.Database != want[i] {
			t.Errorf("%s: %s", w.Title, w.Database)
		}
	}
}
