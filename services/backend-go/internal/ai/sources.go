package ai

import (
	"bytes"
	"context"
	"encoding/json"
	"encoding/xml"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"time"
)

// The sources the originality check searches, each a free service. What
// each can see differs, and reports say which were searched:
//
//	openalex          250M+ works: journals, conferences, books; titles and abstracts
//	semantic_scholar  passages from the full text of millions of open papers (arXiv, PubMed Central, ...)
//	core              300M+ open-access records from repositories, with their full texts
//	europe_pmc        life-science literature; exact phrases are found in full texts
//	arxiv             preprints in physics, mathematics, computer science, ...; abstracts
//	web               the open web (Tavily), pages' own text
//
// No free service reaches the full text of subscription journals; neither
// does any check that is not a publisher consortium's own (Crossref
// Similarity Check). Reports say so.

// Databases group sources for the report's breakdown.
const (
	DatabasePublications = "publications"
	DatabaseFullText     = "full_text"
	DatabasePreprints    = "preprints"
	DatabaseWeb          = "web"
)

const (
	sourceTimeout   = 15 * time.Second
	sourceUserAgent = "SkoLab originality check (+https://skolab-web.onrender.com)"
	// Full texts and web pages are long; enough of each is compared.
	maxSourceText = 300_000
)

// Provider is one searchable source.
type Provider struct {
	Name     string
	Database string
	Search   Searcher
	// Daily is the shared daily budget of searches (all checks together);
	// PerCheck the most searches one check may make (0: one per probe).
	Daily    int
	PerCheck int
	// Pace is the least time between two calls, across all checks, where a
	// service's terms or rate limit ask for it.
	Pace time.Duration
}

// Sources is the configured providers, in report order. A provider whose
// key is not set has no Search and is reported as not configured.
func Sources(cfg Config, client *http.Client) []Provider {
	providers := []Provider{
		{Name: "openalex", Database: DatabasePublications, Daily: cfg.SearchDaily},
		{Name: "semantic_scholar", Database: DatabaseFullText, Daily: cfg.SemanticScholarDaily, Pace: 1100 * time.Millisecond},
		{Name: "core", Database: DatabaseFullText, Daily: cfg.COREDaily, PerCheck: 10, Pace: 2 * time.Second},
		{Name: "europe_pmc", Database: DatabaseFullText, Daily: cfg.EuropePMCDaily, Pace: 200 * time.Millisecond},
		{Name: "arxiv", Database: DatabasePreprints, Daily: cfg.ArXivDaily, PerCheck: 6, Pace: 3 * time.Second},
		{Name: "web", Database: DatabaseWeb, Daily: cfg.WebDaily, PerCheck: cfg.WebPerCheck},
	}
	if cfg.OpenAlexKey != "" {
		providers[0].Search = OpenAlex{Key: cfg.OpenAlexKey, Client: client}.Search
	}
	if cfg.SemanticScholarKey != "" {
		providers[1].Search = SemanticScholar{Key: cfg.SemanticScholarKey, Client: client}.Search
	}
	if cfg.COREKey != "" {
		providers[2].Search = CORE{Key: cfg.COREKey, Client: client}.Search
	}
	if cfg.EuropePMC {
		providers[3].Search = EuropePMC{Client: client}.Search
	}
	if cfg.ArXiv {
		providers[4].Search = ArXiv{Client: client}.Search
	}
	if cfg.TavilyKey != "" {
		providers[5].Search = Tavily{Key: cfg.TavilyKey, Client: client}.Search
	}
	return providers
}

func getJSON(ctx context.Context, client *http.Client, method, link string, body any, headers map[string]string, out any) error {
	return getJSONWithTimeout(ctx, client, method, link, body, headers, out, sourceTimeout)
}

func getJSONWithTimeout(ctx context.Context, client *http.Client, method, link string, body any, headers map[string]string, out any, timeout time.Duration) error {
	ctx, cancel := context.WithTimeout(ctx, timeout)
	defer cancel()
	var reader io.Reader
	if body != nil {
		raw, err := json.Marshal(body)
		if err != nil {
			return err
		}
		reader = bytes.NewReader(raw)
	}
	req, err := http.NewRequestWithContext(ctx, method, link, reader)
	if err != nil {
		return err
	}
	req.Header.Set("Accept", "application/json")
	req.Header.Set("User-Agent", sourceUserAgent)
	if body != nil {
		req.Header.Set("Content-Type", "application/json")
	}
	for k, v := range headers {
		req.Header.Set(k, v)
	}
	if client == nil {
		client = http.DefaultClient
	}
	resp, err := client.Do(req)
	if err != nil {
		return errors.New("request failed") // never the URL: it may carry a key
	}
	defer resp.Body.Close()
	if resp.StatusCode == http.StatusTooManyRequests {
		return ErrBudget
	}
	if resp.StatusCode != http.StatusOK {
		return fmt.Errorf("status %d", resp.StatusCode)
	}
	if out == nil {
		return nil
	}
	if err := json.NewDecoder(io.LimitReader(resp.Body, 16<<20)).Decode(out); err != nil {
		return errors.New("unreadable answer")
	}
	return nil
}

