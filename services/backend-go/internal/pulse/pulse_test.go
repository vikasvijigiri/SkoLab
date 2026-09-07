package pulse

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/gin-gonic/gin"
)

const rssSample = `<?xml version="1.0"?>
<rss version="2.0"><channel>
  <item>
    <title>A new phase of quantum matter</title>
    <link>https://example.org/a</link>
    <description>&lt;p&gt;Physicists report a &lt;b&gt;surprising&lt;/b&gt; result in condensed matter.&lt;/p&gt;</description>
    <pubDate>Mon, 01 Sep 2026 12:00:00 +0000</pubDate>
  </item>
  <item>
    <title>Unrelated economics headline</title>
    <link>https://example.org/b</link>
    <description>Markets moved today.</description>
    <pubDate>Tue, 02 Sep 2026 09:00:00 +0000</pubDate>
  </item>
</channel></rss>`

const atomSample = `<?xml version="1.0" encoding="utf-8"?>
<feed xmlns="http://www.w3.org/2005/Atom">
  <entry>
    <title>Telescope spots distant galaxy</title>
    <link rel="alternate" href="https://example.org/c"/>
    <summary>Astronomers describe the observation.</summary>
    <published>2026-09-03T00:00:00Z</published>
  </entry>
</feed>`

func TestParseFeedRSS(t *testing.T) {
	items := parseFeed([]byte(rssSample), "Test")
	if len(items) != 2 {
		t.Fatalf("want 2 items, got %d", len(items))
	}
	if items[0].Title != "A new phase of quantum matter" {
		t.Errorf("title = %q", items[0].Title)
	}
	if strings.Contains(items[0].Summary, "<") {
		t.Errorf("summary still has markup: %q", items[0].Summary)
	}
	if items[0].Published != "2026-09-01T12:00:00Z" {
		t.Errorf("published = %q, want RFC3339 UTC", items[0].Published)
	}
	if items[0].Source != "Test" {
		t.Errorf("source = %q", items[0].Source)
	}
}

const rdfSample = `<?xml version="1.0"?>
<rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns="http://purl.org/rss/1.0/">
  <channel rdf:about="http://example.org/"><title>Nature-like</title></channel>
  <item rdf:about="https://example.org/d">
    <title>Weekly research roundup</title>
    <link>https://example.org/d</link>
    <description>Highlights from this week.</description>
    <dc:date>2026-09-04T00:00:00Z</dc:date>
  </item>
</rdf:RDF>`

func TestParseFeedRDF(t *testing.T) {
	items := parseFeed([]byte(rdfSample), "Nature-like")
	if len(items) != 1 {
		t.Fatalf("RSS 1.0/RDF: want 1 item, got %d", len(items))
	}
	if items[0].URL != "https://example.org/d" || items[0].Published != "2026-09-04T00:00:00Z" {
		t.Fatalf("RDF item parsed wrong: %+v", items[0])
	}
}

func TestParseFeedAtom(t *testing.T) {
	items := parseFeed([]byte(atomSample), "Atomic")
	if len(items) != 1 || items[0].URL != "https://example.org/c" {
		t.Fatalf("atom parse failed: %+v", items)
	}
	if items[0].Published != "2026-09-03T00:00:00Z" {
		t.Errorf("published = %q", items[0].Published)
	}
}

func TestParseFeedGarbageIsEmpty(t *testing.T) {
	if got := parseFeed([]byte("not xml at all"), "x"); len(got) != 0 {
		t.Fatalf("garbage should yield no items, got %d", len(got))
	}
}

func TestFieldTokens(t *testing.T) {
	got := fieldTokens("Condensed Matter Physics and the")
	// "and"/"the"/<4 chars dropped.
	want := map[string]bool{"condensed": true, "matter": true, "physics": true}
	if len(got) != 3 {
		t.Fatalf("tokens = %v", got)
	}
	for _, g := range got {
		if !want[g] {
			t.Errorf("unexpected token %q", g)
		}
	}
}

func TestMatchesField(t *testing.T) {
	it := NewsItem{Title: "A new phase of quantum matter", Summary: "condensed matter result"}
	if !matchesField(it, []string{"condensed", "matter"}) {
		t.Error("should match on 'matter'")
	}
	if matchesField(it, []string{"economics"}) {
		t.Error("should not match 'economics'")
	}
	if !matchesField(it, nil) {
		t.Error("nil tokens must keep the item")
	}
}

func TestClampLimit(t *testing.T) {
	if clampLimit("", 8, 20) != 8 || clampLimit("99", 8, 20) != 20 || clampLimit("5", 8, 20) != 5 {
		t.Fatal("clampLimit bounds wrong")
	}
}

// Handler serves a JSON {items:[...]} shape even with no network (feeds fail,
// cache seeded empty).
func TestGetScienceNewsShape(t *testing.T) {
	store("all", []NewsItem{{Title: "seeded", URL: "https://x/1", Source: "S", Published: "2026-09-01T00:00:00Z"}})
	gin.SetMode(gin.TestMode)
	r := gin.New()
	r.GET("/science_news", GetScienceNews)

	req := httptest.NewRequest(http.MethodGet, "/science_news?limit=3", nil)
	w := httptest.NewRecorder()
	r.ServeHTTP(w, req)

	if w.Code != http.StatusOK {
		t.Fatalf("status %d", w.Code)
	}
	var body struct {
		Items []NewsItem `json:"items"`
	}
	if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
		t.Fatalf("bad JSON: %v", err)
	}
	if len(body.Items) != 1 || body.Items[0].Title != "seeded" {
		t.Fatalf("items = %+v", body.Items)
	}
}
