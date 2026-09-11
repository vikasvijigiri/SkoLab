import { NextRequest, NextResponse } from "next/server";
import { OPENALEX_MAILTO, withOpenAlexKey } from "@/lib/openalex";
import { DISCOVERY_CONFIG } from "@/lib/discovery/config";
import { bareId } from "@/lib/discovery/mapAuthor";
import { rankByVelocity } from "@/lib/discovery/trendingPapers";

/**
 * Server-side proxy for paper search and trending papers — keeps the OpenAlex call
 * off the client, unlike the Android app. With `q`, searches by title/topic.
 *
 * Without `q`: trending papers from the last `DISCOVERY_CONFIG.trendingPapersWindowDays`,
 * optionally scoped to the viewer's resolved field (`trendSubfield`/`trendField` —
 * the same auto-resolved scope the fit-first researcher grid and trending topics
 * already use). Ranked by citation velocity (`rankByVelocity`), never a raw or
 * cumulative count — a raw `cited_by_count:desc` sort over "papers from the last
 * two years" surfaced an implausible 135-citation five-week-old paper during
 * verification (decisions/0015). `sort=cited_by_count:desc` here is only
 * OpenAlex's own prefilter, pulling a reasonable candidate pool to re-rank —
 * never the final order.
 */
const TAXON_FILTER = { topic: "topics.id", subfield: "topics.subfield.id", field: "topics.field.id" } as const;

function isoDate(d: Date): string {
  return d.toISOString().slice(0, 10);
}

export async function GET(req: NextRequest) {
  const sp = req.nextUrl.searchParams;
  const q = sp.get("q");
  const focus = sp.get("focus");

  const url = new URL("https://api.openalex.org/works");

  // Click-only Discovery drilldown: top works for a taxonomy node — unchanged,
  // all-time top-cited, not a "trending" view.
  const taxonEntry = Object.entries(TAXON_FILTER).find(([k]) => sp.get(k));
  if (!q && taxonEntry) {
    const [key, field] = taxonEntry;
    // Last path segment only: OpenAlex's filter grammar 400s on a path-prefixed
    // id ("topics.subfield.id:subfields/1102").
    const id = ((sp.get(key) ?? "").replace(/^https?:\/\/openalex\.org\//i, "").split("/").pop() ?? "");
    url.searchParams.set("per-page", "18");
    url.searchParams.set("filter", `${field}:${id}`);
    url.searchParams.set("sort", "cited_by_count:desc");
    withOpenAlexKey(url);
    const headers = { "User-Agent": `SkoLabWeb/1.0 (mailto:${OPENALEX_MAILTO})` };
    const r = await fetch(url, { headers, next: { revalidate: 1800 } });
    if (!r.ok) return NextResponse.json({ error: "openalex request failed" }, { status: r.status });
    return NextResponse.json((await r.json()).results ?? []);
  }

  // Velocity-ranked trending, scoped to a viewer field if given — set below
  // for the non-search path only.
  let velocityRank = false;

  if (q) {
    url.searchParams.set("search", q);
    url.searchParams.set("per-page", "20");
  } else {
    velocityRank = true;
    const trendSubfield = sp.get("trendSubfield");
    const trendField = sp.get("trendField");
    const windowStart = isoDate(
      new Date(Date.now() - DISCOVERY_CONFIG.trendingPapersWindowDays * 86400000),
    );
    // type:article — verification surfaced a journal-issue "paratext" entry
    // with 561 citations attributed to it days after its listed date;
    // rankByVelocity repeats this check, but excluding it here means it's
    // never even fetched.
    const filters = [`from_publication_date:${windowStart}`, "type:article"];
    if (trendSubfield) {
      filters.push(`topics.subfield.id:${bareId(trendSubfield) ?? trendSubfield}`);
    } else if (trendField) {
      filters.push(`topics.field.id:${bareId(trendField) ?? trendField}`);
    } else if (focus) {
      // `default.search` is only valid *inside* the filter param — passing it
      // as a top-level query param is rejected by OpenAlex with a 400
      // ("default.search is not a valid parameter"). A comma joins filter
      // clauses; the value itself must not contain a comma, so strip any.
      filters.push(`default.search:${focus.replace(/,/g, " ").trim()}`);
    }
    url.searchParams.set("filter", filters.join(","));
    // OpenAlex's own prefilter — pulls a reasonable candidate pool for
    // rankByVelocity to re-rank; not the response order.
    url.searchParams.set("sort", "cited_by_count:desc");
    url.searchParams.set("per-page", String(DISCOVERY_CONFIG.trendingPapersPoolSize));
  }

  // A configured OPENALEX_API_KEY raises the daily budget ~10x; the mailto in
  // the User-Agent is descriptive only now (the polite pool is retired).
  withOpenAlexKey(url);
  const headers = { "User-Agent": `SkoLabWeb/1.0 (mailto:${OPENALEX_MAILTO})` };

  let res = await fetch(url, { headers, next: { revalidate: q ? 300 : 1800 } });
  // One short backoff on a 429 — the budget window is short, so a brief wait
  // often clears a transient burst without bubbling an error to the user.
  if (res.status === 429) {
    await new Promise((r) => setTimeout(r, 1200));
    res = await fetch(url, { headers, cache: "no-store" });
  }
  if (!res.ok) {
    return NextResponse.json({ error: "openalex request failed" }, { status: res.status });
  }
  const data = await res.json();
  const results = data.results ?? [];
  return NextResponse.json(velocityRank ? rankByVelocity(results, new Date()) : results);
}
