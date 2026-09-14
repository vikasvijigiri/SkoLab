package author

import (
	"context"
	"net/http"
	"strings"
	"sync"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/skolab/backend-go/internal/services/openalex"
)

// GET /api/v1/coach_pulse — the Home page's "Since You Were Here" panel
// (replaces the old static Daily Brief, 2026-09-15). Three real, honestly-
// computed signals, each independent and best-effort (a failed or empty one
// is just a nil field, never an error for the whole response):
//
//  1. Impact — recent citations on the caller's own highest-cited papers.
//     Deliberately just a count + which paper: OpenAlex gives no citation
//     *stance* (supporting/contesting) without reading full text, and this
//     app doesn't fabricate an unexplainable claim it can't back (decisions/
//     0021's honesty rule) — an earlier design draft's "2 build on your
//     method, 1 contests" line was illustrative copy, not a real field.
//  2. Tracked network — new work from the caller's explicitly-tracked
//     researchers (TrackedResearcher, Firestore, client-owned — passed in
//     as tracked_author_ids since this Go service has no direct Firestore
//     read for that collection).
//  3. Worth tracking — one similar researcher not already on that list,
//     reusing fetchSimilarAuthors (search.go) rather than a second
//     similarity implementation.
//
// All three run concurrently — this is a read-only, unauthenticated
// aggregation over public OpenAlex data (same trust level as
// author_suggestions/network_collaborators), not gated behind VerifyUser.
const pulseLookbackDays = 21

type CoachPulseResponse struct {
	Impact          *PulseImpact          `json:"impact"`
	TrackedActivity *PulseTrackedActivity `json:"tracked_activity"`
	WorthTracking   *PulseWorthTracking   `json:"worth_tracking"`
}

type PulseImpact struct {
	NewCitations int    `json:"new_citations"`
	PaperTitle   string `json:"paper_title"`
	PaperID      string `json:"paper_id"`
}

type PulseTrackedActivity struct {
	AuthorID    string `json:"author_id"`
	AuthorName  string `json:"author_name"`
	WorkTitle   string `json:"work_title"`
	WorkID      string `json:"work_id"`
	PublishedAt string `json:"published_at"`
}

type PulseWorthTracking struct {
	AuthorID    string `json:"author_id"`
	AuthorName  string `json:"author_name"`
	Institution string `json:"institution"`
	WorksCount  int    `json:"works_count"`
}

func GetCoachPulse(c *gin.Context) {
	authorID := strings.TrimSpace(c.Query("author_id"))
	field := strings.TrimSpace(c.Query("field"))
	trackedIDs := splitCSV(c.Query("tracked_author_ids"))

	ctx := c.Request.Context()
	since := time.Now().AddDate(0, 0, -pulseLookbackDays).Format("2006-01-02")

	var impact *PulseImpact
	var trackedActivity *PulseTrackedActivity
	var worthTracking *PulseWorthTracking

	var wg sync.WaitGroup
	if authorID != "" {
		wg.Add(1)
		go func() {
			defer wg.Done()
			impact = computePulseImpact(ctx, authorID, since)
		}()
	}
	if len(trackedIDs) > 0 {
		wg.Add(1)
		go func() {
			defer wg.Done()
			trackedActivity = computeTrackedActivity(ctx, trackedIDs, since)
		}()
	}
	if authorID != "" && field != "" {
		wg.Add(1)
		go func() {
			defer wg.Done()
			worthTracking = computeWorthTracking(ctx, authorID, field, trackedIDs)
		}()
	}
	wg.Wait()

	c.JSON(http.StatusOK, CoachPulseResponse{
		Impact:          impact,
		TrackedActivity: trackedActivity,
		WorthTracking:   worthTracking,
	})
}

// computePulseImpact finds the caller's own top-cited papers, then looks for
// works citing them published within the lookback window. ReferencedWorks on
// each citing result is how a hit is attributed back to a specific paper of
// the caller's — a batched cites:W1|W2|W3 filter can't tell you which one on
// its own.
func computePulseImpact(ctx context.Context, authorID, since string) *PulseImpact {
	topWorks, err := openAlexClient.FetchAuthorWorks(ctx, authorID, "", 5, "cited_by_count:desc")
	if err != nil || len(topWorks) == 0 {
		return nil
	}
	topIDs := make([]string, 0, len(topWorks))
	byID := make(map[string]openalex.Work, len(topWorks))
	for _, w := range topWorks {
		topIDs = append(topIDs, w.ID)
		byID[w.ID] = w
	}

	citing, err := openAlexClient.FetchCitingWorks(ctx, topIDs, since, 25)
	if err != nil || len(citing) == 0 {
		return nil
	}

	hits := make(map[string]int, len(topIDs))
	for _, cw := range citing {
		for _, ref := range cw.ReferencedWorks {
			if _, ok := byID[ref]; ok {
				hits[ref]++
			}
		}
	}
	var best string
	var bestCount int
	for id, n := range hits {
		if n > bestCount {
			best, bestCount = id, n
		}
	}
	if best == "" {
		return nil
	}
	w := byID[best]
	return &PulseImpact{NewCitations: bestCount, PaperTitle: w.Title, PaperID: w.ID}
}

// computeTrackedActivity returns the single most recent work published by
// any of trackedIDs within the lookback window.
func computeTrackedActivity(ctx context.Context, trackedIDs []string, since string) *PulseTrackedActivity {
	works, err := openAlexClient.FetchWorksByAuthorIDs(ctx, trackedIDs, since, 5)
	if err != nil || len(works) == 0 {
		return nil
	}
	tracked := make(map[string]bool, len(trackedIDs))
	for _, id := range trackedIDs {
		tracked[cleanOpenAlexID(id)] = true
	}

	w := works[0]
	var authorID, authorName string
	for _, a := range w.Authorships {
		if tracked[cleanOpenAlexID(a.Author.ID)] {
			authorID, authorName = a.Author.ID, a.Author.DisplayName
			break
		}
	}
	if authorID == "" {
		return nil
	}
	return &PulseTrackedActivity{
		AuthorID:    authorID,
		AuthorName:  authorName,
		WorkTitle:   w.Title,
		WorkID:      w.ID,
		PublishedAt: w.PublicationDate,
	}
}

// computeWorthTracking reuses fetchSimilarAuthors (search.go) rather than a
// second similarity implementation, and just filters its top few candidates
// down to the first one not already tracked.
func computeWorthTracking(ctx context.Context, authorID, field string, trackedIDs []string) *PulseWorthTracking {
	excluded := make(map[string]bool, len(trackedIDs)+1)
	excluded[cleanOpenAlexID(authorID)] = true
	for _, id := range trackedIDs {
		excluded[cleanOpenAlexID(id)] = true
	}

	for _, cand := range fetchSimilarAuthors(ctx, field, authorID) {
		if !excluded[cleanOpenAlexID(cand.ID)] {
			return &PulseWorthTracking{
				AuthorID:    cand.ID,
				AuthorName:  cand.DisplayName,
				Institution: cand.Institution,
				WorksCount:  intDeref(cand.WorksCount),
			}
		}
	}
	return nil
}

func cleanOpenAlexID(id string) string {
	id = strings.TrimSpace(id)
	if i := strings.LastIndex(id, "/"); i >= 0 {
		id = id[i+1:]
	}
	return strings.ToUpper(id)
}
