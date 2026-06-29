// Package openalex provides a Go HTTP client for the OpenAlex scholarly API.
// It mirrors the Python OpenAlexService: circuit-breaking, polite crawling headers,
// and the same endpoint coverage (authors, works, funders).
package openalex

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"net/url"
	"os"
	"strings"
	"time"

	"github.com/skolab/backend-go/internal/circuitbreaker"
)

const baseURL = "https://api.openalex.org"

// ── Domain types mirroring OpenAlex JSON ─────────────────────────────────────

type FieldObj struct {
	ID          string `json:"id"`
	DisplayName string `json:"display_name"`
}

type Topic struct {
	DisplayName string   `json:"display_name"`
	Field       FieldObj `json:"field"`
	Subfield    FieldObj `json:"subfield"`
	Domain      FieldObj `json:"domain"`
}

type Concept struct {
	DisplayName string  `json:"display_name"`
	Level       int     `json:"level"`
	Score       float64 `json:"score"`
}

type Institution struct {
	DisplayName string `json:"display_name"`
	ID          string `json:"id"`
}

type SummaryStats struct {
	HIndex   int `json:"h_index"`
	I10Index int `json:"i10_index"`
}

type Author struct {
	ID                    string        `json:"id"`
	DisplayName           string        `json:"display_name"`
	ORCID                 string        `json:"orcid"`
	WorksCount            int           `json:"works_count"`
	CitedByCount          int           `json:"cited_by_count"`
	SummaryStats          SummaryStats  `json:"summary_stats"`
	LastKnownInstitutions []Institution `json:"last_known_institutions"`
	Topics                []Topic       `json:"topics"`
	XConcepts             []Concept     `json:"x_concepts"`
	Affiliations          []Affiliation `json:"affiliations"`
}

type Affiliation struct {
	Institution Institution `json:"institution"`
	Years       []int       `json:"years"`
}

type Source struct {
	DisplayName        string  `json:"display_name"`
	TwoYrMeanCitedness float64 `json:"2yr_mean_citedness"`
}

type PrimaryLocation struct {
	Source Source `json:"source"`
}

type OpenAccess struct {
	IsOA bool `json:"is_oa"`
}

type AuthorRef struct {
	ID          string `json:"id"`
	DisplayName string `json:"display_name"`
}

type Authorship struct {
	Author       AuthorRef     `json:"author"`
	Institutions []Institution `json:"institutions"`
}

type Work struct {
	ID                    string           `json:"id"`
	Title                 string           `json:"title"`
	DOI                   string           `json:"doi"`
	PublicationYear       int              `json:"publication_year"`
	CitedByCount          int              `json:"cited_by_count"`
	PrimaryLocation       PrimaryLocation  `json:"primary_location"`
	OpenAccess            OpenAccess       `json:"open_access"`
	Authorships           []Authorship     `json:"authorships"`
	Concepts              []Concept        `json:"concepts"`
	Topics                []Topic          `json:"topics"`
	AbstractInvertedIndex map[string][]int `json:"abstract_inverted_index"`
}

type Funder struct {
	ID          string `json:"id"`
	DisplayName string `json:"display_name"`
	Description string `json:"description"`
}

type listResponse[T any] struct {
	Results []T `json:"results"`
}

// ── Client ────────────────────────────────────────────────────────────────────

// Client is a reusable, circuit-broken OpenAlex API client.
type Client struct {
	http    *http.Client
	email   string
	apiKey  string
	breaker *circuitbreaker.Breaker
}

// New creates a Client using OPENALEX_EMAIL and OPENALEX_API_KEY env vars.
// Circuit breaker opens after 5 consecutive failures and probes after 30 s.
func New() *Client {
	email := os.Getenv("OPENALEX_EMAIL")
	if email == "" {
		email = "support@skolab.open"
	}
	return &Client{
		http:    &http.Client{Timeout: 15 * time.Second},
		email:   email,
		apiKey:  os.Getenv("OPENALEX_API_KEY"),
		breaker: circuitbreaker.New(5, 30*time.Second),
	}
}

func (c *Client) headers() http.Header {
	h := make(http.Header)
	h.Set("User-Agent", fmt.Sprintf("SkolabApp/2.0 (mailto:%s)", c.email))
	h.Set("Accept", "application/json")
	if c.apiKey != "" {
		h.Set("api_key", c.apiKey)
	}
	return h
}

