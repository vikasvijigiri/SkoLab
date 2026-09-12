// Package activity assembles the Home activity feed: a merged, recency-sorted
// stream of things that happened around the requesting researcher —
//
//   - paper_published : a connected researcher put out a new paper
//   - connection_made : a new accepted connection
//   - trending        : highly-cited recent work in the user's field, a floor
//     so a user with no connections yet still sees a live feed
//
// Pure Postgres reads plus the pooled OpenAlex client. No LLM, no embedding —
// this is aggregation and sorting, so it lives on the Go edge exactly like
// internal/similarity and internal/author (decisions/0002, 0010, 0011).
package activity

import (
	"context"
	"net/http"
	"os"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/skolab/backend-go/internal/db"
	"github.com/skolab/backend-go/internal/services/openalex"
)

var oaClient = openalex.New()

// ── response shapes ───────────────────────────────────────────────────────────

// Actor is the researcher a feed item is about (absent on trending items).
type Actor struct {
	ID          string `json:"id"` // OpenAlex author id, cleaned ("A123…")
	DisplayName string `json:"display_name"`
	Institution string `json:"institution,omitempty"`
}

// Object is the thing the item points at — today always a work.
type Object struct {
	Kind      string   `json:"kind"` // "work"
	ID        string   `json:"id"`
	Title     string   `json:"title"`
	Authors   []string `json:"authors,omitempty"`
	Year      int      `json:"year,omitempty"`
	Venue     string   `json:"venue,omitempty"`
	Citations int      `json:"citations,omitempty"`
}

// Item is one row in the feed.
type Item struct {
	ID     string  `json:"id"`   // stable de-dupe key
	Type   string  `json:"type"` // paper_published | connection_made | trending
	Verb   string  `json:"verb"` // human phrase for the card headline
	TS     string  `json:"ts"`   // RFC3339 / ISO date, drives ordering
	Actor  *Actor  `json:"actor,omitempty"`
	Object *Object `json:"object,omitempty"`
	Href   string  `json:"href"`
	Why    string  `json:"why,omitempty"`
}

// FeedResponse mirrors similarity.SimilarResearchersResponse's shape:
// results + a degraded flag the web uses to caption a thin feed.
type FeedResponse struct {
	Items    []Item `json:"items"`
	Degraded bool   `json:"degraded"`
}

// ── helpers ───────────────────────────────────────────────────────────────────

func envInt(key string, def int) int {
	if v := os.Getenv(key); v != "" {
		if n, err := strconv.Atoi(v); err == nil && n > 0 {
			return n
		}
	}
	return def
}

func clampLimit(raw string, def, max int) int {
	n, err := strconv.Atoi(raw)
	if err != nil || n <= 0 {
		return def
	}
	if n > max {
		return max
	}
	return n
}

// cleanID normalises an OpenAlex id to the bare "A123" / "W123" form.
// openalex.cleanID is package-private, so this mirrors it.
func cleanID(raw string) string {
	s := strings.TrimSpace(raw)
	if i := strings.LastIndex(s, "/"); i >= 0 {
		s = s[i+1:]
	}
	return strings.ToUpper(s)
}

func authorNames(as []openalex.Authorship, n int) []string {
	out := make([]string, 0, n)
	for _, a := range as {
		if len(out) >= n {
			break
		}
		if name := strings.TrimSpace(a.Author.DisplayName); name != "" {
			out = append(out, name)
		}
	}
	return out
}

// parseDate accepts OpenAlex's "2026-08-14" (publication_date) and returns the
// zero time when it is empty or malformed.
func parseDate(s string) time.Time {
	if s == "" {
		return time.Time{}
	}
	t, err := time.Parse("2006-01-02", s)
	if err != nil {
		return time.Time{}
	}
	return t
}

// ── GET /api/v1/activity_feed ─────────────────────────────────────────────────

