import { NextRequest, NextResponse } from "next/server";
import { OPENALEX_MAILTO } from "@/lib/openalex";

/**
 * Server-side proxy for paper search and trending papers — keeps the OpenAlex call
 * off the client, unlike the Android app. With `q`, searches by title/topic. Without
 * `q`, returns high-citation papers from the last two years (optionally scoped by
 * `focus`), mirroring the Android app's ApiService.getTrendingPapers().
 */
export async function GET(req: NextRequest) {
  const q = req.nextUrl.searchParams.get("q");
  const focus = req.nextUrl.searchParams.get("focus");

  const url = new URL("https://api.openalex.org/works");
  // Identifies us to OpenAlex's "polite pool" (higher, more reliable rate limits)
  // instead of the anonymous common pool — matches services/backend's OpenAlexService.
  url.searchParams.set("mailto", OPENALEX_MAILTO);

  if (q) {
    url.searchParams.set("search", q);
    url.searchParams.set("per-page", "20");
  } else {
    const year = new Date().getFullYear();
    url.searchParams.set("per-page", "12");
    if (focus) {
      // `default.search` is only valid *inside* the filter param — passing it
      // as a top-level query param is rejected by OpenAlex with a 400
      // ("default.search is not a valid parameter"). A comma joins filter
      // clauses; the value itself must not contain a comma, so strip any.
      const safeFocus = focus.replace(/,/g, " ").trim();
      url.searchParams.set("filter", `publication_year:${year - 1}|${year},default.search:${safeFocus}`);
      url.searchParams.set("sort", "relevance_score:desc");
    } else {
      url.searchParams.set("filter", `publication_year:${year - 1}|${year}`);
      url.searchParams.set("sort", "cited_by_count:desc");
    }
  }

  // OpenAlex grants the "polite pool" (higher, steadier limits) via EITHER the
  // mailto param OR a User-Agent carrying mailto: — send both, matching
  // services/backend's OpenAlexService. Without this the common pool 429s under
  // even light bursts.
  const headers = { "User-Agent": `SkoLabWeb/1.0 (mailto:${OPENALEX_MAILTO})` };

  let res = await fetch(url, { headers, next: { revalidate: q ? 300 : 1800 } });
  // One short backoff on a 429 — OpenAlex rate limits are per-second, so a
  // brief wait usually clears it without bubbling an error to the user.
  if (res.status === 429) {
    await new Promise((r) => setTimeout(r, 1200));
    res = await fetch(url, { headers, cache: "no-store" });
  }
  if (!res.ok) {
    return NextResponse.json({ error: "openalex request failed" }, { status: res.status });
  }
  const data = await res.json();
  return NextResponse.json(data.results ?? []);
}
