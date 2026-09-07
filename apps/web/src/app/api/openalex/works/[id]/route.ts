import { NextResponse } from "next/server";
import { OPENALEX_MAILTO, withOpenAlexKey } from "@/lib/openalex";
import { shortOpenAlexId } from "@/lib/utils";

/** Server-side proxy for a single paper's OpenAlex record (basics only — /analyze_paper covers the AI intelligence report). */
export async function GET(_req: Request, context: RouteContext<"/api/openalex/works/[id]">) {
  const { id } = await context.params;

  // Accept a bare id ("W…") or a full URL id — OpenAlex only resolves the bare
  // form once it is URL-encoded into the path.
  const url = new URL(`https://api.openalex.org/works/${encodeURIComponent(shortOpenAlexId(id))}`);
  withOpenAlexKey(url);

  const res = await fetch(url, {
    headers: { "User-Agent": `SkoLabWeb/1.0 (mailto:${OPENALEX_MAILTO})` },
    next: { revalidate: 3600 },
  });
  if (!res.ok) {
    return NextResponse.json({ error: "openalex request failed" }, { status: res.status });
  }
  const data = await res.json();
  return NextResponse.json(data);
}