// GetActivityFeed handles GET /api/v1/activity_feed?user_id=&author_id=&limit=.
//
// user_id  — the requesting user's users.id, used to read their connections.
// author_id — the user's own OpenAlex id, used to pick a field for the
//
//	trending floor. Either may be absent; the feed degrades, never 500s.
func GetActivityFeed(c *gin.Context) {
	ctx := c.Request.Context()
	userID := strings.TrimSpace(c.Query("user_id"))
	// The route allows anonymous callers (auth.VerifyUserOptional) so the
	// public trending floor still works with no token, but a `user_id`
	// pulls that user's connections/papers -- verify it actually belongs
	// to whoever is asking rather than serving anyone's feed to anyone
	// who supplies (or guesses) their id.
	if userID != "" && userID != c.GetString("user_id") {
		c.JSON(http.StatusForbidden, gin.H{"error": "Forbidden: you may only view your own activity feed."})
		return
	}
	authorID := cleanID(c.Query("author_id"))
	limit := clampLimit(c.Query("limit"), 20, 40)

	peerWindow := time.Duration(envInt("ACTIVITY_PAPER_WINDOW_DAYS", 75)) * 24 * time.Hour
	connWindow := time.Duration(envInt("ACTIVITY_CONNECTION_WINDOW_DAYS", 30)) * 24 * time.Hour

	var (
		mu       sync.Mutex
		papers   []Item
		conns    []Item
		trending []Item
		wg       sync.WaitGroup
	)

	if userID != "" && db.Pool != nil {
		wg.Add(2)
		go func() {
			defer wg.Done()
			got := peerPublications(ctx, userID, peerWindow)
			mu.Lock()
			papers = got
			mu.Unlock()
		}()
		go func() {
			defer wg.Done()
			got := connectionEvents(ctx, userID, connWindow)
			mu.Lock()
			conns = got
			mu.Unlock()
		}()
	}

	wg.Add(1)
	go func() {
		defer wg.Done()
		got := fieldTrending(ctx, userID, authorID)
		mu.Lock()
		trending = got
		mu.Unlock()
	}()

	wg.Wait()

	// De-dupe by object work id; a real "peer published" beats the same paper
	// showing up as "trending".
	seen := map[string]bool{}
	merged := make([]Item, 0, len(papers)+len(conns)+len(trending))
	add := func(items []Item) {
		for _, it := range items {
			key := it.ID
			if it.Object != nil && it.Object.ID != "" {
				key = it.Object.ID
			}
			if seen[key] {
				continue
			}
			seen[key] = true
			merged = append(merged, it)
		}
	}
	add(papers)
	add(conns)
	add(trending)

	sort.SliceStable(merged, func(i, j int) bool {
		return merged[i].TS > merged[j].TS // ISO strings sort chronologically
	})
	if len(merged) > limit {
		merged = merged[:limit]
	}

	// Degraded when we could not read the user's network at all and the feed is
	// carried entirely by the trending floor.
	degraded := (userID == "" || db.Pool == nil) || (len(papers) == 0 && len(conns) == 0)

	c.JSON(http.StatusOK, FeedResponse{Items: merged, Degraded: degraded})
}

// connectedAuthorIDs returns the OpenAlex ids of the users the requester is
// connected to — user_circles plus accepted connections — capped so the peer
// fan-out to OpenAlex stays bounded. Mirrors similarity.excludedPeers.
func connectedAuthorIDs(ctx context.Context, userID string, maxPeers int) []string {
	// maxPeers is inlined (env-derived int, never user input) — the pool runs
	// QueryExecModeExec, so keeping LIMIT as a literal avoids any parameter
	// text-formatting quirk, matching recommendation.go's style.
	rows, err := db.Pool.Query(ctx, `
		SELECT DISTINCT oid FROM (
			SELECT u.openalex_id AS oid
			FROM user_circles uc
			JOIN users u ON u.id = uc.peer_id
			WHERE uc.user_id = $1 AND u.openalex_id IS NOT NULL AND u.openalex_id <> ''
			UNION
			SELECT u.openalex_id AS oid
			FROM connections c
			JOIN users u ON u.id = c.connected_user_id
			WHERE c.user_id = $1 AND c.status = 'accepted'
			  AND u.openalex_id IS NOT NULL AND u.openalex_id <> ''
		) s
		LIMIT `+strconv.Itoa(maxPeers), userID)
	if err != nil {
		return nil
	}
	defer rows.Close()
	out := []string{}
	for rows.Next() {
		var oid string
		if rows.Scan(&oid) == nil {
			if c := cleanID(oid); c != "" {
				out = append(out, c)
			}
		}
	}
	return out
}

