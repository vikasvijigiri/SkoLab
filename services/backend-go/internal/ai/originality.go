package ai

import (
	"context"
	"errors"
	"math"
	"regexp"
	"sort"
	"strings"
	"unicode"
)

// The originality check compares a document's prose with the scholarly
// record and the web: it picks the document's most distinctive sentences,
// searches every configured source for them (sources.go: publications'
// abstracts, open-access full texts, preprints, web pages), then measures,
// for every sentence, how much of its wording (runs of five words) appears
// in a found work's text. Sources that find an exact phrase in a full text
// they do not return count that phrase as found. It finds copied and lightly
// edited text, not paraphrase, and only in what the sources can see;
// reports list what was searched rather than claiming more.

const (
	shingleSize = 5
	// A sentence counts as copied when this share of its five-word runs
	// appears in one work.
	matchThreshold = 0.5
	// A sentence counts as edited from a work (words swapped or reordered
	// here and there) when both hold for that work: this share of its
	// three-word runs appears, and this share of its content words.
	editedRuns      = 0.3
	editedContent   = 0.7
	minContentWords = 6
	minWords        = 30 // below this there is too little prose to judge
	probeWords      = 10 // words of a sentence sent as a search query
	minProbeWords   = 12 // sentences shorter than this make poor queries
	minMatchWords   = 8  // sentences shorter than this are not scored
)

// Work is one search hit: a published work and the text compared against.
type Work struct {
	ID       string
	DOI      string
	Title    string
	Year     int
	URL      string
	Abstract string // the text compared: an abstract, a full-text passage, a full text or a page
	Database string // DatabasePublications, DatabaseFullText, DatabasePreprints or DatabaseWeb
	// Phrase is the query when the source matched it as an exact phrase in
	// the work's full text (which Abstract may not hold).
	Phrase string
}

// Searcher finds works related to a query. ErrBudget means no more searches
// may be made today; the check then finishes with what it has.
type Searcher func(ctx context.Context, query string) ([]Work, error)

var ErrBudget = errors.New("search budget spent")

// ErrTooShort is a document with too little prose to check.
var ErrTooShort = errors.New("too little prose to check")

// Source is a work some of the document matched.
type Source struct {
	ID        string `json:"id"`
	Title     string `json:"title"`
	Year      int    `json:"year,omitempty"`
	URL       string `json:"url"`
	DOI       string `json:"doi,omitempty"`
	Database  string `json:"database"`
	Sentences int    `json:"sentences"`
	Words     int    `json:"words"`
}

// Match is a sentence of the document found in a source.
type Match struct {
	Sentence string  `json:"sentence"`
	Kind     string  `json:"kind"`    // copied (word for word) or edited (lightly reworded)
	Overlap  float64 `json:"overlap"` // copied: share of its five-word runs found; edited: share of its content words found
	SourceID string  `json:"source_id"`
	Excerpt  string  `json:"excerpt"` // the source's words around the match
}

// Result is a finished check.
type Result struct {
	Score          float64  `json:"score"` // percent of the document's words in matched sentences
	Words          int      `json:"words"`
	MatchedWords   int      `json:"matched_words"`
	Sentences      int      `json:"sentences"`
	Probes         int      `json:"probes"`
	SourcesChecked int      `json:"sources_checked"`
	Partial        bool     `json:"partial"` // stopped early: the day's search budget ran out
	Matches        []Match  `json:"matches"`
	Sources        []Source `json:"sources"`
	// ByDatabase is the score's share from each kind of source: percent of
	// the document's words matched there (a sentence counts once, toward the
	// source it matched best).
	ByDatabase map[string]float64 `json:"by_database"`
	// Coverage is every source and what it was asked (set by the runner).
	Coverage []Coverage `json:"coverage"`
	// Writing is the AI-writing estimate, when it could be made.
	Writing *Writing `json:"writing"`
	// WritingStatus says why Writing is missing: too_short, unavailable or
	// off (not run on this server); ok when it is there.
	WritingStatus string `json:"writing_status"`
}

