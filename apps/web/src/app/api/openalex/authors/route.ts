import { NextRequest, NextResponse } from "next/server";
import { OPENALEX_MAILTO, withOpenAlexKey } from "@/lib/openalex";

/**
 * Server-side proxy for OpenAlex author search — powers the "is this you?"
 * picker in onboarding, so a user clicks their own profile instead of typing
 * a name / ORCID. Trimmed to what the picker cards render.
 */
export async function GET(req: NextRequest) {
  const q = req.nextUrl.searchParams.get("q")?.trim();
  if (!q) return NextResponse.json([]);

  const url = new URL("https://api.openalex.org/authors");
  url.searchParams.set("search", q);
  url.searchParams.set("per-page", "8");
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
