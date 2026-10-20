package ai

import (
	"context"
	"errors"
	"sort"
	"strings"
	"sync"
	"time"

	"github.com/skolab/backend-go/internal/quota"
)

// Coverage is what one source contributed to a check, for the report.
type Coverage struct {
	Source   string `json:"source"`
	Database string `json:"database"`
	Searches int    `json:"searches"`
	Works    int    `json:"works"`
	// searched; partial (stopped: daily budget or errors); unavailable
	// (every search failed); unconfigured (no key on this server).
	Status string `json:"status"`
}

// Fanout searches every provider for each query, in parallel, each within
// its own daily budget and per-check limit, and keeps count for the report.
type Fanout struct {
	budgets      Budgets
	providers    []Provider
	unconfigured []Provider

	mu       sync.Mutex
	searches map[string]int
	works    map[string]int
	failures map[string]int
	stopped  map[string]bool
}

// NewFanout is the search for one check. Providers without a Search are
// reported as not configured.
func NewFanout(b Budgets, providers []Provider) *Fanout {
	f := &Fanout{budgets: b, searches: map[string]int{}, works: map[string]int{}, failures: map[string]int{}, stopped: map[string]bool{}}
	for _, p := range providers {
		if p.Search == nil {
			f.unconfigured = append(f.unconfigured, p)
		} else {
			f.providers = append(f.providers, p)
		}
	}
	return f
}

func sourceBudgetKey(name string) string {
	if name == "openalex" {
		return searchKey // the budget OpenAlex has always had
	}
	return searchKey + ":" + name
}

// pacers space calls to each provider across every check in this process.
var pacers sync.Map // provider name -> *pacer

type pacer struct {
	sync.Mutex
	next time.Time
}

// wait holds a call until the provider's pace allows it.
func wait(ctx context.Context, p Provider) error {
	if p.Pace <= 0 {
		return nil
	}
	value, _ := pacers.LoadOrStore(p.Name, &pacer{})
	pc := value.(*pacer)
	pc.Lock()
	at := pc.next
	if now := time.Now(); at.Before(now) {
		at = now
	}
	pc.next = at.Add(p.Pace)
	pc.Unlock()
	select {
	case <-time.After(time.Until(at)):
		return nil
	case <-ctx.Done():
		return ctx.Err()
	}
}

// Search is a Searcher: every provider still able to search is asked; the
// answer is ErrBudget when every provider has run out of its daily budget
// (or been rate-limited), so the check stops and says it is partial.
func (f *Fanout) Search(ctx context.Context, query string) ([]Work, error) {
	var (
		wg    sync.WaitGroup
		mu    sync.Mutex
		found []Work
	)
	for _, p := range f.providers {
		if !f.take(p) {
			continue
		}
		wg.Add(1)
		go func(p Provider) {
			defer wg.Done()
			works, err := f.one(ctx, p, query)
			f.mu.Lock()
			switch {
			case errors.Is(err, ErrBudget):
				f.stopped[p.Name] = true
			case err != nil:
				f.failures[p.Name]++
			default:
				f.works[p.Name] += len(works)
			}
			f.mu.Unlock()
			if err == nil {
				mu.Lock()
				for _, w := range works {
					if w.Database == "" {
						w.Database = p.Database
					}
					found = append(found, w)
				}
				mu.Unlock()
			}
		}(p)
	}
	wg.Wait()
	if f.allStopped() {
		return found, ErrBudget
	}
	return found, nil
}

func (f *Fanout) allStopped() bool {
	f.mu.Lock()
	defer f.mu.Unlock()
	for _, p := range f.providers {
		if !f.stopped[p.Name] {
			return false
		}
	}
	return len(f.providers) > 0
}

// take counts a search against the provider's per-check limit.
func (f *Fanout) take(p Provider) bool {
	f.mu.Lock()
	defer f.mu.Unlock()
	if f.stopped[p.Name] || (p.PerCheck > 0 && f.searches[p.Name] >= p.PerCheck) {
		return false
	}
	f.searches[p.Name]++
	return true
}

func (f *Fanout) one(ctx context.Context, p Provider, query string) ([]Work, error) {
	if err := wait(ctx, p); err != nil {
		return nil, err
	}
	if f.budgets != nil && p.Daily > 0 {
		_, refund, err := f.budgets.Charge(ctx, sourceBudgetKey(p.Name), 1, p.Daily)
		var exceeded *quota.Exceeded
		if errors.As(err, &exceeded) {
			countProvider(ctx, "search_"+p.Name, "over_budget", 0)
			return nil, ErrBudget
		}
		if err != nil {
			return nil, err
		}
		started := time.Now()
		works, err := p.Search(ctx, query)
		switch {
		case errors.Is(err, ErrBudget):
			countProvider(ctx, "search_"+p.Name, "busy", time.Since(started))
		case err != nil:
			refund()
			countProvider(ctx, "search_"+p.Name, "error", time.Since(started))
		default:
			countProvider(ctx, "search_"+p.Name, "ok", time.Since(started))
		}
		return works, err
	}
	return p.Search(ctx, query)
}

// Coverage reports every provider, configured or not, in a stable order.
func (f *Fanout) Coverage() []Coverage {
	f.mu.Lock()
	defer f.mu.Unlock()
	out := make([]Coverage, 0, len(f.providers)+len(f.unconfigured))
	for _, p := range f.providers {
		c := Coverage{Source: p.Name, Database: p.Database, Searches: f.searches[p.Name], Works: f.works[p.Name], Status: "searched"}
		switch failed := f.failures[p.Name]; {
		case c.Searches > 0 && failed == c.Searches:
			c.Status = "unavailable"
		case f.stopped[p.Name] || failed > 0:
			c.Status = "partial"
		}
		out = append(out, c)
	}
	for _, p := range f.unconfigured {
		out = append(out, Coverage{Source: p.Name, Database: p.Database, Status: "unconfigured"})
	}
	sort.SliceStable(out, func(i, j int) bool { return strings.Compare(out[i].Source, out[j].Source) < 0 })
	return out
}
