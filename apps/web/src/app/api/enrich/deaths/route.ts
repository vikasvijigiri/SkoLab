import { NextRequest, NextResponse } from "next/server";
import { OPENALEX_MAILTO } from "@/lib/openalex";
import { DISCOVERY_CONFIG } from "@/lib/discovery/config";
import { buildDeathSparql, parseDeathBindings, sanitizeOrcids } from "@/lib/discovery/deaths";

/**
 * `GET /api/enrich/deaths?orcids=a,b,c` → `{ "<ORCID>": <deathYear> }` for the
 * verifiably deceased among the given ORCIDs (Wikidata `P570`). A missing key
 * means "not known to be deceased" — never "alive". Any upstream failure returns
 * `{}` with HTTP 200 so the researcher grid is never blocked.
 */

const ENDPOINT = "https://query.wikidata.org/sparql";
const UA = `SkoLabWeb/1.0 (mailto:${OPENALEX_MAILTO})`;

export async function GET(req: NextRequest) {
  const orcids = sanitizeOrcids(
    (req.nextUrl.searchParams.get("orcids") ?? "").split(",").filter(Boolean),
    DISCOVERY_CONFIG.enrichPageSize,
  );
  if (orcids.length === 0) return NextResponse.json({});

  const url = new URL(ENDPOINT);
  url.searchParams.set("query", buildDeathSparql(orcids));
  url.searchParams.set("format", "json");

  try {
    const res = await fetch(url, {
      headers: { Accept: "application/sparql-results+json", "User-Agent": UA },
      signal: AbortSignal.timeout(4000),
      next: { revalidate: 604800 },
    });
    if (!res.ok) return NextResponse.json({});
    return NextResponse.json(parseDeathBindings(await res.json()));
  } catch {
    return NextResponse.json({});
  }
}