// profileMeta is the cached display name + institution for an OpenAlex id.
type profileMeta struct {
	name        string
	institution string
	field       string
}

func profileMetaFor(ctx context.Context, ids []string) map[string]profileMeta {
	out := map[string]profileMeta{}
	if len(ids) == 0 {
		return out
	}
	rows, err := db.Pool.Query(ctx, `
		SELECT openalex_id, display_name, COALESCE(institution, ''), COALESCE(field_of_study, '')
		FROM researcher_profiles
		WHERE openalex_id = ANY($1)
	`, ids)
	if err != nil {
		return out
	}
	defer rows.Close()
	for rows.Next() {
		var id, name, inst, field string
		if rows.Scan(&id, &name, &inst, &field) == nil {
			out[cleanID(id)] = profileMeta{name: name, institution: inst, field: field}
		}
	}
	return out
}

// peerPublications fetches each connected researcher's most recent works and
// keeps the ones published inside the window.
func peerPublications(ctx context.Context, userID string, window time.Duration) []Item {
	ids := connectedAuthorIDs(ctx, userID, envInt("ACTIVITY_MAX_PEERS", 25))
	if len(ids) == 0 {
		return nil
	}
	meta := profileMetaFor(ctx, ids)
	cutoff := time.Now().Add(-window)

	var (
		mu    sync.Mutex
		items []Item
		wg    sync.WaitGroup
		sem   = make(chan struct{}, envInt("ACTIVITY_PEER_CONCURRENCY", 6))
	)
	for _, id := range ids {
		wg.Add(1)
		sem <- struct{}{}
		go func(authorID string) {
			defer wg.Done()
			defer func() { <-sem }()

			works, err := oaClient.FetchAuthorWorks(ctx, authorID, "", 4, "publication_date:desc")
			if err != nil {
				return
			}
			pm := meta[authorID]
			for _, w := range works {
				pub := parseDate(w.PublicationDate)
				if pub.IsZero() || pub.Before(cutoff) {
					continue
				}
				wid := cleanID(w.ID)
				if wid == "" || strings.TrimSpace(w.Title) == "" {
					continue
				}
				name := pm.name
				if name == "" {
					// Fall back to this author's label on the work itself.
					for _, a := range w.Authorships {
						if cleanID(a.Author.ID) == authorID {
							name = a.Author.DisplayName
							break
						}
					}
				}
				if name == "" {
					name = "A researcher you follow"
				}
				why := "In your network"
				if pm.institution != "" {
					why = pm.institution
				}
				mu.Lock()
				items = append(items, Item{
					ID:   "pub:" + wid,
					Type: "paper_published",
					Verb: "published a new paper",
					TS:   pub.Format(time.RFC3339),
					Actor: &Actor{
						ID:          authorID,
						DisplayName: name,
						Institution: pm.institution,
					},
					Object: &Object{
						Kind:      "work",
						ID:        wid,
						Title:     w.Title,
						Authors:   authorNames(w.Authorships, 3),
						Year:      w.PublicationYear,
						Venue:     w.PrimaryLocation.Source.DisplayName,
						Citations: w.CitedByCount,
					},
					Href: "/paper/" + wid,
					Why:  why,
				})
				mu.Unlock()
			}
		}(id)
	}
	wg.Wait()
	return items
}

