package ai

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
	"time"
)

func words(n int, word string) string {
	return strings.TrimSpace(strings.Repeat(word+" ", n))
}

func TestParagraphsJoinProseIntoWindowsLongEnoughToJudge(t *testing.T) {
	long := words(640, "alpha")
	latex := `\documentclass{article}\title{Preamble words are ignored entirely here}
\begin{document}
\maketitle
\begin{abstract}` + words(60, "abstract") + `\end{abstract}
\section{Introduction}
` + words(55, "intro") + ` $E=mc^2$ \cite{x}

Too short a paragraph to judge.

` + long + `
\begin{figure}\caption{` + words(10, "caption") + `}\end{figure}
\begin{thebibliography}{9}\bibitem{x} ` + words(80, "reference") + `\end{thebibliography}
\end{document}`
	got := Paragraphs(latex)
	var sizes []int
	for _, p := range got {
		sizes = append(sizes, len(strings.Fields(p)))
	}
	// The abstract (60) and introduction (55; its title is not prose) are too
	// short alone, so they open a window the long paragraph fills to 300; the
	// rest is cut at 300, leaving 155, too short alone, which joins the window
	// before it. The short paragraph and the caption are not prose enough to count.
	if len(sizes) != 2 || sizes[0] != 300 || sizes[1] != 455 {
		t.Fatalf("%v", sizes)
	}
	if !strings.HasPrefix(got[0], "abstract") || strings.Contains(got[0], "Introduction") {
		t.Fatalf("%.60q", got[0])
	}
	for _, p := range got {
		if strings.Contains(p, "reference") || strings.Contains(p, "Preamble") || strings.Contains(p, "caption") || strings.Contains(p, "\\") {
			t.Fatalf("markup, a caption or the bibliography leaked: %.60q", p)
		}
	}
}

func TestAShortTailJoinsTheWindowBeforeIt(t *testing.T) {
	got := Paragraphs(`\begin{document}` + words(200, "first") + "\n\n" + words(40, "tail") + `\end{document}`)
	if len(got) != 1 || len(strings.Fields(got[0])) != 240 {
		t.Fatalf("%d windows", len(got))
	}
	// A document under a window's worth is one short window (EstimateWriting refuses it).
	if got := Paragraphs(`\begin{document}` + words(100, "only") + `\end{document}`); len(got) != 1 {
		t.Fatal(len(got))
	}
}

func TestParagraphsStopAtTheLimit(t *testing.T) {
	var b strings.Builder
	b.WriteString(`\begin{document}`)
	for i := 0; i < maxPassages+10; i++ {
		b.WriteString(words(windowMaxWords, "word") + "\n\n")
	}
	b.WriteString(`\end{document}`)
	if got := Paragraphs(b.String()); len(got) != maxPassages {
		t.Fatal(len(got))
	}
}

func TestEstimateWritingCountsWordsInLikelyPassages(t *testing.T) {
	latex := `\begin{document}` + words(250, "human") + "\n\n" + words(250, "machine") + "\n\n" + words(250, "border") + `\end{document}`
	var sent []string
	detect := func(_ context.Context, passages []string) ([]float64, string, error) {
		sent = passages
		return []float64{0.12, 0.99, Likely - 0.001}, "test-model", nil
	}
	w, err := EstimateWriting(context.Background(), latex, detect)
	if err != nil {
		t.Fatal(err)
	}
	if len(sent) != 3 || w.Words != 750 || w.Model != "test-model" || w.Threshold != Likely {
		t.Fatalf("%+v", w)
	}
	// Only the 0.99 window counts: 250 of 750 words.
	if w.Score != 33.3 || !w.Passages[1].Likely || w.Passages[2].Likely || w.Passages[0].Probability != 0.12 {
		t.Fatalf("%+v", w)
	}
}

