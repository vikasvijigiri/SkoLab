package ai

import (
	"context"
	"errors"
	"math"
	"net/http"
	"regexp"
	"strings"
	"time"
)

// The AI-writing estimate: the document's prose is scored in windows of
// 200-300 words by a text classifier (desklib's DeBERTa-v3-large, run as its
// own service, services/ai-detector, reached through Python), and the
// estimate is the share of the scored words in windows at or above Likely.
// Both numbers come from measurement on academic prose
// (services/qa/ai_detect_eval.py): on shorter text detectors raise more
// false alarms, so paragraphs are joined into windows rather than judged
// alone; at 200 words and 0.98 this model flagged none of 198 human-written
// abstracts from before AI writing tools and caught 83% of AI-written text.
// A score is a model's estimate, not proof; the web app says so beside it.

const (
	// Likely is the window score from which its words count as likely
	// AI-written.
	Likely = 0.98
	// Windows: consecutive paragraphs joined until at least windowMinWords;
	// a longer paragraph is cut every windowMaxWords; a short tail joins the
	// window before it. Blocks under blockMinWords (headings, captions) are
	// not prose. A document needs windowMinWords of prose to be judged.
	windowMinWords = 200
	windowMaxWords = 300
	blockMinWords  = 15
	maxPassages    = 60
	// The detector scales to zero; a cold start takes up to a minute.
	detectTimeout = 4 * time.Minute
)

// Passage is one scored part of the prose.
type Passage struct {
	Text        string  `json:"text"`
	Words       int     `json:"words"`
	Probability float64 `json:"probability"` // that it is machine-written, 0-1
	Likely      bool    `json:"likely"`
}

// Writing is the estimate for a document.
type Writing struct {
	Score     float64   `json:"score"` // percent of scored words in likely AI-written passages
	Words     int       `json:"words"` // words scored
	Passages  []Passage `json:"passages"`
	Threshold float64   `json:"threshold"`
	Model     string    `json:"model"`
}

// Detector scores passages: the probability each is machine-written.
type Detector func(ctx context.Context, passages []string) (probabilities []float64, model string, err error)

// ErrDetectorUnavailable means no estimate could be made now.
var ErrDetectorUnavailable = errors.New("ai-writing detector unavailable")

var paragraphBreak = regexp.MustCompile(`\n[ \t]*\n|\\(?:sub)*section\*?|\\paragraph\*?|\\begin\{(?:abstract|itemize|enumerate|figure|table)\}|\\end\{(?:abstract|itemize|enumerate|figure|table)\}|\\item\b|\\maketitle`)

var leadingTitle = regexp.MustCompile(`^\s*(?:\[[^\]]*\])?\{[^{}]*\}`)

// Paragraphs are the document's prose in windows the model can judge.
func Paragraphs(latex string) []string {
	if i := strings.Index(latex, `\begin{document}`); i >= 0 {
		latex = latex[i+len(`\begin{document}`):]
	}
	if i := strings.Index(latex, `\end{document}`); i >= 0 {
		latex = latex[:i]
	}
	latex = WithoutFrontMatter(bibliography.ReplaceAllString(latex, " "))
	var windows [][]string
	var current []string
	flush := func() {
		if len(current) > 0 {
			windows = append(windows, current)
			current = nil
		}
	}
	for _, block := range paragraphBreak.Split(latex, -1) {
		// A section's title, right after the break, is not prose.
		block = leadingTitle.ReplaceAllString(block, " ")
		words := strings.Fields(Prose(`\begin{document}` + block + `\end{document}`))
		if len(words) < blockMinWords {
			continue
		}
		for len(words) > 0 {
			room := windowMaxWords - len(current)
			if room <= 0 {
				flush()
				room = windowMaxWords
			}
			take := min(room, len(words))
			current = append(current, words[:take]...)
			words = words[take:]
			if len(current) >= windowMaxWords {
				flush()
			}
		}
		if len(current) >= windowMinWords {
			flush()
		}
		if len(windows) >= maxPassages {
			break
		}
	}
	// A short tail is judged with the window before it.
	if len(current) > 0 {
		if len(current) < windowMinWords && len(windows) > 0 {
			windows[len(windows)-1] = append(windows[len(windows)-1], current...)
		} else {
			windows = append(windows, current)
		}
	}
	out := make([]string, 0, len(windows))
	for _, w := range windows {
		if len(out) == maxPassages {
			break
		}
		out = append(out, strings.Join(w, " "))
	}
	return out
}

// EstimateWriting scores the document's paragraphs. ErrTooShort means too
// little prose to judge.
func EstimateWriting(ctx context.Context, latex string, detect Detector) (*Writing, error) {
	paragraphs := Paragraphs(latex)
	total := 0
	for _, p := range paragraphs {
		total += len(strings.Fields(p))
	}
	if total < windowMinWords {
		return nil, ErrTooShort
	}
	ctx, cancel := context.WithTimeout(ctx, detectTimeout)
	defer cancel()
	probabilities, model, err := detect(ctx, paragraphs)
	if err != nil {
		return nil, err
	}
	if len(probabilities) != len(paragraphs) {
		return nil, ErrDetectorUnavailable
	}
	w := &Writing{Words: total, Threshold: Likely, Model: model, Passages: make([]Passage, len(paragraphs))}
	likely := 0
	for i, text := range paragraphs {
		p := math.Round(math.Max(0, math.Min(1, probabilities[i]))*1000) / 1000
		words := len(strings.Fields(text))
		w.Passages[i] = Passage{Text: text, Words: words, Probability: p, Likely: p >= Likely}
		if p >= Likely {
			likely += words
		}
	}
	w.Score = math.Round(1000*float64(likely)/float64(total)) / 10
	return w, nil
}

// Warmer wakes the detector, so its cold start overlaps the searches.
type Warmer func(ctx context.Context)

// PythonWarmer asks the Python service to wake the detector (POST
// /api/v1/ai/detect/warm); it does not wait for the answer to matter.
func PythonWarmer(client *http.Client, pythonURL, token string) Warmer {
	return func(ctx context.Context) {
		_ = getJSONWithTimeout(ctx, client, http.MethodPost, strings.TrimRight(pythonURL, "/")+"/api/v1/ai/detect/warm",
			map[string]any{}, map[string]string{"X-Internal-Token": token}, nil, detectTimeout)
	}
}

// PythonDetector asks the Python service (POST /api/v1/ai/detect), which
// admits only the gateway's internal token.
func PythonDetector(client *http.Client, pythonURL, token string) Detector {
	return func(ctx context.Context, passages []string) ([]float64, string, error) {
		var answer struct {
			Probabilities []float64 `json:"probabilities"`
			Model         string    `json:"model"`
		}
		started := time.Now()
		err := getJSONWithTimeout(ctx, client, http.MethodPost, strings.TrimRight(pythonURL, "/")+"/api/v1/ai/detect",
			map[string]any{"passages": passages}, map[string]string{"X-Internal-Token": token}, &answer, detectTimeout)
		if err != nil {
			countProvider(ctx, "detect", "error", time.Since(started))
			return nil, "", ErrDetectorUnavailable
		}
		countProvider(ctx, "detect", "ok", time.Since(started))
		return answer.Probabilities, answer.Model, nil
	}
}