type sentence struct {
	text  string
	words []string
}

// Check runs the originality check on a LaTeX source.
func Check(ctx context.Context, latex string, search Searcher, maxProbes int) (Result, error) {
	sentences := split(Prose(latex))
	total := 0
	for _, s := range sentences {
		total += len(s.words)
	}
	if total < minWords {
		return Result{}, ErrTooShort
	}
	result := Result{Words: total, Sentences: len(sentences), Matches: []Match{}, Sources: []Source{}, ByDatabase: map[string]float64{}, Coverage: []Coverage{}}

	works := map[string]Work{}
	var order []string
	probed := map[string]bool{} // queries searched
	for _, probe := range probes(sentences, maxProbes) {
		if err := ctx.Err(); err != nil {
			return Result{}, err
		}
		found, err := search(ctx, query(probe.words))
		if errors.Is(err, ErrBudget) {
			result.Partial = true
			break
		}
		if err != nil {
			return Result{}, err
		}
		result.Probes++
		probed[query(probe.words)] = true
		for _, w := range found {
			if w.ID == "" {
				continue
			}
			// One paper found in several sources is one work: keep the
			// longest text, and any exact-phrase evidence.
			key := w.ID
			if w.DOI != "" {
				key = "doi:" + strings.ToLower(w.DOI)
			}
			seen, ok := works[key]
			if !ok {
				works[key] = w
				order = append(order, key)
				continue
			}
			if len(w.Abstract) > len(seen.Abstract) {
				phrase := seen.Phrase
				seen = w
				if seen.Phrase == "" {
					seen.Phrase = phrase
				}
			} else if seen.Phrase == "" {
				seen.Phrase = w.Phrase
			}
			works[key] = seen
		}
	}
	result.SourcesChecked = len(order)

	type candidate struct {
		work     Work
		words    []string
		shingles map[string]int // five-word run -> index of its first word
		triples  map[string]int // three-word run -> index of its first word
		content  map[string]bool
	}
	candidates := make([]candidate, 0, len(order))
	for _, id := range order {
		w := works[id]
		words := tokens(w.Title + ". " + w.Abstract)
		candidates = append(candidates, candidate{w, words, runs(words, shingleSize), runs(words, 3), contentWords(words)})
	}

	sources := map[string]*Source{}
	byDatabase := map[string]int{}
	for _, s := range sentences {
		if len(s.words) < minMatchWords {
			continue
		}
		mine, mineTriples, mineContent := runs(s.words, shingleSize), runs(s.words, 3), contentWords(s.words)
		share := func(set map[string]int, in map[string]int) (float64, int) {
			shared, first := 0, -1
			for key := range set {
				if at, ok := in[key]; ok {
					shared++
					if first < 0 || at < first {
						first = at
					}
				}
			}
			return float64(shared) / float64(max(len(set), 1)), first
		}
		best, bestShare, bestAt, kind := -1, 0.0, 0, ""
		for i, c := range candidates {
			if v, at := share(mine, c.shingles); v >= matchThreshold && (kind != "copied" || v > bestShare) {
				best, bestShare, bestAt, kind = i, v, at, "copied"
			}
		}
		if kind == "" && len(mineContent) >= minContentWords {
			for i, c := range candidates {
				triple, at := share(mineTriples, c.triples)
				found := 0
				for w := range mineContent {
					if c.content[w] {
						found++
					}
				}
				words := float64(found) / float64(len(mineContent))
				if triple >= editedRuns && words >= editedContent && words > bestShare {
					best, bestShare, bestAt, kind = i, words, at, "edited"
				}
			}
		}
		if best < 0 {
			// A full-text source found this sentence's exact searched phrase
			// in a work it does not show: that phrase is the evidence.
			if q := query(s.words); probed[q] {
				for i, c := range candidates {
					if c.work.Phrase == q {
						qw := strings.Fields(q)
						best, bestShare, kind = i, math.Min(1, float64(len(qw))/float64(len(s.words))), "copied"
						bestAt = -1
						break
					}
				}
			}
		}
		if best < 0 {
			continue
		}
		c := candidates[best]
		quote := excerpt(c.words, bestAt, len(s.words))
		if bestAt < 0 {
			quote = "… " + query(s.words) + " …"
		}
		result.MatchedWords += len(s.words)
		result.Matches = append(result.Matches, Match{
			Sentence: s.text,
			Kind:     kind,
			Overlap:  math.Round(bestShare*100) / 100,
			SourceID: c.work.ID,
			Excerpt:  quote,
		})
		src := sources[c.work.ID]
		if src == nil {
			src = &Source{ID: c.work.ID, Title: c.work.Title, Year: c.work.Year, URL: c.work.URL, DOI: c.work.DOI, Database: c.work.Database}
			sources[c.work.ID] = src
		}
		src.Sentences++
		src.Words += len(s.words)
		byDatabase[c.work.Database] += len(s.words)
	}
	for database, words := range byDatabase {
		result.ByDatabase[database] = math.Round(1000*float64(words)/float64(total)) / 10
	}
	for _, s := range sources {
		result.Sources = append(result.Sources, *s)
	}
	sort.Slice(result.Sources, func(i, j int) bool {
		if result.Sources[i].Words != result.Sources[j].Words {
			return result.Sources[i].Words > result.Sources[j].Words
		}
		return result.Sources[i].ID < result.Sources[j].ID
	})
	result.Score = math.Round(1000*float64(result.MatchedWords)/float64(total)) / 10
	return result, nil
}

