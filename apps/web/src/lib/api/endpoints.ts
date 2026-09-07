import { apiRequest, ApiError } from "./client";
import { shortOpenAlexId } from "@/lib/utils";
import type {
  AuthorSuggestion,
  AuthorResponse,
  NetworkCollaborator,
  CitationHeatmap,
  JournalRecommendation,
  GrantMatch,
  DailyFeedItem,
  Conjecture,
  IndustryOpportunity,
  PaperIntelligence,
  LeaderboardEntry,
  OpenAlexWork,
  BreakthroughPrediction,
  NexusChatPaper,
  NexusMessage,
  SimilarPaper,
  SimilarResearcher,
  SimilarResult,
  ActivityFeedResult,
  ScienceNewsItem,
  OpenAlexTaxon,
  OpenAlexAuthorHit,
} from "@/lib/types";

// ---- Authors / Search / Discovery -----------------------------------------

export const getAuthorSuggestions = (query: string) =>
  apiRequest<AuthorSuggestion[]>("/api/v1/author_suggestions", { params: { query } });

export const getLeaderboard = (field = "all") =>
  apiRequest<LeaderboardEntry[]>(`/api/v1/leaderboard/${encodeURIComponent(field)}`);

export const searchAuthor = (name: string, id?: string, focus?: string) =>
  apiRequest<AuthorResponse>("/search_author", { params: { name, id, focus } });

export const refreshAuthor = (name: string, id?: string) =>
  apiRequest<AuthorResponse>("/refresh_author", { params: { name, id } });

export const getNetworkCollaborators = (authorId: string, field?: string, name?: string, limit = 50) =>
  apiRequest<NetworkCollaborator[]>("/network_collaborators", {
    params: { author_id: authorId, field, name, limit },
  });

// citation_heatmap is only registered on the Go gateway under /api/v1 (no
// bare fallback, unlike search_author/refresh_author/network_collaborators
// below); journal_advisor and match_grants never reached Go's own routes at
// all -- they're Python-only, forwarded by the gateway's catch-all NoRoute
// proxy with the request path preserved as-is, and Python has them
// registered under /api/v1 too (app.include_router(api_router,
// prefix="/api/v1")). A bare path 404s at both hops. Confirmed live: hitting
// an author profile page threw two real 404s in the browser console before
// this fix.
export const getCitationHeatmap = (authorId: string) =>
  apiRequest<CitationHeatmap>("/api/v1/citation_heatmap", { params: { author_id: authorId } });

export const getJournalAdvisor = (authorId: string) =>
  apiRequest<JournalRecommendation[]>("/api/v1/journal_advisor", { params: { author_id: authorId } });

// ---- Similarity engine (Go gateway — pgvector kNN + graph blend, no LLM) ----

export const getSimilarPapers = (workId: string, limit = 8) =>
  apiRequest<SimilarResult<SimilarPaper>>("/api/v1/similar_papers", {
    params: { work_id: workId, limit },
  });

export const getSimilarResearchers = (
  authorId: string,
  opts: { userId?: string; limit?: number } = {},
) =>
  apiRequest<SimilarResult<SimilarResearcher>>("/api/v1/similar_researchers", {
    params: { author_id: authorId, user_id: opts.userId, limit: opts.limit ?? 8 },
  });

export const getMatchGrants = (authorId: string) =>
  apiRequest<GrantMatch[]>("/api/v1/match_grants", { params: { author_id: authorId } });

// ---- Home activity feed (Go gateway — merged recency stream, no LLM) --------
// Connected researchers' new papers + newly accepted connections + a
// highly-cited-recent field floor. `degraded` when the network is unreadable.
export const getActivityFeed = (
  opts: { authorId?: string; userId?: string; limit?: number } = {},
) =>
  apiRequest<ActivityFeedResult>("/api/v1/activity_feed", {
    params: { author_id: opts.authorId, user_id: opts.userId, limit: opts.limit ?? 20 },
  });

// Curated science-news headlines (Quanta, Phys.org, ScienceDaily, Nature) —
// RSS aggregation on the gateway, cached 45 min. `field` biases toward the
// user's area. Always link out.
export const getScienceNews = (field?: string, limit = 6) =>
  apiRequest<{ items: ScienceNewsItem[] }>("/api/v1/science_news", {
    params: { field, limit },
  });

// ---- Home / Feed ------------------------------------------------------------

export const getDailyFeed = (authorId?: string, queryFallback?: string) =>
  apiRequest<DailyFeedItem[]>("/api/v1/daily_feed", {
    params: { author_id: authorId, query_fallback: queryFallback },
  });

// Owner-scoped on the backend — needs the caller's Firebase ID token (401
// without, 403 if the token's user is not that OpenAlex author).
export const dismissDailyFeedItem = (idToken: string | null, authorId: string, workId: string) =>
  apiRequest<{ success: boolean }>("/api/v1/daily_feed/dismiss", {
    method: "POST",
    idToken: idToken ?? undefined,
    body: { author_id: authorId, work_id: workId },
  });

export const getDailyConjecture = (authorId?: string, name?: string) =>
  apiRequest<Conjecture>("/api/v1/daily_conjecture", { params: { author_id: authorId, name } });

export const getIndustryOpportunities = (focus = "AI", name?: string) =>
  apiRequest<IndustryOpportunity[]>("/api/v1/industry_opportunities", { params: { focus, name } });

