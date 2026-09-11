import { NextRequest, NextResponse } from "next/server";
import { OPENALEX_MAILTO, withOpenAlexKey } from "@/lib/openalex";
import { DISCOVERY_CONFIG } from "@/lib/discovery/config";
import { bareId } from "@/lib/discovery/mapAuthor";
import { computeTopicGrowth } from "@/lib/discovery/trendingTopics";
import type { TopicActivityBucket } from "@/lib/types";

/**
 * Server-side proxy for trending topics: which topics in a field/subfield are
 * growing fastest right now.
 *   ?subfield=<id> | ?field=<id>
 *
 * OpenAlex's `/topics/{id}` entity has no growth field (verified live) — only
 * cumulative `works_count`/`cited_by_count`. Trend is derived from two
 * `/works?group_by=topics.id` calls (recent window, equal-length prior
 * window), never a raw recent-count or cumulative-count sort — see
 * `decisions/0015` for why (a raw recent-citation sort surfaced a five-week-old
 * paper with an implausible 135 citations during verification; a growth ratio
 * with a volume floor doesn't).
 */

const UA = `SkoLabWeb/1.0 (mailto:${OPENALEX_MAILTO})`;
/** 12h — topic growth moves slowly; no need to hit OpenAlex on every view. */
const REVALIDATE_SECONDS = 43200;

function isoDate(d: Date): string {
  return d.toISOString().slice(0, 10);
}

function windowDates(now: Date, windowDays: number) {
  const msPerDay = 86400000;
  const recentFrom = isoDate(new Date(now.getTime() - windowDays * msPerDay));
  const priorFrom = isoDate(new Date(now.getTime() - 2 * windowDays * msPerDay));
  return { recentFrom, priorFrom, priorTo: recentFrom };
}

interface GroupRow {
  key: string;
  key_display_name: string;
  count: number;
}

async function fetchTopicBuckets(
  filterField: string,
  id: string,
  dateFilter: string,
): Promise<{ ok: boolean; buckets: TopicActivityBucket[] }> {
  const url = new URL("https://api.openalex.org/works");
  url.searchParams.set("filter", `${filterField}:${bareId(id) ?? id},${dateFilter}`);
  url.searchParams.set("group_by", "topics.id");
  url.searchParams.set("per-page", "50");
  withOpenAlexKey(url);

  const headers = { "User-Agent": UA };
  let res = await fetch(url, { headers, next: { revalidate: REVALIDATE_SECONDS } });
  if (res.status === 429) {
    await new Promise((r) => setTimeout(r, 1200));
    res = await fetch(url, { headers, cache: "no-store" });
  }
  if (!res.ok) return { ok: false, buckets: [] };

  const data = await res.json();
  const buckets: TopicActivityBucket[] = (data.group_by ?? []).map((g: GroupRow) => ({
    id: bareId(g.key) ?? g.key,
    displayName: g.key_display_name,
    count: g.count,
  }));
  return { ok: true, buckets };
}

export async function GET(req: NextRequest) {
  const sp = req.nextUrl.searchParams;
  const subfield = sp.get("subfield");
  const field = sp.get("field");

  const [filterField, id] = subfield
    ? ["topics.subfield.id", subfield]
    : field
      ? ["topics.field.id", field]
      : [null, null];
  if (!filterField || !id) return NextResponse.json([]);

  const { recentFrom, priorFrom, priorTo } = windowDates(new Date(), DISCOVERY_CONFIG.trendingWindowDays);

  const [recent, prior] = await Promise.all([
    fetchTopicBuckets(filterField, id, `from_publication_date:${recentFrom}`),
    fetchTopicBuckets(filterField, id, `from_publication_date:${priorFrom},to_publication_date:${priorTo}`),
  ]);

  if (!recent.ok || !prior.ok) {
    return NextResponse.json({ error: "openalex request failed" }, { status: 502 });
  }

  return NextResponse.json(computeTopicGrowth(recent.buckets, prior.buckets));
}