// probes picks up to n sentences long enough to search with, spread over
// the document (one from each stretch), preferring distinctive wording.
func probes(sentences []sentence, n int) []sentence {
	var eligible []sentence
	for _, s := range sentences {
		if len(s.words) >= minProbeWords {
			eligible = append(eligible, s)
		}
	}
	if n <= 0 || len(eligible) == 0 {
		return nil
	}
	if len(eligible) <= n {
		return eligible
	}
	picked := make([]sentence, 0, n)
	for i := 0; i < n; i++ {
		lo, hi := i*len(eligible)/n, (i+1)*len(eligible)/n
		best := lo
		for j := lo; j < hi; j++ {
			if distinctive(eligible[j]) > distinctive(eligible[best]) {
				best = j
			}
		}
		picked = append(picked, eligible[best])
	}
	return picked
}

// distinctive favours long words, which are rarer and make sharper queries.
func distinctive(s sentence) int {
	score := 0
	for _, w := range s.words {
		if len(w) >= 7 {
			score += 2
		} else if len(w) >= 4 {
			score++
		}
	}
	return score
}

// query is the sentence's most distinctive run of words.
func query(words []string) string {
	if len(words) <= probeWords {
		return strings.Join(words, " ")
	}
	best, bestScore := 0, -1
	for i := 0; i+probeWords <= len(words); i++ {
		if score := distinctive(sentence{words: words[i : i+probeWords]}); score > bestScore {
			best, bestScore = i, score
		}
	}
	return strings.Join(words[best:best+probeWords], " ")
}

// stopwords are left out of content words: they say nothing about wording.
var stopwords = map[string]bool{
	"about": true, "above": true, "after": true, "again": true, "also": true, "been": true, "being": true, "both": true,
	"does": true, "each": true, "from": true, "have": true, "here": true, "into": true, "more": true, "most": true,
	"much": true, "only": true, "other": true, "over": true, "same": true, "some": true, "such": true, "than": true,
	"that": true, "their": true, "them": true, "then": true, "there": true, "these": true, "they": true, "this": true,
	"those": true, "through": true, "under": true, "very": true, "were": true, "what": true, "when": true, "where": true,
	"which": true, "while": true, "with": true, "would": true, "your": true, "will": true, "shall": true, "should": true,
	"could": true, "between": true, "within": true, "using": true, "used": true, "show": true, "shows": true, "shown": true,
}