// ---- Papers ------------------------------------------------------------------

export const analyzePaper = (opts: { title?: string; doi?: string; openalexId?: string }) =>
  apiRequest<PaperIntelligence>("/api/v1/analyze_paper", {
    params: { title: opts.title, doi: opts.doi, openalex_id: opts.openalexId },
  });

// ---- Recommendations (project/task collaborator autocomplete) ---------------
// Served by the Go gateway (internal/recommendation). Auth is transitional:
// the token is optional today, mandatory after the Android client attaches one
// (decisions/0008) — pass it now so the web client needs no later change.

export const logPeerInvite = (
  idToken: string | null,
  userId: string,
  peer: { email?: string; phone?: string; uid?: string },
) =>
  apiRequest<{ success: boolean }>("/api/v1/recommendations/peers/invite", {
    method: "POST",
    idToken: idToken ?? undefined,
    body: { user_id: userId, peer_email: peer.email, peer_phone: peer.phone, peer_uid: peer.uid },
  });

// ---- Users (Firebase-auth protected, Go gateway) -----------------------------

export const syncUserProfile = (idToken: string, uid: string, name: string, discipline?: string) =>
  apiRequest<{ status: string; uid: string }>("/api/v1/users/profile/sync", {
    method: "POST",
    idToken,
    body: { uid, name, discipline },
  });

export const deleteUserAccount = (idToken: string, userId: string) =>
  apiRequest<{ status: string; detail: string }>(`/api/v1/users/${userId}`, {
    method: "DELETE",
    idToken,
  });

// ---- Horizon / Nexus (discovery engine) ------------------------------------

export const getHorizonPrediction = (field: string, focusArea?: string, authorId?: string) =>
  apiRequest<BreakthroughPrediction>("/api/v1/discovery/predict", {
    method: "POST",
    body: { field, focus_area: focusArea, author_id: authorId },
  });

export const nexusChat = (papers: NexusChatPaper[], messages: NexusMessage[]) =>
  apiRequest<{ content: string }>("/api/v1/discovery/nexus-chat", {
    method: "POST",
    body: { papers, messages },
  });

/**
 * OpenAlex works via the same-origin Next route handler (`app/api/openalex/works`),
 * NOT the Go gateway — so it uses `fetch` directly, not `apiRequest`.
 */
export const openAlexWorks = async (opts: { q?: string; focus?: string } = {}): Promise<OpenAlexWork[]> => {
  const params = new URLSearchParams();
  if (opts.q) params.set("q", opts.q);
  if (opts.focus) params.set("focus", opts.focus);
  const qs = params.toString();
  const res = await fetch(`/api/openalex/works${qs ? `?${qs}` : ""}`);
  if (!res.ok) throw new ApiError(res.status, `OpenAlex works request failed with ${res.status}`);
  const data = await res.json();
  return Array.isArray(data) ? data : [];
};

export const openAlexWorkById = async (id: string): Promise<OpenAlexWork> => {
  // Tolerate a full URL id ("https://openalex.org/W…") from a stale link,
  // bookmark, or hand-typed URL — OpenAlex 400s on the URL-encoded form.
  const res = await fetch(`/api/openalex/works/${encodeURIComponent(shortOpenAlexId(id))}`);
  if (!res.ok) throw new ApiError(res.status, `Couldn't load this paper (HTTP ${res.status}).`);
  return (await res.json()) as OpenAlexWork;
};

// ---- OpenAlex taxonomy + author match (click-only pickers) ------------------

export const openAlexTaxonomy = async (
  kind: "fields" | "subfields" | "topics",
  parent?: string,
): Promise<OpenAlexTaxon[]> => {
  const params = new URLSearchParams({ kind });
  if (parent) params.set("parent", parent);
  const res = await fetch(`/api/openalex/taxonomy?${params.toString()}`);
  if (!res.ok) throw new ApiError(res.status, `Taxonomy request failed (HTTP ${res.status}).`);
  const data = await res.json();
  return Array.isArray(data) ? data : [];
};

export const openAlexAuthorsByName = async (q: string): Promise<OpenAlexAuthorHit[]> => {
  if (!q.trim()) return [];
  const res = await fetch(`/api/openalex/authors?q=${encodeURIComponent(q.trim())}`);
  if (!res.ok) throw new ApiError(res.status, `Author search failed (HTTP ${res.status}).`);
  const data = await res.json();
  return Array.isArray(data) ? data : [];
};

export type TaxonLevel = "field" | "subfield" | "topic";

export const openAlexAuthorsByTaxon = async (
  level: TaxonLevel,
  id: string,
): Promise<OpenAlexAuthorHit[]> => {
  const res = await fetch(`/api/openalex/authors?${level}=${encodeURIComponent(id)}`);
  if (!res.ok) throw new ApiError(res.status, `Discovery request failed (HTTP ${res.status}).`);
  const data = await res.json();
  return Array.isArray(data) ? data : [];
};

export const openAlexWorksByTaxon = async (
  level: TaxonLevel,
  id: string,
): Promise<OpenAlexWork[]> => {
  const res = await fetch(`/api/openalex/works?${level}=${encodeURIComponent(id)}`);
  if (!res.ok) throw new ApiError(res.status, `Discovery request failed (HTTP ${res.status}).`);
  const data = await res.json();
  return Array.isArray(data) ? data : [];
};
