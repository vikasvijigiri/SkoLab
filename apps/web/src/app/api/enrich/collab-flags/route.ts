import { NextRequest, NextResponse } from "next/server";
import { resolveCollabFlags } from "@/lib/discovery/collabFlags";

/**
 * `GET /api/enrich/collab-flags?ids=a,b,c` → `{ "<id>": true }` for researchers
 * who self-declared "open to collaboration". Returns `{}` today — the seam
 * exists; the data arrives once professors populate the flag (`decisions/0014`).
 */
export async function GET(req: NextRequest) {
  const ids = (req.nextUrl.searchParams.get("ids") ?? "").split(",").filter(Boolean);
  return NextResponse.json(resolveCollabFlags(ids));
}
