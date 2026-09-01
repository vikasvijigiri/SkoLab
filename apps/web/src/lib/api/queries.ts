import { queryOptions } from "@tanstack/react-query";
import {
  searchAuthor,
  getNetworkCollaborators,
  getCitationHeatmap,
  getJournalAdvisor,
  getAuthorSuggestions,
  getDailyFeed,
  getDailyConjecture,
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