// get is the single internal HTTP helper: applies circuit breaker and polite headers.
func (c *Client) get(ctx context.Context, rawURL string, params url.Values) ([]byte, error) {
	if err := c.breaker.Allow(); err != nil {
		return nil, fmt.Errorf("openalex: %w", err)
	}

	params.Set("mailto", c.email)
	fullURL := rawURL + "?" + params.Encode()

	req, err := http.NewRequestWithContext(ctx, http.MethodGet, fullURL, nil)
	if err != nil {
		c.breaker.RecordFailure()
		return nil, err
	}
	for k, vs := range c.headers() {
		req.Header[k] = vs
	}

	resp, err := c.http.Do(req)
	if err != nil {
		c.breaker.RecordFailure()
		return nil, err
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		c.breaker.RecordFailure()
		return nil, fmt.Errorf("openalex: HTTP %d for %s", resp.StatusCode, rawURL)
	}

	body, err := io.ReadAll(resp.Body)
	if err != nil {
		c.breaker.RecordFailure()
		return nil, err
	}
	c.breaker.RecordSuccess()
	return body, nil
}

// ── Public API ────────────────────────────────────────────────────────────────

// SearchAuthors searches OpenAlex for authors matching the query term.
func (c *Client) SearchAuthors(ctx context.Context, query string, perPage int) ([]Author, error) {
	p := url.Values{"search": {query}, "per_page": {fmt.Sprint(perPage)}}
	body, err := c.get(ctx, baseURL+"/authors", p)
	if err != nil {
		slog.Warn("openalex SearchAuthors failed", "query", query, "err", err)
		return nil, err
	}
	var out listResponse[Author]
	if err := json.Unmarshal(body, &out); err != nil {
		return nil, err
	}
	return out.Results, nil
}

// FetchAuthorByID retrieves a single author by their OpenAlex ID (URL or bare ID).
func (c *Client) FetchAuthorByID(ctx context.Context, authorID string) (*Author, error) {
	clean := cleanID(authorID)
	body, err := c.get(ctx, baseURL+"/authors/"+clean, url.Values{})
	if err != nil {
		slog.Warn("openalex FetchAuthorByID failed", "id", clean, "err", err)
		return nil, err
	}
	var a Author
	if err := json.Unmarshal(body, &a); err != nil {
		return nil, err
	}
	return &a, nil
}

// FetchAuthorWorks returns recent works for an author, optionally filtered by ORCID.
func (c *Client) FetchAuthorWorks(ctx context.Context, authorID, orcid string, perPage int, sort string) ([]Work, error) {
	clean := cleanID(authorID)
	filter := "authorships.author.id:" + clean
	if orcid != "" {
		filter = "authorships.author.orcid:" + orcid
	}
	if sort == "" {
		sort = "publication_year:desc"
	}
	p := url.Values{"filter": {filter}, "per_page": {fmt.Sprint(perPage)}, "sort": {sort}}
	body, err := c.get(ctx, baseURL+"/works", p)
	if err != nil {
		slog.Warn("openalex FetchAuthorWorks failed", "id", clean, "err", err)
		return nil, err
	}
	var out listResponse[Work]
	if err := json.Unmarshal(body, &out); err != nil {
		return nil, err
	}
	return out.Results, nil
}

// SearchWorks searches OpenAlex works by keywords.
func (c *Client) SearchWorks(ctx context.Context, query string, perPage int, sort string) ([]Work, error) {
	if sort == "" {
		sort = "publication_year:desc,cited_by_count:desc"
	}
	p := url.Values{"search": {query}, "per_page": {fmt.Sprint(perPage)}, "sort": {sort}}
	body, err := c.get(ctx, baseURL+"/works", p)
	if err != nil {
		slog.Warn("openalex SearchWorks failed", "query", query, "err", err)
		return nil, err
	}
	var out listResponse[Work]
	if err := json.Unmarshal(body, &out); err != nil {
		return nil, err
	}
	return out.Results, nil
}

