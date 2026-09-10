/**
 * Normalise a widened OpenAlex `/authors` row into `ResearcherResult`, and build
 * the `filter=` / `sort=` strings for the proxy. Pure — no network. See the
 * "Plan correction" in `docs/plans/2026-09-10-discovery-fit-first.md`: `/authors`
 * has no `topics.field.id` filter, and author `topics[]` carry `count`, not a
 * share.
 */

import { deriveSignals, type SignalContext } from "./signals";
import type { DiscoverySort, OpenAlexAuthorRaw, ResearcherResult } from "@/lib/types";

/** "https://openalex.org/subfields/1102" | "subfields/1102" | "1102" → "1102". */
export function bareId(v: string | null | undefined): string | null {
  if (!v) return null;
  const s = String(v).trim().replace(/^https?:\/\/openalex\.org\//i, "");
  return s.split("/").pop() || null;
}

interface OaTopic {
  id?: string;
  display_name?: string;
  count?: number;
  subfield?: { id?: string };
  field?: { id?: string };
}
interface OaAffiliation {
  institution?: { display_name?: string };
  years?: number[];
}

/** OpenAlex `/authors` result row as it arrives (loose — the API omits fields). */
export interface OaAuthorRow {
  id?: string;
  display_name?: string;
  orcid?: string | null;
  works_count?: number;
  cited_by_count?: number;
  summary_stats?: { h_index?: number; i10_index?: number; "2yr_mean_citedness"?: number };
  last_known_institutions?: { display_name?: string; country_code?: string; type?: string }[];
  affiliations?: OaAffiliation[];
  counts_by_year?: { year?: number; works_count?: number; cited_by_count?: number }[];
  topics?: OaTopic[];
}

function toRaw(row: OaAuthorRow): OpenAlexAuthorRaw {
  const inst = row.last_known_institutions?.[0];
  const topicsIn = row.topics ?? [];
  const totalCount = topicsIn.reduce((s, t) => s + (t.count ?? 0), 0);
  const topics = topicsIn.map((t) => ({
    id: bareId(t.id) ?? "",
    display_name: t.display_name ?? "",
    value: totalCount > 0 ? (t.count ?? 0) / totalCount : 0,
    subfield_id: bareId(t.subfield?.id),
    field_id: bareId(t.field?.id),
  }));
  return {
    id: (row.id ?? "").replace(/^https?:\/\/openalex\.org\//i, ""),
    display_name: row.display_name ?? "",
    orcid: row.orcid ? row.orcid.replace(/^https?:\/\/orcid\.org\//i, "") : null,
    works_count: row.works_count ?? 0,
    cited_by_count: row.cited_by_count ?? 0,
    h_index: row.summary_stats?.h_index ?? 0,
    i10_index: row.summary_stats?.i10_index ?? 0,
    two_yr_mean_citedness: row.summary_stats?.["2yr_mean_citedness"] ?? 0,
    institution: inst?.display_name ?? "",
    country: inst?.country_code ? inst.country_code.toUpperCase() : null,
    inst_type: inst?.type ?? null,
    counts_by_year: (row.counts_by_year ?? []).map((c) => ({
      year: c.year ?? 0,
      works_count: c.works_count ?? 0,
      cited_by_count: c.cited_by_count ?? 0,
    })),
    affiliations: (row.affiliations ?? []).map((a) => ({
      institution: a.institution?.display_name ?? "",
      years: a.years ?? [],
    })),
    topics,
  };
}

export function mapAuthorRow(row: OaAuthorRow, ctx: SignalContext = {}): ResearcherResult {
  const raw = toRaw(row);
  return {
    id: raw.id,
    display_name: raw.display_name,
    orcid: raw.orcid,
    institution: raw.institution,
    country: raw.country,
    instType: raw.inst_type,
    hIndex: raw.h_index,
    i10: raw.i10_index,
    worksCount: raw.works_count,
    citedBy: raw.cited_by_count,
    twoYrMean: raw.two_yr_mean_citedness,
    topics: raw.topics.map((t) => t.display_name).filter(Boolean),
    signals: deriveSignals(raw, ctx),
  };
}

/** Fill `signals.standingPercentile` = % of the page with an h-index at or below
 *  this one (so a higher h ⇒ higher percentile; the card shows `top (100−p)%`).
 *  Done over the whole page because a single row has no distribution. */
export function attachStandingPercentiles(rows: ResearcherResult[]): ResearcherResult[] {
  if (rows.length < 2) return rows;
  const hs = rows.map((r) => r.hIndex).sort((a, b) => a - b);
  return rows.map((r) => {
    const atOrBelow = hs.filter((h) => h <= r.hIndex).length;
    const pct = Math.round((atOrBelow / hs.length) * 100);
    return { ...r, signals: { ...r.signals, standingPercentile: pct } };
  });
}

export interface AuthorsFilterParams {
  /** OpenAlex topic ids (bare `T…`) — OR-joined into one `topics.id:` clause. */
  topicIds?: string[];
  hIndexMax?: number;
  country?: string;
  instType?: string;
  hasOrcid?: boolean;
  worksMin?: number;
}

export function buildAuthorsFilter(p: AuthorsFilterParams): string {
  const clauses: string[] = [];
  if (p.topicIds && p.topicIds.length > 0) {
    clauses.push(`topics.id:${p.topicIds.map(bareId).filter(Boolean).join("|")}`);
  }
  if (typeof p.hIndexMax === "number") clauses.push(`summary_stats.h_index:<${p.hIndexMax}`);
  if (p.country) clauses.push(`last_known_institutions.country_code:${p.country.toLowerCase()}`);
  if (p.instType) clauses.push(`last_known_institutions.type:${p.instType}`);
  if (p.hasOrcid) clauses.push("has_orcid:true");
  if (typeof p.worksMin === "number") clauses.push(`works_count:>${p.worksMin}`);
  return clauses.join(",");
}

export function mapSort(sort: DiscoverySort | string | null | undefined): string {
  switch (sort) {
    case "standing":
      return "summary_stats.h_index:desc";
    case "recent":
      return "summary_stats.2yr_mean_citedness:desc";
    // "fit" and "momentum" are computed client-side — the API sort is the
    // sensible citation default so the first page is still a strong candidate set.
    default:
      return "cited_by_count:desc";
  }
}
