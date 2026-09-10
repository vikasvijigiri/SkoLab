import { NextRequest, NextResponse } from "next/server";
import { OPENALEX_MAILTO, withOpenAlexKey } from "@/lib/openalex";
import { DISCOVERY_CONFIG } from "@/lib/discovery/config";
import { buildAuthorsFilter, mapSort } from "@/lib/discovery/mapAuthor";

/**
 * Server-side proxy for OpenAlex authors.
 *   ?q=<name>                        → name search (onboarding "is this you?")
 *   ?topic=<id> | ?subfield=<id> | ?field=<id>
 *                                    → researchers for that taxonomy node
 *   & hIndexMax, country, instType, hasOrcid, worksMin, sort
 *                                    → fit-first Discovery filters / sort
 *
 * `/authors` has NO `topics.field.id` / `topics.subfield.id` filter (only
 * `topics.id`), so a subfield/field node is first expanded to its topic-id list
 * via `/topics` (cached 24 h). The response is a superset: the 7 keys the
 * onboarding picker + the drilldown card read, plus the enrichment blocks the
 * fit-first grid maps client-side.
 */

const UA = `SkoLabWeb/1.0 (mailto:${OPENALEX_MAILTO})`;
const NAME_SELECT =
  "id,display_name,orcid,works_count,cited_by_count,summary_stats,last_known_institutions";
const GRID_SELECT =
  "id,display_name,orcid,works_count,cited_by_count,summary_stats,last_known_institutions,affiliations,counts_by_year,topics";
/** URL-length guard: a field can carry 300+ topics; a subfield ~35. */
const MAX_TOPIC_IDS = 50;

/** Reduce any OpenAlex id form to its bare key ("subfields/1102" → "1102"). */
function bareId(v: string): string {
  const s = v.trim().replace(/^https?:\/\/openalex\.org\//i, "");
  return s.split("/").pop() ?? s;
}

function numParam(sp: URLSearchParams, key: string): number | undefined {
  const raw = sp.get(key);
  if (raw == null || raw.trim() === "") return undefined;
  const n = Number(raw);
  return Number.isFinite(n) ? n : undefined;
}

async function topicIdsForNode(kind: "subfield" | "field", id: string): Promise<string[]> {
  const url = new URL("https://api.openalex.org/topics");
  url.searchParams.set("filter", `${kind}.id:${bareId(id)}`);
  url.searchParams.set("per-page", String(MAX_TOPIC_IDS));
  url.searchParams.set("select", "id");
  withOpenAlexKey(url);
  const res = await fetch(url, { headers: { "User-Agent": UA }, next: { revalidate: 86400 } });
  if (!res.ok) return [];
  const data = await res.json();
  return (data.results ?? [])
    .map((t: { id?: string }) => (t.id ? bareId(t.id) : ""))
    .filter(Boolean);
}

interface OaRow {
  id: string;
  display_name: string;
  orcid: string | null;
  works_count?: number;
  cited_by_count?: number;
  summary_stats?: Record<string, number>;
  last_known_institutions?: { display_name?: string }[];
  affiliations?: unknown;
  counts_by_year?: unknown;
  topics?: unknown;
}

/** Trimmed shape (7 keys) the onboarding picker + drilldown card read, plus the
 *  raw enrichment blocks passed straight through for the fit-first grid. */
function mapRow(r: OaRow) {
  return {
    id: r.id.replace(/^https?:\/\/openalex\.org\//i, ""),
    display_name: r.display_name,
    orcid: r.orcid ? r.orcid.replace(/^https?:\/\/orcid\.org\//i, "") : null,
    works_count: r.works_count ?? 0,
    cited_by_count: r.cited_by_count ?? 0,
    h_index: r.summary_stats?.h_index ?? 0,
    institution: r.last_known_institutions?.[0]?.display_name ?? "",
    summary_stats: r.summary_stats ?? {},
    last_known_institutions: r.last_known_institutions ?? [],
    affiliations: r.affiliations ?? [],
    counts_by_year: r.counts_by_year ?? [],
    topics: r.topics ?? [],
  };
}

export async function GET(req: NextRequest) {
  const sp = req.nextUrl.searchParams;
  const q = sp.get("q")?.trim();

  // ── name search (onboarding) — unchanged behaviour ──────────────────────
  if (q) {
    const url = new URL("https://api.openalex.org/authors");
    url.searchParams.set("per-page", "8");
    url.searchParams.set("search", q);
    url.searchParams.set("select", NAME_SELECT);
    withOpenAlexKey(url);
    const res = await fetch(url, { headers: { "User-Agent": UA }, next: { revalidate: 300 } });
    if (!res.ok) {
      return NextResponse.json({ error: "openalex request failed" }, { status: res.status });
    }
    const data = await res.json();
    return NextResponse.json((data.results ?? []).map(mapRow));
  }

  // ── taxon mode: resolve the node to a topic-id list ─────────────────────
  let topicIds: string[] = [];
  const topic = sp.get("topic");
  const subfield = sp.get("subfield");
  const field = sp.get("field");
  if (topic) topicIds = [bareId(topic)];
  else if (subfield) topicIds = await topicIdsForNode("subfield", subfield);
  else if (field) topicIds = await topicIdsForNode("field", field);
  else return NextResponse.json([]);

  if (topicIds.length === 0) return NextResponse.json([]);

  const filter = buildAuthorsFilter({
    topicIds,
    hIndexMax: numParam(sp, "hIndexMax"),
    country: sp.get("country") ?? undefined,
    instType: sp.get("instType") ?? undefined,
    hasOrcid: sp.get("hasOrcid") === "1" || sp.get("hasOrcid") === "true",
    worksMin: numParam(sp, "worksMin"),
  });

  const url = new URL("https://api.openalex.org/authors");
  url.searchParams.set("per-page", String(DISCOVERY_CONFIG.pageSize));
  url.searchParams.set("filter", filter);
  url.searchParams.set("sort", mapSort(sp.get("sort")));
  url.searchParams.set("select", GRID_SELECT);
  withOpenAlexKey(url);

  const headers = { "User-Agent": UA };
  let res = await fetch(url, { headers, next: { revalidate: 300 } });
  if (res.status === 429) {
    await new Promise((r) => setTimeout(r, 1200));
    res = await fetch(url, { headers, cache: "no-store" });
  }
  if (!res.ok) {
    return NextResponse.json({ error: "openalex request failed" }, { status: res.status });
  }
  const data = await res.json();
  return NextResponse.json((data.results ?? []).map(mapRow));
}
