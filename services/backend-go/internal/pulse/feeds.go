package pulse

import (
	"encoding/xml"
	"html"
	"regexp"
	"strings"
	"time"
)

// NewsItem is one normalised headline from any feed.
type NewsItem struct {
	Title     string `json:"title"`
	URL       string `json:"url"`
	Source    string `json:"source"`
	Published string `json:"published"` // RFC3339, "" when the feed omitted a date
	Summary   string `json:"summary"`
}

// ── RSS 2.0 + RSS 1.0/RDF ─────────────────────────────────────────────────────
// RSS 2.0 nests <item> under <channel>; RSS 1.0 (RDF, e.g. Nature) puts <item>
// as a sibling of <channel> at the document root. Capture both.

type rssItem struct {
	Title       string `xml:"title"`
	Link        string `xml:"link"`
	Description string `xml:"description"`
	PubDate     string `xml:"pubDate"`
	Date        string `xml:"date"` // dc:date fallback (RDF feeds)
}

type rss struct {
	ChannelItems []rssItem `xml:"channel>item"`
	RootItems    []rssItem `xml:"item"`
}

func (r rss) items() []rssItem {
	if len(r.ChannelItems) > 0 {
		return r.ChannelItems
	}
	return r.RootItems
}

// ── Atom ──────────────────────────────────────────────────────────────────────

type atom struct {
	Entries []struct {
		Title string `xml:"title"`
		Links []struct {
			Href string `xml:"href,attr"`
			Rel  string `xml:"rel,attr"`
		} `xml:"link"`
		Summary   string `xml:"summary"`
		Content   string `xml:"content"`
		Updated   string `xml:"updated"`
		Published string `xml:"published"`
	} `xml:"entry"`
}

var tagRe = regexp.MustCompile(`<[^>]*>`)

// clean strips tags/entities and collapses whitespace, then truncates.
func clean(s string, max int) string {
	s = tagRe.ReplaceAllString(s, " ")
	s = html.UnescapeString(s)
	s = strings.Join(strings.Fields(s), " ")
	if max > 0 && len(s) > max {
		if i := strings.LastIndex(s[:max], " "); i > 40 {
			s = s[:i]
		} else {
			s = s[:max]
		}
		s += "…"
	}
	return s
}

// dateFormats covers what the science feeds actually emit (RFC1123Z is RSS's
// canonical pubDate; the rest are real observed variants).
var dateFormats = []string{
	time.RFC1123Z,
	time.RFC1123,
	time.RFC3339,
	"2006-01-02T15:04:05Z07:00",
	"Mon, 2 Jan 2006 15:04:05 -0700",
	"Mon, 2 Jan 2006 15:04:05 MST",
	"2006-01-02",
}

func parseDate(s string) string {
	s = strings.TrimSpace(s)
	if s == "" {
		return ""
	}
	for _, f := range dateFormats {
		if t, err := time.Parse(f, s); err == nil {
			return t.UTC().Format(time.RFC3339)
		}
	}
	return ""
}

// parseFeed reads either RSS 2.0 or Atom and returns normalised items tagged
// with source. Unknown/!ok bytes yield (nil, nil) rather than an error — one
// bad feed must not sink the aggregate.
func parseFeed(body []byte, source string) []NewsItem {
	head := strings.TrimSpace(string(body))
	if len(head) > 400 {
		head = head[:400]
	}
	out := []NewsItem{}

	// Atom's root is <feed>; RSS 2.0 is <rss>; RSS 1.0 is <rdf:RDF>. Decide on
	// the root element near the top, not anywhere in the body.
	if strings.Contains(head, "<feed") && !strings.Contains(head, "<rss") {
		var a atom
		if xml.Unmarshal(body, &a) != nil {
			return nil
		}
		for _, e := range a.Entries {
			link := ""
			for _, l := range e.Links {
				if l.Rel == "alternate" || l.Rel == "" {
					link = l.Href
					break
				}
			}
			if link == "" && len(e.Links) > 0 {
				link = e.Links[0].Href
			}
			sum := e.Summary
			if sum == "" {
				sum = e.Content
			}
			when := e.Published
			if when == "" {
				when = e.Updated
			}
			it := NewsItem{
				Title:     clean(e.Title, 200),
				URL:       strings.TrimSpace(link),
				Source:    source,
				Published: parseDate(when),
				Summary:   clean(sum, 240),
			}
			if it.Title != "" && it.URL != "" {
				out = append(out, it)
			}
		}
		return out
	}

	var r rss
	if xml.Unmarshal(body, &r) != nil {
		return nil
	}
	for _, i := range r.items() {
		when := i.PubDate
		if when == "" {
			when = i.Date
		}
		it := NewsItem{
			Title:     clean(i.Title, 200),
			URL:       strings.TrimSpace(i.Link),
			Source:    source,
			Published: parseDate(when),
			Summary:   clean(i.Description, 240),
		}
		if it.Title != "" && it.URL != "" {
			out = append(out, it)
		}
	}
	return out
}