// contentWords are the words of four or more letters that carry meaning.
func contentWords(words []string) map[string]bool {
	out := map[string]bool{}
	for _, w := range words {
		if len([]rune(w)) >= 4 && !stopwords[w] {
			out[w] = true
		}
	}
	return out
}

// runs maps every n-word run of words to where it first starts.
func runs(words []string, n int) map[string]int {
	out := map[string]int{}
	if len(words) < n {
		if len(words) > 0 {
			out[strings.Join(words, " ")] = 0
		}
		return out
	}
	for i := 0; i+n <= len(words); i++ {
		key := strings.Join(words[i:i+n], " ")
		if _, seen := out[key]; !seen {
			out[key] = i
		}
	}
	return out
}

func shingles(words []string) map[string]int {
	out := map[string]int{}
	if len(words) < shingleSize {
		if len(words) > 0 {
			out[strings.Join(words, " ")] = 0
		}
		return out
	}
	for i := 0; i+shingleSize <= len(words); i++ {
		key := strings.Join(words[i:i+shingleSize], " ")
		if _, seen := out[key]; !seen {
			out[key] = i
		}
	}
	return out
}

// excerpt is up to n+10 of a source's words from where the match begins.
func excerpt(words []string, at, n int) string {
	from := max(at-3, 0)
	to := min(at+n+7, len(words))
	text := strings.Join(words[from:to], " ")
	if from > 0 {
		text = "… " + text
	}
	if to < len(words) {
		text += " …"
	}
	return text
}

// tokens lowercases text into words of letters and digits.
func tokens(text string) []string {
	return strings.FieldsFunc(strings.ToLower(text), func(r rune) bool { return !unicode.IsLetter(r) && !unicode.IsNumber(r) })
}

var sentenceEnd = regexp.MustCompile(`([.!?])\s+`)

// split breaks prose into sentences (at ., ! or ? followed by space; a
// decimal point or an abbreviation without a following capital stays).
func split(prose string) []sentence {
	var out []sentence
	add := func(text string) {
		text = strings.TrimSpace(text)
		if words := tokens(text); len(words) > 0 {
			out = append(out, sentence{text, words})
		}
	}
	start := 0
	for _, loc := range sentenceEnd.FindAllStringIndex(prose, -1) {
		next := loc[1]
		if next < len(prose) {
			r := []rune(prose[next:])[0]
			if !unicode.IsUpper(r) && !unicode.IsDigit(r) {
				continue
			}
		}
		add(prose[start : loc[0]+1])
		start = next
	}
	add(prose[start:])
	return out
}

var (
	spaces       = regexp.MustCompile(`\s+`)
	bibliography = regexp.MustCompile(`(?s)\\begin\{thebibliography\}.*?\\end\{thebibliography\}`)
)

// Prose is the readable text of a LaTeX source: the document body without
// the preamble, comments, math, citations, references or commands (their
// prose arguments, such as section titles and emphasis, stay).
func Prose(latex string) string {
	if i := strings.Index(latex, `\begin{document}`); i >= 0 {
		latex = latex[i+len(`\begin{document}`):]
	}
	if i := strings.Index(latex, `\end{document}`); i >= 0 {
		latex = latex[:i]
	}
	latex = bibliography.ReplaceAllString(latex, " ")
	m := Mask(latex)
	var b strings.Builder
	inPlaceholder := false
	for _, r := range m.Text {
		switch {
		case r == '⟦':
			inPlaceholder = true
			b.WriteByte(' ')
		case r == '⟧':
			inPlaceholder = false
		case inPlaceholder, r == '{', r == '}', r == '~':
			if r == '~' {
				b.WriteByte(' ')
			}
		default:
			b.WriteRune(r)
		}
	}
	return strings.TrimSpace(spaces.ReplaceAllString(b.String(), " "))
}