// connectionEvents surfaces recently accepted connections.
func connectionEvents(ctx context.Context, userID string, window time.Duration) []Item {
	cutoff := time.Now().Add(-window)
	rows, err := db.Pool.Query(ctx, `
		SELECT COALESCE(u.openalex_id, ''), COALESCE(u.display_name, ''), c.created_at
		FROM connections c
		JOIN users u ON u.id = c.connected_user_id
		WHERE c.user_id = $1 AND c.status = 'accepted' AND c.created_at > $2
		ORDER BY c.created_at DESC
		LIMIT 8
	`, userID, cutoff)
	if err != nil {
		return nil
	}
	defer rows.Close()

	items := []Item{}
	for rows.Next() {
		var oid, name string
		var ts time.Time
		if rows.Scan(&oid, &name, &ts) != nil {
			continue
		}
		if name == "" {
			name = "A new connection"
		}
		href := "/home"
		actorID := cleanID(oid)
		if actorID != "" {
			href = "/author/" + actorID
		}
		items = append(items, Item{
			ID:    "conn:" + actorID + ":" + ts.Format("20060102"),
			Type:  "connection_made",
			Verb:  "is now connected with you",
			TS:    ts.Format(time.RFC3339),
			Actor: &Actor{ID: actorID, DisplayName: name},
			Href:  href,
			Why:   "New connection",
		})
	}
	return items
}

// fieldTrending is the cold-start floor: highly-cited recent work in the user's
// field. Field name comes from the cached profile (no LLM, no author fetch when
// the profile is warm); falls back to the OpenAlex author record only if the
// profile is cold.
func fieldTrending(ctx context.Context, userID, authorID string) []Item {
	field := ""
	if db.Pool != nil {
		if authorID != "" {
			if m := profileMetaFor(ctx, []string{authorID}); m[authorID].field != "" {
				field = m[authorID].field
			}
		}
		if field == "" && userID != "" {
			var f *string
			if err := db.Pool.QueryRow(ctx,
				`SELECT research_focus FROM users WHERE id = $1`, userID).Scan(&f); err == nil && f != nil {
				field = strings.TrimSpace(*f)
			}
		}
	}
	if field == "" && authorID != "" {
		if a, err := oaClient.FetchAuthorByID(ctx, authorID); err == nil && a != nil {
			if f, _ := openalex.ExtractFieldAndExpertise(a); f != "" {
				field = f
			}
		}
	}
	if field == "" {
		return nil
	}

	works, err := oaClient.SearchWorks(ctx, field, 12, "publication_year:desc,cited_by_count:desc")
	if err != nil {
		return nil
	}
	nowYear := time.Now().Year()
	items := []Item{}
	for _, w := range works {
		if w.PublicationYear < nowYear-1 {
			continue
		}
		wid := cleanID(w.ID)
		if wid == "" || strings.TrimSpace(w.Title) == "" || w.CitedByCount < 1 {
			continue
		}
		ts := w.PublicationDate
		if ts == "" {
			ts = strconv.Itoa(w.PublicationYear) + "-01-01"
		}
		items = append(items, Item{
			ID:   "trend:" + wid,
			Type: "trending",
			Verb: "is gaining attention in " + field,
			TS:   parseDate(ts).Format(time.RFC3339),
			Object: &Object{
				Kind:      "work",
				ID:        wid,
				Title:     w.Title,
				Authors:   authorNames(w.Authorships, 3),
				Year:      w.PublicationYear,
				Venue:     w.PrimaryLocation.Source.DisplayName,
				Citations: w.CitedByCount,
			},
			Href: "/paper/" + wid,
			Why:  strconv.Itoa(w.CitedByCount) + " citations already",
		})
		if len(items) >= 6 {
			break
		}
	}
	return items
}
