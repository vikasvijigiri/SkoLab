import { apiRequest, ApiError } from "./client";
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

export const getCitationHeatmap = (authorId: string) =>
  apiRequest<CitationHeatmap>("/citation_heatmap", { params: { author_id: authorId } });

export const getJournalAdvisor = (authorId: string) =>
  apiRequest<JournalRecommendation[]>("/journal_advisor", { params: { author_id: authorId } });

export const getMatchGrants = (authorId: string) =>
  apiRequest<GrantMatch[]>("/match_grants", { params: { author_id: authorId } });

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
  const res = await fetch(`/api/openalex/works/${encodeURIComponent(id)}`);
  if (!res.ok) throw new ApiError(res.status, `Couldn't load this paper (HTTP ${res.status}).`);
  return (await res.json()) as OpenAlexWork;
};