func TestEstimateWritingRefusesTooLittleAndReportsAFailedDetector(t *testing.T) {
	never := func(context.Context, []string) ([]float64, string, error) { t.Fatal("asked"); return nil, "", nil }
	if _, err := EstimateWriting(context.Background(), `\begin{document}`+words(windowMinWords-1, "few")+`\end{document}`, never); !errors.Is(err, ErrTooShort) {
		t.Fatal(err)
	}
	latex := `\begin{document}` + words(200, "enough") + `\end{document}`
	down := func(context.Context, []string) ([]float64, string, error) { return nil, "", ErrDetectorUnavailable }
	if _, err := EstimateWriting(context.Background(), latex, down); !errors.Is(err, ErrDetectorUnavailable) {
		t.Fatal(err)
	}
	short := func(context.Context, []string) ([]float64, string, error) { return []float64{}, "m", nil }
	if _, err := EstimateWriting(context.Background(), latex, short); !errors.Is(err, ErrDetectorUnavailable) {
		t.Fatal(err)
	}
	wild := func(_ context.Context, p []string) ([]float64, string, error) { return []float64{7}, "m", nil }
	if w, err := EstimateWriting(context.Background(), latex, wild); err != nil || w.Passages[0].Probability != 1 {
		t.Fatal(err, w)
	}
}

func TestPythonDetectorSendsPassagesWithTheInternalToken(t *testing.T) {
	var got struct {
		Passages []string `json:"passages"`
	}
	var token string
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		token = r.Header.Get("X-Internal-Token")
		raw, _ := io.ReadAll(r.Body)
		_ = json.Unmarshal(raw, &got)
		if r.URL.Path != "/api/v1/ai/detect" {
			w.WriteHeader(404)
			return
		}
		_, _ = io.WriteString(w, `{"probabilities":[0.25,0.75],"model":"m"}`)
	}))
	defer server.Close()
	p, model, err := PythonDetector(server.Client(), server.URL+"/", "secret")(context.Background(), []string{"a", "b"})
	if err != nil || model != "m" || len(p) != 2 || p[1] != 0.75 || token != "secret" || len(got.Passages) != 2 {
		t.Fatal(p, model, err, token, got)
	}
	down := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) { w.WriteHeader(503) }))
	defer down.Close()
	if _, _, err := PythonDetector(down.Client(), down.URL, "s")(context.Background(), []string{"a"}); !errors.Is(err, ErrDetectorUnavailable) {
		t.Fatal(err)
	}
}

func TestPythonWarmerAsksPythonWithTheToken(t *testing.T) {
	var path, token string
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		path, token = r.URL.Path, r.Header.Get("X-Internal-Token")
		w.WriteHeader(http.StatusNoContent)
	}))
	defer server.Close()
	PythonWarmer(server.Client(), server.URL, "secret")(context.Background())
	if path != "/api/v1/ai/detect/warm" || token != "secret" {
		t.Fatal(path, token)
	}
}

func TestTheRunnerWakesTheDetectorAndReportsWritingAndCoverage(t *testing.T) {
	store := newReports()
	store.source = `\begin{document}` + words(260, "prose") + `\end{document}`
	report, _, _ := store.Create(context.Background(), ws, "ada", 1)
	woken := make(chan struct{}, 1)
	providers := []Provider{
		{Name: "openalex", Database: DatabasePublications, Search: func(context.Context, string) ([]Work, error) { return nil, nil }},
		{Name: "core", Database: DatabaseFullText},
	}
	runner := NewRunner(store, nil, 3, 1).WithSources(nil, providers).WithDetector(
		func(_ context.Context, p []string) ([]float64, string, error) { return []float64{0.99}, "m", nil },
		func(context.Context) { woken <- struct{}{} },
	)
	var done sync.WaitGroup
	done.Add(1)
	runner.Submit(report, done.Done)
	done.Wait()
	got, _ := store.Get(context.Background(), ws, report.ID)
	if got.Status != "done" || got.Result == nil || got.Result.WritingStatus != "ok" || got.Result.Writing.Score != 100 {
		t.Fatalf("%+v", got)
	}
	if len(got.Result.Coverage) != 2 || got.Result.Coverage[0].Status != "unconfigured" || got.Result.Coverage[1].Source != "openalex" {
		t.Fatalf("%+v", got.Result.Coverage)
	}
	select {
	case <-woken:
	case <-time.After(time.Second):
		t.Fatal("the detector was not woken")
	}

	// Without a detector the report says the estimate is off.
	report2, _, _ := store.Create(context.Background(), "ws-2", "ada", 1)
	done.Add(1)
	NewRunner(store, func(context.Context, string) ([]Work, error) { return nil, nil }, 3, 1).Submit(report2, done.Done)
	done.Wait()
	if got, _ := store.Get(context.Background(), "ws-2", report2.ID); got.Result == nil || got.Result.WritingStatus != "off" || got.Result.Writing != nil {
		t.Fatalf("%+v", got)
	}
}
