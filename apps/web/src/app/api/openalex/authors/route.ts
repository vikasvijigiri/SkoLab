import { NextRequest, NextResponse } from "next/server";
import { OPENALEX_MAILTO, withOpenAlexKey } from "@/lib/openalex";

/**
 * Server-side proxy for OpenAlex authors.
 *   ?q=<name>                        → search (onboarding "is this you?")
 *   ?topic=<id> | ?subfield=<id> | ?field=<id>
 *                                    → top authors for that taxonomy node,
 *                                      by citations (click-only Discovery)
 * Trimmed to what the result cards render.
 */
const TAXON_FILTER = { topic: "topics.id", subfield: "topics.subfield.id", field: "topics.field.id" } as const;

function bareId(v: string): string {
  return v.trim().replace(/^https?:\/\/openalex\.org\//i, "");
}

export async function GET(req: NextRequest) {
  const sp = req.nextUrl.searchParams;
  const q = sp.get("q")?.trim();

  const url = new URL("https://api.openalex.org/authors");
  url.searchParams.set("per-page", q ? "8" : "24");

  if (q) {
    url.searchParams.set("search", q);
  } else {
    let matched = false;
    for (const [key, field] of Object.entries(TAXON_FILTER)) {
      const id = sp.get(key);
      if (id) {
        url.searchParams.set("filter", `${field}:${bareId(id)}`);
        url.searchParams.set("sort", "cited_by_count:desc");
        matched = true;
        break;
      }
    }
    if (!matched) return NextResponse.json([]);
  }
  url.searchParams.set(
    "select",
    "id,display_name,orcid,works_count,cited_by_count,summary_stats,last_known_institutions",
  );
  withOpenAlexKey(url);

  const res = await fetch(url, {
    headers: { "User-Agent": `SkoLabWeb/1.0 (mailto:${OPENALEX_MAILTO})` },
    next: { revalidate: 300 },
  });
  if (!res.ok) {
    return NextResponse.json({ error: "openalex request failed" }, { status: res.status });
  }
  const data = await res.json();
  const items = (data.results ?? []).map(
    (r: {
      id: string;
      display_name: string;
      orcid: string | null;
      works_count: number;
      cited_by_count: number;
      summary_stats?: { h_index?: number };
      last_known_institutions?: { display_name: string }[];
    }) => ({
      id: r.id.replace(/^https?:\/\/openalex\.org\//i, ""),
      display_name: r.display_name,
      orcid: r.orcid ? r.orcid.replace(/^https?:\/\/orcid\.org\//i, "") : null,
      works_count: r.works_count ?? 0,
      cited_by_count: r.cited_by_count ?? 0,
      h_index: r.summary_stats?.h_index ?? 0,
      institution: r.last_known_institutions?.[0]?.display_name ?? "",
    }),
  );
  return NextResponse.json(items);
}
