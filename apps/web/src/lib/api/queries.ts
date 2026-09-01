import { queryOptions } from "@tanstack/react-query";
import {
  searchAuthor,
  getNetworkCollaborators,
  getCitationHeatmap,
  getJournalAdvisor,
  getAuthorSuggestions,
  getDailyFeed,
  getDailyConjecture,
  getIndustryOpportunities,
  getMatchGrants,
  analyzePaper,
  openAlexWorks,
  openAlexWorkById,
  getLeaderboard,
} from "./endpoints";

/**
 * Typed `queryOptions` factories — the single place a query key and its fetcher
 * are defined together. Pass the result straight to `useQuery(...)`.
 *
 * Key convention:
 *   ["author", { id, name, focus }]                primary author record
 *   ["author", id, "collaborators", { field, name }]
 *   ["author", id, "heatmap"] | ["author", id, "journals"]
 *   ["author-suggestions", query]
 *   ["daily-feed", { authorId, queryFallback }] | ["daily-conjecture", { authorId, name }]
 */

export const authorQuery = (name: string, id?: string, focus?: string) =>
  queryOptions({
    queryKey: ["author", { id: id ?? null, name, focus: focus ?? null }] as const,
    queryFn: () => searchAuthor(name, id, focus),
    enabled: Boolean(name || id),
  });

export const collaboratorsQuery = (
  authorId: string,
  field?: string,
  name?: string,
  limit = 50,
) =>
  queryOptions({
    queryKey: ["author", authorId, "collaborators", { field: field ?? null, name: name ?? null, limit }] as const,
    queryFn: () => getNetworkCollaborators(authorId, field, name, limit),
    enabled: Boolean(authorId),
  });

export const heatmapQuery = (authorId: string) =>
  queryOptions({
    queryKey: ["author", authorId, "heatmap"] as const,
    queryFn: () => getCitationHeatmap(authorId),
    enabled: Boolean(authorId),
  });

export const journalAdvisorQuery = (authorId: string) =>
  queryOptions({
    queryKey: ["author", authorId, "journals"] as const,
    queryFn: () => getJournalAdvisor(authorId),
    enabled: Boolean(authorId),
  });

export const authorSuggestionsQuery = (query: string) =>
  queryOptions({
    queryKey: ["author-suggestions", query] as const,
    queryFn: () => getAuthorSuggestions(query),
    enabled: query.trim().length >= 2,
    staleTime: 5 * 60_000,
  });

export const dailyFeedQuery = (authorId?: string, queryFallback?: string) =>
  queryOptions({
    queryKey: ["daily-feed", { authorId: authorId ?? null, queryFallback: queryFallback ?? null }] as const,
    queryFn: () => getDailyFeed(authorId, queryFallback),
  });

export const dailyConjectureQuery = (authorId?: string, name?: string) =>
  queryOptions({
    queryKey: ["daily-conjecture", { authorId: authorId ?? null, name: name ?? null }] as const,
    queryFn: () => getDailyConjecture(authorId, name),
    staleTime: 30 * 60_000,
  });

// ── Discovery / Horizon / Nexus / Paper ──────────────────────────────────
//   ["openalex-works", { q, focus }]      literature search / field browse
//   ["paper-analysis", { title, doi, openalexId }]
//   ["industry-opportunities", { focus, name }] | ["grants", authorId]
// (Horizon predict and Nexus chat are POST-on-submit → useMutation, no factory.)

export const openAlexWorksQuery = (opts: { q?: string; focus?: string } = {}) =>
  queryOptions({
    queryKey: ["openalex-works", { q: opts.q ?? null, focus: opts.focus ?? null }] as const,
    queryFn: () => openAlexWorks(opts),
    enabled: Boolean(opts.q?.trim() || opts.focus),
    staleTime: 5 * 60_000,
  });

export const paperWorkQuery = (id: string) =>
  queryOptions({
    queryKey: ["paper-work", id] as const,
    queryFn: () => openAlexWorkById(id),
    enabled: Boolean(id),
  });

export const paperAnalysisQuery = (opts: { title?: string; doi?: string; openalexId?: string }) =>
  queryOptions({
    queryKey: ["paper-analysis", { title: opts.title ?? null, doi: opts.doi ?? null, openalexId: opts.openalexId ?? null }] as const,
    queryFn: () => analyzePaper(opts),
    enabled: Boolean(opts.title || opts.doi || opts.openalexId),
    staleTime: 6 * 60 * 60_000,
  });

export const industryOpportunitiesQuery = (focus = "AI", name?: string) =>
  queryOptions({
    queryKey: ["industry-opportunities", { focus, name: name ?? null }] as const,
    queryFn: () => getIndustryOpportunities(focus, name),
  });

export const matchGrantsQuery = (authorId: string) =>
  queryOptions({
    queryKey: ["grants", authorId] as const,
    queryFn: () => getMatchGrants(authorId),
    enabled: Boolean(authorId),
  });

export const leaderboardQuery = (field = "all") =>
  queryOptions({
    queryKey: ["leaderboard", field] as const,
    queryFn: () => getLeaderboard(field),
    staleTime: 5 * 60_000,
  });
