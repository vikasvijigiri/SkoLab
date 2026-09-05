import { NextResponse } from "next/server";
import { OPENALEX_MAILTO, withOpenAlexKey } from "@/lib/openalex";

/** Server-side proxy for a single paper's OpenAlex record (basics only — /analyze_paper covers the AI intelligence report). */
export async function GET(_req: Request, context: RouteContext<"/api/openalex/works/[id]">) {
  const { id } = await context.params;

  const url = new URL(`https://api.openalex.org/works/${encodeURIComponent(id)}`);
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
