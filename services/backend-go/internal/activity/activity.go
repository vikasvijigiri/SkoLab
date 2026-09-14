// Package activity assembles the Home activity feed: a merged, recency-sorted
// stream of things that happened around the requesting researcher —
//
//   - paper_published        : a connected researcher put out a new paper
//   - connection_made        : a new accepted connection
//   - trending               : highly-cited recent work in the user's field, a
//     floor so a user with no connections yet still sees a live feed
//   - tracked_researcher_paper : a researcher this user tracks (decisions/0021,
//     Firestore users/{uid}/tracked_researchers) published something new
//   - citation_received      : this user's own OpenAlex cited_by_count went up
//     since the last time the feed was fetched (decisions/0022)
//   - tracked_topic_activity : new OpenAlex work tagged with a topic this user
//     tracks in Discovery (Firestore users/{uid}/tracked_topics) since the
//     tracked-window cutoff — see trackedTopicActivity.
//   - mention / invite       : client-originated CoLab events (an @mention in
//     ChatTab, a real invite in ShareModal) queued in Firestore
//     users/{uid}/inbox and drained here — see inboxItems.
//
// mention/invite deliberately reuse this same ActivityItem pipeline instead
// of getting their own type (unlike CvShare in lib/types.ts, which predates
// this feed and had no client write path to begin with): the frontend's
// useNotifications.ts already ships real, specific copy for both kinds from a
// previous session, so this is the smaller change and it costs nothing to
// share the sort/merge/limit logic every other source here already gets.
//
// Pure Postgres + Firestore reads plus the pooled OpenAlex client. No LLM, no
// embedding — this is aggregation and sorting, so it lives on the Go edge
// exactly like internal/similarity and internal/author (decisions/0002, 0010,
// 0011). The Firestore reads/writes below reuse the same nil-safe wrapper
// (internal/firestore) author/search.go already uses for global_researchers —
// they degrade to a clean no-op when no Firestore client is wired (e.g. CI),
// exactly like every Postgres-gated source here degrades when db.Pool is nil.
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
	"github.com/skolab/backend-go/internal/firestore"
	"github.com/skolab/backend-go/internal/services/openalex"
)

var oaClient = openalex.New()