func clip(text string) string {
	if len(text) > maxSourceText {
		return text[:maxSourceText]
	}
	return text
}

// quoted is the query as an exact phrase.
func quoted(query string) string { return `"` + strings.ReplaceAll(query, `"`, "") + `"` }

// ── Semantic Scholar: full-text passages ─────────────────────────────────

// SemanticScholar searches passages of papers' full texts (snippet search).
// A free key is needed in practice: the keyless pool is shared by everyone.
type SemanticScholar struct {
	BaseURL string
	Key     string
	Client  *http.Client
}

func (s SemanticScholar) Search(ctx context.Context, query string) ([]Work, error) {
	base := s.BaseURL
	if base == "" {
		base = "https://api.semanticscholar.org"
	}
	headers := map[string]string{}
	if s.Key != "" {
		headers["x-api-key"] = s.Key
	}
	var body struct {
		Data []struct {
			Snippet struct {
				Text string `json:"text"`
			} `json:"snippet"`
			Paper struct {
				CorpusID json.Number `json:"corpusId"`
				Title    string      `json:"title"`
			} `json:"paper"`
		} `json:"data"`
	}
	link := base + "/graph/v1/snippet/search?" + url.Values{"query": {query}, "limit": {"5"}}.Encode()
	if err := getJSON(ctx, s.Client, http.MethodGet, link, nil, headers, &body); err != nil {
		return nil, err
	}
	works := make([]Work, 0, len(body.Data))
	for _, d := range body.Data {
		id := d.Paper.CorpusID.String()
		if id == "" {
			continue
		}
		works = append(works, Work{
			ID:       "s2:" + id,
			Title:    d.Paper.Title,
			URL:      "https://www.semanticscholar.org/p/" + id,
			Abstract: d.Snippet.Text,
			Database: DatabaseFullText,
		})
	}
	return works, nil
}

// ── CORE: open-access full texts ─────────────────────────────────────────

// CORE (core.ac.uk) searches open-access research outputs' full texts for
// the exact phrase and answers each work's full text. A free key is needed.
type CORE struct {
	BaseURL string
	Key     string
	Client  *http.Client
}

func (c CORE) Search(ctx context.Context, query string) ([]Work, error) {
	base := c.BaseURL
	if base == "" {
		base = "https://api.core.ac.uk"
	}
	var body struct {
		Results []struct {
			ID            json.Number `json:"id"`
			DOI           string      `json:"doi"`
			Title         string      `json:"title"`
			YearPublished int         `json:"yearPublished"`
			Abstract      string      `json:"abstract"`
			FullText      string      `json:"fullText"`
			DownloadURL   string      `json:"downloadUrl"`
		} `json:"results"`
	}
	link := base + "/v3/search/works?" + url.Values{"q": {"fullText:" + quoted(query)}, "limit": {"3"}}.Encode()
	if err := getJSON(ctx, c.Client, http.MethodGet, link, nil, map[string]string{"Authorization": "Bearer " + c.Key}, &body); err != nil {
		return nil, err
	}
	works := make([]Work, 0, len(body.Results))
	for _, r := range body.Results {
		id := r.ID.String()
		link := "https://core.ac.uk/works/" + id
		if r.DOI != "" {
			link = "https://doi.org/" + r.DOI
		}
		text := r.FullText
		if text == "" {
			text = r.Abstract
		}
		works = append(works, Work{
			ID: "core:" + id, DOI: r.DOI, Title: r.Title, Year: r.YearPublished, URL: link,
			Abstract: clip(text), Database: DatabaseFullText, Phrase: query,
		})
	}
	return works, nil
}

// ── Europe PMC: life sciences, phrase found in full text ─────────────────

// EuropePMC searches abstracts and open-access full texts for the exact
// phrase. It answers abstracts only, so a hit is also evidence by itself:
// the phrase is in that work.
type EuropePMC struct {
	BaseURL string
	Client  *http.Client
}

func (e EuropePMC) Search(ctx context.Context, query string) ([]Work, error) {
	base := e.BaseURL
	if base == "" {
		base = "https://www.ebi.ac.uk/europepmc/webservices/rest"
	}
	var body struct {
		ResultList struct {
			Result []struct {
				ID           string `json:"id"`
				Source       string `json:"source"`
				DOI          string `json:"doi"`
				Title        string `json:"title"`
				PubYear      string `json:"pubYear"`
				AbstractText string `json:"abstractText"`
			} `json:"result"`
		} `json:"resultList"`
	}
	link := base + "/search?" + url.Values{"query": {quoted(query)}, "resultType": {"core"}, "format": {"json"}, "pageSize": {"5"}}.Encode()
	if err := getJSON(ctx, e.Client, http.MethodGet, link, nil, nil, &body); err != nil {
		return nil, err
	}
	works := make([]Work, 0, len(body.ResultList.Result))
	for _, r := range body.ResultList.Result {
		year, _ := strconv.Atoi(r.PubYear)
		link := "https://europepmc.org/article/" + r.Source + "/" + r.ID
		if r.DOI != "" {
			link = "https://doi.org/" + r.DOI
		}
		works = append(works, Work{
			ID: "epmc:" + r.Source + ":" + r.ID, DOI: r.DOI, Title: r.Title, Year: year, URL: link,
			Abstract: stripTags(r.AbstractText), Database: DatabaseFullText, Phrase: query,
		})
	}
	return works, nil
}

