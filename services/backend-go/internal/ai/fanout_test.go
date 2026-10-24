package ai

import (
	"context"
	"errors"
	"strings"
	"sync/atomic"
	"testing"
	"time"
)

func TestFanoutAsksEverySourceAndReportsWhatEachDid(t *testing.T) {
	b := newBudgets()
	var webCalls atomic.Int32
	providers := []Provider{
		{Name: "openalex", Database: DatabasePublications, Daily: 100, Search: func(context.Context, string) ([]Work, error) {
			return []Work{{ID: "W1", Abstract: "a"}}, nil
		}},
		{Name: "web", Database: DatabaseWeb, Daily: 100, PerCheck: 2, Search: func(context.Context, string) ([]Work, error) {
			webCalls.Add(1)
			return []Work{{ID: "web:x", Database: DatabaseWeb}, {ID: "web:y"}}, nil
		}},
		{Name: "arxiv", Database: DatabasePreprints, Daily: 1, Search: func(context.Context, string) ([]Work, error) {
			return nil, nil
		}},
		{Name: "europe_pmc", Database: DatabaseFullText, Daily: 100, Search: func(context.Context, string) ([]Work, error) {
			return nil, errors.New("down")
		}},
		{Name: "core", Database: DatabaseFullText},
	}
	f := NewFanout(b, providers)
	ctx := context.Background()
	for i := 0; i < 3; i++ {
		works, err := f.Search(ctx, "query")
		if err != nil {
			t.Fatal(err)
		}
		if i == 0 && len(works) != 3 {
			t.Fatalf("%+v", works)
		}
		for _, w := range works {
			if w.Database == "" {
				t.Fatalf("no database on %+v", w)
			}
		}
	}
	if webCalls.Load() != 2 {
		t.Fatalf("web searched %d times, limit 2 a check", webCalls.Load())
	}
	got := map[string]Coverage{}
	for _, c := range f.Coverage() {
		got[c.Source] = c
	}
	want := map[string]Coverage{
		"openalex":   {Source: "openalex", Database: DatabasePublications, Searches: 3, Works: 3, Status: "searched"},
		"web":        {Source: "web", Database: DatabaseWeb, Searches: 2, Works: 4, Status: "searched"},
		"arxiv":      {Source: "arxiv", Database: DatabasePreprints, Searches: 2, Works: 0, Status: "partial"},                  // one a day
		"europe_pmc": {Source: "europe_pmc", Database: DatabaseFullText, Searches: breakAfter, Works: 0, Status: "unavailable"}, // down: asked twice, then left alone
		"core":       {Source: "core", Database: DatabaseFullText, Status: "unconfigured"},
	}
	for name, w := range want {
		if got[name] != w {
			t.Errorf("%s: got %+v, want %+v", name, got[name], w)
		}
	}
	// OpenAlex keeps the budget it has always had; the others have their own.
	if b.used[searchKey] != 3 || b.used[searchKey+":web"] != 2 || b.used[searchKey+":arxiv"] != 1 || b.used[searchKey+":europe_pmc"] != 0 {
		t.Fatalf("%v", b.used)
	}
}

func TestFanoutIsOutOfBudgetOnlyWhenEverySourceIs(t *testing.T) {
	b := newBudgets()
	one := func(context.Context, string) ([]Work, error) { return nil, nil }
	f := NewFanout(b, []Provider{{Name: "a", Daily: 1, Search: one}, {Name: "b", Daily: 2, Search: one}})
	ctx := context.Background()
	for i := 0; i < 2; i++ {
		if _, err := f.Search(ctx, "q"); err != nil {
			t.Fatal(i, err)
		}
	}
	if _, err := f.Search(ctx, "q"); !errors.Is(err, ErrBudget) {
		t.Fatal(err)
	}
	// A source the provider itself rate-limits stops for the rest of the check.
	busy := 0
	g := NewFanout(nil, []Provider{{Name: "busy", Search: func(context.Context, string) ([]Work, error) { busy++; return nil, ErrBudget }}})
	_, _ = g.Search(ctx, "q")
	if _, err := g.Search(ctx, "q"); !errors.Is(err, ErrBudget) || busy != 1 {
		t.Fatal(err, busy)
	}
	// The budget ledger being down is a failed search, not a stopped check.
	down := newBudgets()
	down.down = true
	h := NewFanout(down, []Provider{{Name: "x", Daily: 5, Search: one}})
	if _, err := h.Search(ctx, "q"); err != nil || h.Coverage()[0].Status != "unavailable" {
		t.Fatal(err, h.Coverage())
	}
}

func TestFanoutRefundsAFailedSearch(t *testing.T) {
	b := newBudgets()
	f := NewFanout(b, []Provider{{Name: "flaky", Daily: 10, Search: func(context.Context, string) ([]Work, error) { return nil, errors.New("500") }}})
	_, _ = f.Search(context.Background(), "q")
	if b.used[searchKey+":flaky"] != 0 {
		t.Fatal(b.used)
	}
}