// Seams for tests, same pattern as author/search.go's firestoreQueryEqHook.
// Production points them at the real implementations.
var (
	firestoreListDocsHook        = firestore.ListDocs
	firestoreListDocsWithIDsHook = firestore.ListDocsWithIDs
	firestoreGetDocHook          = firestore.GetDoc
	firestoreSetDocHook          = firestore.SetDoc
	firestoreDeleteDocHook       = firestore.DeleteDoc
	fetchAuthorHook              = oaClient.FetchAuthorByID
	fetchWorksByConceptHook      = oaClient.FetchWorksByConcept
)

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
	Type   string  `json:"type"` // paper_published | connection_made | trending | citation_received | tracked_researcher_paper | tracked_topic_activity | mention | invite
	Verb   string  `json:"verb"` // human phrase for the card headline
	TS     string  `json:"ts"`   // RFC3339 / ISO date, drives ordering
	Actor  *Actor  `json:"actor,omitempty"`
	Object *Object `json:"object,omitempty"`
	Href   string  `json:"href"`
	Why    string  `json:"why,omitempty"`
	// Count is a real, backend-computed quantity the frontend's copy names
	// honestly — new citations since the watermark check (citation_received)
	// or new works in a tracked topic since the window cutoff
	// (tracked_topic_activity). Never a guess: absent unless there's an
	// actual number to report.
	Count int `json:"count,omitempty"`
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

	trackedWindow := time.Duration(envInt("ACTIVITY_TRACKED_WINDOW_DAYS", 75)) * 24 * time.Hour

	var (
		mu           sync.Mutex
		papers       []Item
		conns        []Item
		trending     []Item
		tracked      []Item
		trackedTopic []Item
		citations    []Item
		inbox        []Item
		wg           sync.WaitGroup
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

	// Firestore-backed sources — independent of db.Pool (they read
	// users/{uid}/... directly, not Postgres), so they still run for a user
	// whose Postgres row doesn't exist or whose pool is unavailable.
	if userID != "" {
		wg.Add(1)
		go func() {
			defer wg.Done()
			got := trackedResearcherPapers(ctx, userID, trackedWindow)
			mu.Lock()
			tracked = got
			mu.Unlock()
		}()
		wg.Add(1)
		go func() {
			defer wg.Done()
			got := trackedTopicActivity(ctx, userID, trackedWindow)
			mu.Lock()
			trackedTopic = got
			mu.Unlock()
		}()
		wg.Add(1)
		go func() {
			defer wg.Done()
			got := inboxItems(ctx, userID)
			mu.Lock()
			inbox = got
			mu.Unlock()
		}()
	}
	if userID != "" && authorID != "" {
		wg.Add(1)
		go func() {
			defer wg.Done()
			got := citationAlert(ctx, userID, authorID)
			mu.Lock()
			citations = got
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
	// showing up as "trending". `tracked` is added first so a paper from
	// someone the user both tracks and is connected to shows once, as the
	// more specific tracked_researcher_paper kind (decisions/0022's second
	// addendum: the plain paper_published-from-a-connection kind is folded
	// into the tracked one, not kept as a separate duplicate).
	seen := map[string]bool{}
	merged := make([]Item, 0, len(papers)+len(conns)+len(trending)+len(tracked)+len(trackedTopic)+len(citations)+len(inbox))
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
	add(citations)
	add(tracked)
	add(trackedTopic)
	add(inbox)
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

// ── Track feeds notifications (decisions/0021 + decisions/0022) ─────────────

func stringField(d map[string]any, key string) string {
	s, _ := d[key].(string)
	return s
}

// trackedResearcherIDs reads the caller's Track list — a parallel workstream
// writes users/{uid}/tracked_researchers/{authorId} with authorId/name/
// trackedAt fields (decisions/0021); this only ever reads it. Degrades to no
// items (not an error) when Firestore is unavailable or the collection is
// empty/doesn't exist yet, exactly like every other Firestore-backed read in
// this codebase (internal/firestore's own no-op contract).
func trackedResearcherIDs(ctx context.Context, userID string) map[string]string {
	docs, err := firestoreListDocsHook(ctx, "users/"+userID+"/tracked_researchers", envInt("ACTIVITY_MAX_TRACKED", 25))
	if err != nil || len(docs) == 0 {
		return nil
	}
	out := map[string]string{}
	for _, d := range docs {
		id := cleanID(stringField(d, "authorId"))
		if id == "" {
			continue
		}
		out[id] = stringField(d, "name")
	}
	return out
}

// trackedResearcherPapers surfaces new work from researchers the user tracks
// in Discovery — the "hear about it when they publish" half of Track,
// completing the bookmark-then-notify loop decisions/0022 calls out as the
// gap SkoLab's competitors don't close in one place.
func trackedResearcherPapers(ctx context.Context, userID string, window time.Duration) []Item {
	byID := trackedResearcherIDs(ctx, userID)
	if len(byID) == 0 {
		return nil
	}
	cutoff := time.Now().Add(-window)

	var (
		mu    sync.Mutex
		items []Item
		wg    sync.WaitGroup
		sem   = make(chan struct{}, envInt("ACTIVITY_TRACKED_CONCURRENCY", 6))
	)
	for authorID, name := range byID {
		wg.Add(1)
		sem <- struct{}{}
		go func(authorID, name string) {
			defer wg.Done()
			defer func() { <-sem }()

			works, err := oaClient.FetchAuthorWorks(ctx, authorID, "", 4, "publication_date:desc")
			if err != nil {
				return
			}
			for _, w := range works {
				pub := parseDate(w.PublicationDate)
				if pub.IsZero() || pub.Before(cutoff) {
					continue
				}
				wid := cleanID(w.ID)
				if wid == "" || strings.TrimSpace(w.Title) == "" {
					continue
				}
				label := name
				if label == "" {
					for _, a := range w.Authorships {
						if cleanID(a.Author.ID) == authorID {
							label = a.Author.DisplayName
							break
						}
					}
				}
				if label == "" {
					label = "A researcher you track"
				}
				mu.Lock()
				items = append(items, Item{
					ID:   "tracked:" + wid,
					Type: "tracked_researcher_paper",
					Verb: "published a new paper",
					TS:   pub.Format(time.RFC3339),
					Actor: &Actor{
						ID:          authorID,
						DisplayName: label,
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
					Why:  "Tracked researcher",
				})
				mu.Unlock()
			}
		}(authorID, name)
	}
	wg.Wait()
	return items
}

// ── Tracked topics (decisions/0022's topic-follow gap) ──────────────────────

// trackedTopicIDs reads the caller's topic-Track list — a parallel
// workstream writes users/{uid}/tracked_topics/{topicId} with
// topicId/name/trackedAt fields, mirroring trackedResearcherIDs's researcher
// version exactly (see apps/web/src/lib/firebase/tracking.ts). Degrades to no
// items (not an error) when Firestore is unavailable or the collection is
// empty/doesn't exist yet.
func trackedTopicIDs(ctx context.Context, userID string) map[string]string {
	docs, err := firestoreListDocsHook(ctx, "users/"+userID+"/tracked_topics", envInt("ACTIVITY_MAX_TRACKED", 25))
	if err != nil || len(docs) == 0 {
		return nil
	}
	out := map[string]string{}
	for _, d := range docs {
		id := cleanID(stringField(d, "topicId"))
		if id == "" {
			continue
		}
		out[id] = stringField(d, "name")
	}
	return out
}

// trackedTopicActivity surfaces new OpenAlex work tagged with a topic the
// user tracks in Discovery's Topics mode — the topic-follow half of Track,
// alongside trackedResearcherPapers's researcher half. Reuses
// FetchWorksByConcept, the OpenAlex topic-filtered works query already
// defined for this exact shape of lookup (topics.id filter, falling back to
// the legacy concepts.id filter).
//
// Deliberately coarse, same honesty rule as citationAlert: reports a real
// count of new works in the window and the topic's name, never invents which
// specific paper is "the" one driving the count — OpenAlex gives us the list,
// but naming one work as *the* notable one would be an editorial claim this
// backend has no basis for.
func trackedTopicActivity(ctx context.Context, userID string, window time.Duration) []Item {
	byID := trackedTopicIDs(ctx, userID)
	if len(byID) == 0 {
		return nil
	}
	cutoff := time.Now().Add(-window)
	nowYear := time.Now().Year()

	var (
		mu    sync.Mutex
		items []Item
		wg    sync.WaitGroup
		sem   = make(chan struct{}, envInt("ACTIVITY_TRACKED_CONCURRENCY", 6))
	)
	for topicID, name := range byID {
		wg.Add(1)
		sem <- struct{}{}
		go func(topicID, name string) {
			defer wg.Done()
			defer func() { <-sem }()

			works, err := fetchWorksByConceptHook(ctx, topicID, cutoff.Year(), nowYear, 25)
			if err != nil {
				return
			}
			label := name
			if label == "" {
				label = "a topic you follow"
			}
			count := 0
			var latest time.Time
			for _, w := range works {
				pub := parseDate(w.PublicationDate)
				if pub.IsZero() || pub.Before(cutoff) {
					continue
				}
				count++
				if pub.After(latest) {
					latest = pub
				}
			}
			if count == 0 {
				return
			}
			ts := latest
			if ts.IsZero() {
				ts = time.Now()
			}
			mu.Lock()
			items = append(items, Item{
				ID:   "topic:" + topicID + ":" + ts.Format("20060102"),
				Type: "tracked_topic_activity",
				Verb: "new papers in a topic you follow",
				TS:   ts.Format(time.RFC3339),
				Object: &Object{
					Kind:  "topic",
					ID:    topicID,
					Title: label,
				},
				Href:  "/discovery?tab=topics",
				Why:   "Tracked topic",
				Count: count,
			})
			mu.Unlock()
		}(topicID, name)
	}
	wg.Wait()
	return items
}

// ── Inbox (client-originated mention/invite events) ──────────────────────────

// inboxItems reads and drains the caller's users/{uid}/inbox — a lightweight,
// client-writable queue ShareModal (a real invite) and ChatTab (an @mention
// against the project's real member list) append to directly, matching
// decisions/0004's direct-client-write convention (no REST/Go write endpoint
// for these, same as tracking.ts/cvShare.ts).
//
// Unlike every other source in this file, this is a consume-on-read queue,
// not a re-derivable signal: an inbox doc is deleted immediately after being
// read into a response, so a second fetch never redelivers the same
// mention/invite. That trades a small chance of losing an item if the client
// crashes between the read and the delete for avoiding real complexity (an
// ack/delivery-receipt protocol) a best-effort notification queue doesn't
// warrant — this is not a guaranteed-delivery system, and it doesn't claim to
// be one.
func inboxItems(ctx context.Context, userID string) []Item {
	docs, err := firestoreListDocsWithIDsHook(ctx, "users/"+userID+"/inbox", envInt("ACTIVITY_MAX_INBOX", 40))
	if err != nil || len(docs) == 0 {
		return nil
	}
	var items []Item
	for _, d := range docs {
		typ := stringField(d.Data, "type")
		if typ != "mention" && typ != "invite" {
			continue // unknown/malformed doc — skip rather than misreport its kind
		}
		verb := stringField(d.Data, "verb")
		if verb == "" {
			if typ == "invite" {
				verb = "invited you"
			} else {
				verb = "mentioned you"
			}
		}
		ts := stringField(d.Data, "ts")
		if ts == "" {
			ts = time.Now().Format(time.RFC3339)
		}
		var actor *Actor
		if a, ok := d.Data["actor"].(map[string]any); ok {
			id := stringField(a, "id")
			name := stringField(a, "display_name")
			if id != "" || name != "" {
				actor = &Actor{ID: id, DisplayName: name}
			}
		}
		items = append(items, Item{
			ID:    "inbox:" + d.ID,
			Type:  typ,
			Verb:  verb,
			TS:    ts,
			Actor: actor,
			Href:  stringField(d.Data, "href"),
			Why:   stringField(d.Data, "why"),
		})
		// Best-effort delete: an error here just means the item may be
		// redelivered on the next fetch, not a request failure.
		_ = firestoreDeleteDocHook(ctx, "users/"+userID+"/inbox", d.ID)
	}
	return items
}

// ── Citation watermark (decisions/0022) ──────────────────────────────────────

// notificationStateCollection is the per-user watermark doc's parent
// collection: users/{uid}/notification_state, with a single fixed doc id
// ("state") since there is exactly one watermark per user today.
func notificationStateCollection(userID string) string {
	return "users/" + userID + "/notification_state"
}

func intField(d map[string]any, key string) int {
	switch n := d[key].(type) {
	case int64:
		return int(n)
	case int:
		return n
	case float64:
		return int(n)
	}
	return 0
}

// citationAlert compares the user's current OpenAlex cited_by_count — the
// same value already computed for GET /search_author / the web's
// /author/[id] page — against a stored watermark, and emits exactly one
// citation_received item when it has gone up since the last check, then
// advances the watermark.
//
// Deliberately coarse (decisions/0022: "a coarse signal is honest and
// sufficient"): this reports *that* new citations arrived and *how many*,
// never which specific paper is responsible, since OpenAlex has no cheap way
// to answer that. On the very first check for a user (no watermark yet) this
// only seeds the watermark and emits nothing — otherwise every pre-existing
// citation would be misreported as "new since you last checked."
func citationAlert(ctx context.Context, userID, authorID string) []Item {
	author, err := fetchAuthorHook(ctx, authorID)
	if err != nil || author == nil {
		return nil
	}
	current := author.CitedByCount

	collection := notificationStateCollection(userID)
	doc, found, err := firestoreGetDocHook(ctx, collection, "state")
	if err != nil {
		return nil
	}
	if !found {
		_ = firestoreSetDocHook(ctx, collection, "state", map[string]any{"lastSeenCitationCount": current})
		return nil
	}

	last := intField(doc, "lastSeenCitationCount")
	if current <= last {
		return nil
	}
	delta := current - last
	_ = firestoreSetDocHook(ctx, collection, "state", map[string]any{"lastSeenCitationCount": current})

	verb := "received a new citation"
	if delta > 1 {
		verb = "received new citations"
	}
	return []Item{{
		ID:    "citation:" + authorID + ":" + strconv.Itoa(current),
		Type:  "citation_received",
		Verb:  verb,
		TS:    time.Now().Format(time.RFC3339),
		Href:  "/author/" + authorID,
		Why:   strconv.Itoa(delta) + " new citation(s) since last checked",
		Count: delta,
	}}
}