// ── arXiv: preprints ─────────────────────────────────────────────────────

// ArXiv searches preprints' titles and abstracts for the exact phrase. Its
// terms ask for one request every three seconds (Provider.Pace).
type ArXiv struct {
	BaseURL string
	Client  *http.Client
}

func (a ArXiv) Search(ctx context.Context, query string) ([]Work, error) {
	base := a.BaseURL
	if base == "" {
		base = "https://export.arxiv.org"
	}
	ctx, cancel := context.WithTimeout(ctx, 25*time.Second) // arXiv is slow
	defer cancel()
	link := base + "/api/query?" + url.Values{"search_query": {"all:" + quoted(query)}, "max_results": {"5"}}.Encode()
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, link, nil)
	if err != nil {
		return nil, err
	}
	req.Header.Set("User-Agent", sourceUserAgent)
	client := a.Client
	if client == nil {
		client = http.DefaultClient
	}
	resp, err := client.Do(req)
	if err != nil {
		return nil, errors.New("request failed")
	}
	defer resp.Body.Close()
	if resp.StatusCode == http.StatusTooManyRequests || resp.StatusCode == http.StatusServiceUnavailable {
		return nil, ErrBudget
	}
	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("status %d", resp.StatusCode)
	}
	var feed struct {
		Entries []struct {
			ID        string `xml:"id"`
			Title     string `xml:"title"`
			Summary   string `xml:"summary"`
			Published string `xml:"published"`
			DOI       string `xml:"doi"`
		} `xml:"entry"`
	}
	if err := xml.NewDecoder(io.LimitReader(resp.Body, 4<<20)).Decode(&feed); err != nil {
		return nil, errors.New("unreadable answer")
	}
	works := make([]Work, 0, len(feed.Entries))
	for _, e := range feed.Entries {
		if e.ID == "" {
			continue
		}
		year := 0
		if len(e.Published) >= 4 {
			year, _ = strconv.Atoi(e.Published[:4])
		}
		works = append(works, Work{
			ID: "arxiv:" + strings.TrimPrefix(e.ID, "http://arxiv.org/abs/"), DOI: e.DOI,
			Title: strings.Join(strings.Fields(e.Title), " "), Year: year, URL: strings.Replace(e.ID, "http://", "https://", 1),
			Abstract: strings.Join(strings.Fields(e.Summary), " "), Database: DatabasePreprints, Phrase: query,
		})
	}
	return works, nil
}

// ── The web ──────────────────────────────────────────────────────────────

// Tavily searches the web for the exact phrase and answers the part of each
// page that matches it (asking for whole pages takes ~30s a search). The
// free plan is 1,000 searches a month, so few are spent a check.
type Tavily struct {
	BaseURL string
	Key     string
	Client  *http.Client
}

func (t Tavily) Search(ctx context.Context, query string) ([]Work, error) {
	base := t.BaseURL
	if base == "" {
		base = "https://api.tavily.com"
	}
	var body struct {
		Results []struct {
			URL        string `json:"url"`
			Title      string `json:"title"`
			Content    string `json:"content"`
			RawContent string `json:"raw_content"`
		} `json:"results"`
	}
	request := map[string]any{"query": quoted(query), "max_results": 5, "search_depth": "basic", "include_raw_content": false}
	if err := getJSON(ctx, t.Client, http.MethodPost, base+"/search", request, map[string]string{"Authorization": "Bearer " + t.Key}, &body); err != nil {
		return nil, err
	}
	works := make([]Work, 0, len(body.Results))
	for _, r := range body.Results {
		if r.URL == "" {
			continue
		}
		text := r.RawContent
		if text == "" {
			text = r.Content
		}
		works = append(works, Work{ID: "web:" + r.URL, Title: r.Title, URL: r.URL, Abstract: clip(text), Database: DatabaseWeb})
	}
	return works, nil
}

func stripTags(s string) string {
	var b strings.Builder
	in := false
	for _, r := range s {
		switch {
		case r == '<':
			in = true
		case r == '>':
			in = false
			b.WriteByte(' ')
		case !in:
			b.WriteRune(r)
		}
	}
	return strings.Join(strings.Fields(b.String()), " ")
}
