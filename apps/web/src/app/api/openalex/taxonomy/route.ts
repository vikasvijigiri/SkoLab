import { NextRequest, NextResponse } from "next/server";
import { OPENALEX_MAILTO, withOpenAlexKey } from "@/lib/openalex";

/**
 * Server-side proxy for the OpenAlex topic taxonomy — used by the click-only
 * onboarding / discovery pickers so no field, sub-field or topic is ever a
 * hard-coded list.
 *
 *   ?kind=fields                      → the 26 top-level fields
 *   ?kind=subfields&parent=<fieldId>  → sub-fields of that field
 *   ?kind=topics&parent=<subfieldId>  → topics under that sub-field
 *
 * `parent` accepts a bare id ("2208") or a full OpenAlex URL. Results are
 * trimmed to `{ id, display_name }` and cached hard — the taxonomy is static.
 */
const KINDS = {
  fields: { path: "fields", filter: null, perPage: 30, sort: "display_name" },
  subfields: { path: "subfields", filter: "field.id", perPage: 60, sort: "display_name" },
  topics: { path: "topics", filter: "subfield.id", perPage: 40, sort: "works_count:desc" },
} as const;

function bareId(v: string): string {
  return v.trim().replace(/^https?:\/\/openalex\.org\//i, "");
}

export async function GET(req: NextRequest) {
  const kind = req.nextUrl.searchParams.get("kind") ?? "fields";
  const parent = req.nextUrl.searchParams.get("parent") ?? "";
  const spec = KINDS[kind as keyof typeof KINDS];
  if (!spec) {
    return NextResponse.json({ error: "unknown kind" }, { status: 400 });
  }
  if (spec.filter && !parent) {
    return NextResponse.json({ error: "parent is required" }, { status: 400 });
  }

  const url = new URL(`https://api.openalex.org/${spec.path}`);
  url.searchParams.set("per-page", String(spec.perPage));
  url.searchParams.set("sort", spec.sort);
  url.searchParams.set("select", "id,display_name");
  if (spec.filter) url.searchParams.set("filter", `${spec.filter}:${bareId(parent)}`);
  withOpenAlexKey(url);

  const res = await fetch(url, {
    headers: { "User-Agent": `SkoLabWeb/1.0 (mailto:${OPENALEX_MAILTO})` },
    // 30 days — the taxonomy effectively never changes.
    next: { revalidate: 60 * 60 * 24 * 30 },
  });
  if (!res.ok) {
    return NextResponse.json({ error: "openalex request failed" }, { status: res.status });
  }
  const data = await res.json();
  const items = (data.results ?? []).map((r: { id: string; display_name: string }) => ({
    id: bareId(r.id),
    display_name: r.display_name,
  }));
  return NextResponse.json(items);
}
