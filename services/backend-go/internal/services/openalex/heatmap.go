package openalex

import (
	"context"
	"encoding/json"
	"log/slog"
	"net/url"
)

// YearCount is one entry of an OpenAlex author's counts_by_year array.
type YearCount struct {
	Year         int `json:"year"`
	WorksCount   int `json:"works_count"`
	CitedByCount int `json:"cited_by_count"`
}

// FetchAuthorCountsByYear returns the author's per-year works/citation counts
// plus their h_index, in a single /authors/{id} call through the shared
// circuit-broken client. It mirrors the data Python's
// HeatmapMixin.get_citation_heatmap reads out of
// openalex_service.fetch_author_by_id(author_id) — counts_by_year and
// summary_stats.h_index.
//
// Lives in its own file (not client.go) purely to keep concurrent merges small.
func (c *Client) FetchAuthorCountsByYear(ctx context.Context, authorID string) ([]YearCount, int, error) {
	clean := cleanID(authorID)
	body, err := c.get(ctx, baseURL+"/authors/"+clean, url.Values{})
	if err != nil {
		slog.Warn("openalex FetchAuthorCountsByYear failed", "id", clean, "err", err)
		return nil, 0, err
	}
	var payload struct {
		CountsByYear []YearCount  `json:"counts_by_year"`
		SummaryStats SummaryStats `json:"summary_stats"`
	}
	if err := json.Unmarshal(body, &payload); err != nil {
		return nil, 0, err
	}
	return payload.CountsByYear, payload.SummaryStats.HIndex, nil
}
