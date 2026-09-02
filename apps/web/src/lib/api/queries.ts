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
 *
 * `staleTime` per query is set a little below the matching server cache TTL in
 * services/backend/app/core/cache.py, so a refetch that does fire still lands
 * on a warm server cache instead of paying the cold path. `gcTime` keeps the
 * data resident long enough that navigating back to a surface repaints from
 * cache.
 */
const MIN = 60_000;
const HR = 60 * MIN;
/** server TTL 1 h (profile, feed, collaborators, heatmap, grants, opportunities). */
const HOURLY = { staleTime: 30 * MIN, gcTime: 2 * HR } as const;
/** server TTL 2 h (journal advisor). */
const BIHOURLY = { staleTime: 60 * MIN, gcTime: 3 * HR } as const;

export const authorQuery = (name: string, id?: string, focus?: string) =>
  queryOptions({
    queryKey: ["author", { id: id ?? null, name, focus: focus ?? null }] as const,
    queryFn: () => searchAuthor(name, id, focus),
    ...HOURLY,
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
    ...HOURLY,
    enabled: Boolean(authorId),
  });

export const heatmapQuery = (authorId: string) =>
  queryOptions({
    queryKey: ["author", authorId, "heatmap"] as const,
    queryFn: () => getCitationHeatmap(authorId),
    ...HOURLY,
    enabled: Boolean(authorId),
  });

export const journalAdvisorQuery = (authorId: string) =>
  queryOptions({
    queryKey: ["author", authorId, "journals"] as const,
    queryFn: () => getJournalAdvisor(authorId),
    ...BIHOURLY,
    enabled: Boolean(authorId),
  });

export const authorSuggestionsQuery = (query: string) =>
  queryOptions({
    queryKey: ["author-suggestions", query] as const,
    queryFn: () => getAuthorSuggestions(query),
    enabled: query.trim().length >= 2,
    staleTime: 10 * MIN,
    gcTime: 30 * MIN,
  });

export const dailyFeedQuery = (authorId?: string, queryFallback?: string) =>
  queryOptions({
    queryKey: ["daily-feed", { authorId: authorId ?? null, queryFallback: queryFallback ?? null }] as const,
    queryFn: () => getDailyFeed(authorId, queryFallback),
    ...HOURLY,
  });

export const dailyConjectureQuery = (authorId?: string, name?: string) =>
  queryOptions({
    queryKey: ["daily-conjecture", { authorId: authorId ?? null, name: name ?? null }] as const,
    queryFn: () => getDailyConjecture(authorId, name),
    staleTime: 2 * HR,
    gcTime: 6 * HR,
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
    staleTime: 5 * MIN,
    gcTime: 30 * MIN,
  });

export const paperWorkQuery = (id: string) =>
  queryOptions({
    queryKey: ["paper-work", id] as const,
    queryFn: () => openAlexWorkById(id),
    ...HOURLY,
    enabled: Boolean(id),
  });

export const paperAnalysisQuery = (opts: { title?: string; doi?: string; openalexId?: string }) =>
  queryOptions({
    queryKey: ["paper-analysis", { title: opts.title ?? null, doi: opts.doi ?? null, openalexId: opts.openalexId ?? null }] as const,
    queryFn: () => analyzePaper(opts),
    enabled: Boolean(opts.title || opts.doi || opts.openalexId),
    staleTime: 6 * HR,
    gcTime: 12 * HR,
  });

export const industryOpportunitiesQuery = (focus = "AI", name?: string) =>
  queryOptions({
    queryKey: ["industry-opportunities", { focus, name: name ?? null }] as const,
    queryFn: () => getIndustryOpportunities(focus, name),
    ...HOURLY,
  });

export const matchGrantsQuery = (authorId: string) =>
  queryOptions({
    queryKey: ["grants", authorId] as const,
    queryFn: () => getMatchGrants(authorId),
    ...HOURLY,
    enabled: Boolean(authorId),
  });

export const leaderboardQuery = (field = "all") =>
  queryOptions({
    queryKey: ["leaderboard", field] as const,
    queryFn: () => getLeaderboard(field),
    staleTime: 5 * MIN,
    gcTime: 30 * MIN,
  });
