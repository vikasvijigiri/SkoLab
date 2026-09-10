/**
 * Collaboration-fit score (0–100) + a plain-language `why`, computed from
 * OpenAlex-available signals only — no embeddings (the vector term is a future
 * enhancement behind this same interface; see `decisions/0011` degraded mode,
 * `decisions/0014`). Pure, deterministic, total (never throws on empty input).
 */

import { DISCOVERY_CONFIG } from "./config";
import type { ResearcherResult } from "@/lib/types";

export interface FitViewer {
  /** The signed-in user's topic / concept tags (`AuthorResponse.expertise`). */
  expertise: string[];
  /** The user's last-known institution, for the same-institution bonus. */
  institution?: string;
}

export interface FitScore {
  score: number;
  why: string;
}

const norm = (s: string): string => s.toLowerCase().trim();

/** Exact set overlap + 0.5 credit per term that is a substring of a term in the
 *  other set, over the union, capped at 1 — mirrors the Go engine's `jaccardSim`
 *  (`internal/similarity/blend.go`). */
export function jaccardSim(a: string[], b: string[]): number {
  const sa = new Set(a.map(norm).filter(Boolean));
  const sb = new Set(b.map(norm).filter(Boolean));
  if (sa.size === 0 || sb.size === 0) return 0;

  const exact = new Set<string>();
  for (const u of sa) if (sb.has(u)) exact.add(u);

  let partial = 0;
  for (const u of sa) {
    if (exact.has(u)) continue;
    for (const c of sb) {
      if (u.includes(c) || c.includes(u)) {
        partial += 0.5;
        break;
      }
    }
  }
  const union = new Set([...sa, ...sb]);
  return Math.min(1, (exact.size + partial) / union.size);
}

function sharedInstitutionTokens(a?: string, b?: string): number {
  if (!a || !b) return 0;
  const ta = new Set(norm(a).split(/\s+/).filter((w) => w.length > 3));
  const tb = new Set(norm(b).split(/\s+/).filter((w) => w.length > 3));
  if (ta.size === 0 || tb.size === 0) return 0;
  let hits = 0;
  for (const w of ta) if (tb.has(w)) hits++;
  return Math.min(1, hits / Math.min(ta.size, tb.size));
}

export function scoreFit(viewer: FitViewer, cand: ResearcherResult): FitScore {
  const { wConcept, wInstitution, wCollab, wField } = DISCOVERY_CONFIG.fit;
  const cold = !viewer.expertise || viewer.expertise.length === 0;

  const concept = cold ? 0 : jaccardSim(viewer.expertise, cand.topics);
  const sameInstitution =
    viewer.institution && cand.institution && norm(viewer.institution) === norm(cand.institution) ? 1 : 0;
  const instToken = sameInstitution ? 1 : sharedInstitutionTokens(viewer.institution, cand.institution);
  const field = cand.signals.topicalFocus;

  let raw: number;
  if (cold) {
    // No viewer topics to match on — rank by how concentrated *they* are in the
    // scoped field, lifted by standing. Clearly weaker; caller flags `degraded`.
    const standing = (cand.signals.standingPercentile ?? 0) / 100;
    raw = 0.7 * field + 0.3 * standing;
  } else {
    raw = wConcept * concept + wInstitution * sameInstitution + wCollab * instToken + wField * field;
    raw /= wConcept + wInstitution + wCollab + wField;
  }

  const score = Math.max(0, Math.min(100, Math.round(raw * 100)));

  const reasons: string[] = [];
  if (!cold) {
    const overlap = viewer.expertise
      .map(norm)
      .filter((e) => cand.topics.some((t) => norm(t).includes(e) || e.includes(norm(t))));
    if (overlap.length > 0) {
      reasons.push(`${overlap.length} shared topic${overlap.length === 1 ? "" : "s"}`);
    }
  }
  if (sameInstitution) reasons.push("same institution");
  else if (instToken > 0.4) reasons.push("related institution");
  if (cold || reasons.length === 0) {
    if (field >= 0.5) reasons.push("highly focused on this field");
    else reasons.push("topical overlap");
  }

  return { score, why: reasons.slice(0, 2).join(" · ") };
}