func TestPaceSpacesCallsAcrossChecks(t *testing.T) {
	p := Provider{Name: "paced-test", Pace: 40 * time.Millisecond, Search: func(context.Context, string) ([]Work, error) { return nil, nil }}
	started := time.Now()
	for i := 0; i < 3; i++ {
		f := NewFanout(nil, []Provider{p}) // a new check each time: the pace still holds
		if _, err := f.Search(context.Background(), "q"); err != nil {
			t.Fatal(err)
		}
	}
	if took := time.Since(started); took < 80*time.Millisecond {
		t.Fatalf("three paced calls took %v", took)
	}
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	slow := Provider{Name: "paced-cancel", Pace: time.Hour, Search: p.Search}
	_ = wait(context.Background(), slow) // takes the first slot
	if err := wait(ctx, slow); !errors.Is(err, context.Canceled) {
		t.Fatal(err)
	}
}

func TestCheckCountsAnExactPhraseFoundInAFullText(t *testing.T) {
	latex := texDocument(copied+" in our experiments.", "Our own measurements were collected at the national laboratory over three consecutive winters using a novel setup.")
	var asked []string
	search := func(_ context.Context, q string) ([]Work, error) {
		asked = append(asked, q)
		if strings.Contains(copied, q) {
			// The source found the phrase in a full text but shows only an unrelated abstract.
			return []Work{{ID: "epmc:1", Title: "Magnons", Abstract: "Unrelated abstract words entirely.", Database: DatabaseFullText, Phrase: q}}, nil
		}
		return nil, nil
	}
	result, err := Check(context.Background(), latex, search, 5)
	if err != nil {
		t.Fatal(err)
	}
	if len(result.Matches) != 1 || result.Matches[0].Kind != "copied" || result.Matches[0].SourceID != "epmc:1" || !strings.Contains(result.Matches[0].Excerpt, "…") {
		t.Fatalf("%+v (asked %q)", result.Matches, asked)
	}
	if result.ByDatabase[DatabaseFullText] <= 0 || result.Sources[0].Database != DatabaseFullText {
		t.Fatalf("%+v %+v", result.ByDatabase, result.Sources)
	}
}

func TestCheckMergesOnePaperFoundInSeveralSources(t *testing.T) {
	latex := texDocument(copied+" in our experiments.", "Our own measurements were collected at the national laboratory over three consecutive winters using a novel setup.")
	search := func(_ context.Context, q string) ([]Work, error) {
		return []Work{
			{ID: "W1", DOI: "10.1/X", Title: "Magnons", Abstract: "Short abstract.", Database: DatabasePublications},
			{ID: "core:9", DOI: "10.1/x", Title: "Magnons", Abstract: "Full text: we show that " + copied + " in garnets.", Database: DatabaseFullText},
			{ID: "epmc:2", DOI: "10.1/x", Abstract: "tiny", Database: DatabaseFullText, Phrase: q},
		}, nil
	}
	result, err := Check(context.Background(), latex, search, 3)
	if err != nil {
		t.Fatal(err)
	}
	if result.SourcesChecked != 1 || len(result.Sources) != 1 || result.Sources[0].ID != "core:9" || result.Sources[0].Database != DatabaseFullText {
		t.Fatalf("%+v %+v", result.SourcesChecked, result.Sources)
	}
	if result.ByDatabase[DatabaseFullText] != result.Score || result.Score == 0 {
		t.Fatalf("%v %v", result.ByDatabase, result.Score)
	}
}

func TestFanoutStopsAskingASourceThatKeepsFailing(t *testing.T) {
	calls := 0
	f := NewFanout(nil, []Provider{{Name: "down", Search: func(context.Context, string) ([]Work, error) {
		calls++
		return nil, errors.New("503")
	}}})
	for i := 0; i < 5; i++ {
		if _, err := f.Search(context.Background(), "q"); err != nil {
			t.Fatal(err)
		}
	}
	if calls != breakAfter || f.Coverage()[0].Status != "unavailable" || f.Coverage()[0].Searches != breakAfter {
		t.Fatal(calls, f.Coverage())
	}
	// A success in between resets the count: one failure is not an outage.
	n := 0
	g := NewFanout(nil, []Provider{{Name: "flaky", Search: func(context.Context, string) ([]Work, error) {
		n++
		if n%2 == 1 {
			return nil, errors.New("blip")
		}
		return nil, nil
	}}})
	for i := 0; i < 6; i++ {
		_, _ = g.Search(context.Background(), "q")
	}
	if n != 6 || g.Coverage()[0].Status != "partial" {
		t.Fatal(n, g.Coverage())
	}
}
