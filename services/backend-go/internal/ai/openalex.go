package ai

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"sort"
	"strings"
	"time"
)

// OpenAlex (openalex.org): 250M+ scholarly works, searched across titles,
// abstracts and full texts. A free key allows about 1,000 searches a day;
// every search is charged to a shared daily budget first (see budgets.go).
type OpenAlex struct {
	BaseURL string
	Key     string
	Client  *http.Client
}

const (
	openAlexBase    = "https://api.openalex.org"
	openAlexTimeout = 10 * time.Second
	openAlexPerPage = 5
)

type openAlexWork struct {
	ID              string           `json:"id"`
	DOI             string           `json:"doi"`
	Title           string           `json:"title"`
	PublicationYear int              `json:"publication_year"`
	Abstract        map[string][]int `json:"abstract_inverted_index"`
	PrimaryLocation *struct {
		LandingPageURL string `json:"landing_page_url"`
	} `json:"primary_location"`
}

// Search answers up to five works for query.
func (o OpenAlex) Search(ctx context.Context, query string) ([]Work, error) {
	base := o.BaseURL
	if base == "" {
		base = openAlexBase
	}
	params := url.Values{
		"search":   {query},
		"per_page": {fmt.Sprint(openAlexPerPage)},
		"select":   {"id,doi,title,publication_year,abstract_inverted_index,primary_location"},
	}
	if o.Key != "" {
		params.Set("api_key", o.Key)
	}
	ctx, cancel := context.WithTimeout(ctx, openAlexTimeout)
	defer cancel()
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, base+"/works?"+params.Encode(), nil)
	if err != nil {
		return nil, err
	}
	req.Header.Set("Accept", "application/json")
	req.Header.Set("User-Agent", "SkoLab originality check (+https://skolab-web.onrender.com)")
	client := o.Client
	if client == nil {
		client = http.DefaultClient
	}
	resp, err := client.Do(req)
	if err != nil {
		// Never wrap the URL: it carries the key.
		return nil, fmt.Errorf("openalex: request failed")
	}
	defer resp.Body.Close()
	if resp.StatusCode == http.StatusTooManyRequests {
		return nil, ErrBudget
	}
	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("openalex: status %d", resp.StatusCode)
	}
	var body struct {
		Results []openAlexWork `json:"results"`
	}
	if err := json.NewDecoder(io.LimitReader(resp.Body, 4<<20)).Decode(&body); err != nil {
		return nil, fmt.Errorf("openalex: unreadable answer")
	}
	works := make([]Work, 0, len(body.Results))
	for _, r := range body.Results {
		link := r.ID
		if r.DOI != "" {
			link = r.DOI
		} else if r.PrimaryLocation != nil && r.PrimaryLocation.LandingPageURL != "" {
			link = r.PrimaryLocation.LandingPageURL
		}
		works = append(works, Work{
			ID:       r.ID,
			DOI:      strings.TrimPrefix(r.DOI, "https://doi.org/"),
			Title:    r.Title,
			Year:     r.PublicationYear,
			URL:      link,
			Abstract: abstract(r.Abstract),
		})
	}
	return works, nil
}

// abstract rebuilds text from OpenAlex's inverted index (word -> positions).
func abstract(index map[string][]int) string {
	type placed struct {
		at   int
		word string
	}
	var words []placed
	for word, positions := range index {
		for _, at := range positions {
			if at >= 0 && at < 20000 {
				words = append(words, placed{at, word})
			}
		}
	}
	sort.Slice(words, func(i, j int) bool { return words[i].at < words[j].at })
	parts := make([]string, len(words))
	for i, w := range words {
		parts[i] = w.word
	}
	return strings.Join(parts, " ")
}