// FetchWorksByConcept returns works tagged with a concept/topic, trying the new
// topics.id filter first, then the legacy concepts.id filter.
func (c *Client) FetchWorksByConcept(ctx context.Context, conceptID string, prevYear, nowYear, perPage int) ([]Work, error) {
	_ = nowYear // used only for date filter construction
	dateFilter := fmt.Sprintf("from_publication_date:%d-01-01", prevYear)
	for _, prefix := range []string{"topics.id", "concepts.id"} {
		filter := fmt.Sprintf("%s:%s,%s", prefix, conceptID, dateFilter)
		p := url.Values{"filter": {filter}, "sort": {"cited_by_count:desc"}, "per_page": {fmt.Sprint(perPage)}}
		body, err := c.get(ctx, baseURL+"/works", p)
		if err != nil {
			continue
		}
		var out listResponse[Work]
		if err := json.Unmarshal(body, &out); err != nil {
			continue
		}
		if len(out.Results) > 0 {
			return out.Results, nil
		}
	}
	return nil, nil
}

// FetchRelatedWorks returns works related to a specific work ID.
func (c *Client) FetchRelatedWorks(ctx context.Context, workID string, perPage int) ([]Work, error) {
	clean := cleanID(workID)
	p := url.Values{
		"filter":   {"related_to:" + clean},
		"per_page": {fmt.Sprint(perPage)},
		"sort":     {"publication_date:desc"},
	}
	body, err := c.get(ctx, baseURL+"/works", p)
	if err != nil {
		return nil, err
	}
	var out listResponse[Work]
	if err := json.Unmarshal(body, &out); err != nil {
		return nil, err
	}
	return out.Results, nil
}

// SearchFunders searches OpenAlex funders by query/focus area.
func (c *Client) SearchFunders(ctx context.Context, query string, perPage int) ([]Funder, error) {
	if err := c.breaker.Allow(); err != nil {
		return nil, fmt.Errorf("openalex: %w", err)
	}
	p := url.Values{"search": {query}, "per_page": {fmt.Sprint(perPage)}, "sort": {"cited_by_count:desc"}}
	body, err := c.get(ctx, baseURL+"/funders", p)
	if err != nil {
		return nil, err
	}
	var out listResponse[Funder]
	if err := json.Unmarshal(body, &out); err != nil {
		return nil, err
	}
	return out.Results, nil
}

// ── Helpers ───────────────────────────────────────────────────────────────────

// cleanID strips the full OpenAlex URL prefix, returning the bare ID (e.g. "A1234").
func cleanID(id string) string {
	parts := strings.Split(id, "/")
	return parts[len(parts)-1]
}

// ExtractFieldAndExpertise mirrors the Python helper of the same name:
// derives the primary field and a list of expertise areas from topic/concept data.
func ExtractFieldAndExpertise(a *Author) (field string, expertise []string) {
	// Topics are preferred (new OpenAlex format).
	for _, t := range a.Topics {
		expertise = append(expertise, t.DisplayName)
	}
	if len(a.Topics) > 0 {
		first := a.Topics[0]
		if first.Field.DisplayName != "" {
			field = first.Field.DisplayName
		} else if first.Subfield.DisplayName != "" {
			field = first.Subfield.DisplayName
		} else {
			field = first.DisplayName
		}
	}

	// Fallback: filter x_concepts, excluding terms that match the author's own name.
	nameLower := strings.ToLower(a.DisplayName)
	nameWords := make(map[string]bool)
	for _, w := range strings.Fields(nameLower) {
		if len(w) > 2 {
			nameWords[w] = true
		}
	}

	var validConcepts []Concept
	for _, c := range a.XConcepts {
		cLower := strings.ToLower(c.DisplayName)
		isNameMatch := true
		for _, w := range strings.Fields(cLower) {
			if len(w) > 2 && !nameWords[w] {
				isNameMatch = false
				break
			}
		}
		if !isNameMatch {
			validConcepts = append(validConcepts, c)
		}
	}

	if field == "" {
		for _, c := range validConcepts {
			if c.Level == 1 {
				field = c.DisplayName
				break
			}
		}
		if field == "" && len(validConcepts) > 0 {
			field = validConcepts[0].DisplayName
		}
		if field == "" {
			field = "Multidisciplinary"
		}
	}

	if len(expertise) == 0 {
		for _, c := range validConcepts {
			expertise = append(expertise, c.DisplayName)
			if len(expertise) >= 6 {
				break
			}
		}
	}
	return
}
