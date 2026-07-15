import { apiRequest } from "./client";
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
  PeerRecommendation,
  LeaderboardEntry,
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

export const resolveAuthorEmail = (name: string, institution?: string) =>
  apiRequest<{ name: string; email: string; institution: string }>("/api/v1/resolve_email", {
    params: { name, institution },
  });

// ---- Home / Feed ------------------------------------------------------------

export const getDailyFeed = (authorId?: string, queryFallback?: string) =>
  apiRequest<DailyFeedItem[]>("/api/v1/daily_feed", {
    params: { author_id: authorId, query_fallback: queryFallback },
  });

export const dismissDailyFeedItem = (authorId: string, workId: string) =>
  apiRequest<{ success: boolean }>("/api/v1/daily_feed/dismiss", {
    method: "POST",
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

export const getPeerRecommendations = (query: string, userId: string) =>
  apiRequest<PeerRecommendation[]>("/api/v1/recommendations/peers", {
    params: { query, user_id: userId },
  });

export const checkRegisteredPeers = (emails: string[], phones: string[]) =>
  apiRequest<{ registered_emails: string[]; registered_phones: string[] }>(
    "/api/v1/recommendations/peers/check-registered",
    { method: "POST", body: { emails, phones } }
  );

export const logPeerInvite = (userId: string, peer: { email?: string; phone?: string; uid?: string }) =>
  apiRequest<{ success: boolean }>("/api/v1/recommendations/peers/invite", {
    method: "POST",
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
