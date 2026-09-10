/**
 * OpenAlex has no "date of death" / "is active" field. For the few researchers a
 * leaderboard surfaces who are verifiably deceased (mostly famous names with a
 * Wikidata entry), we cross-reference Wikidata `P570` by ORCID (`P496`). Everyone
 * else relies on the `counts_by_year` activity proxy in `signals.ts`.
 *
 * Pure helpers — the route (`app/api/enrich/deaths/route.ts`) does the fetch.
 */

const ORCID_RE = /^\d{4}-\d{4}-\d{4}-\d{3}[\dX]$/;

/** Keep only well-formed ORCIDs, deduped, capped. */
export function sanitizeOrcids(raw: string[], cap: number): string[] {
  const seen = new Set<string>();
  for (const o of raw) {
    const v = o.trim().replace(/^https?:\/\/orcid\.org\//i, "").toUpperCase();
    if (ORCID_RE.test(v)) seen.add(v);
    if (seen.size >= cap) break;
  }
  return [...seen];
}

/** A SPARQL query returning one row per *deceased* ORCID-bearing person. */
export function buildDeathSparql(orcids: string[]): string {
  const values = orcids.map((o) => `"${o}"`).join(" ");
  return [
    "SELECT ?orcid ?year WHERE {",
    `  VALUES ?orcid { ${values} }`,
    "  ?person wdt:P496 ?orcid .",
    "  ?person wdt:P570 ?date .",
    "  BIND(YEAR(?date) AS ?year)",
    "}",
  ].join("\n");
}

interface SparqlBinding {
  orcid?: { value?: string };
  year?: { value?: string };
}
interface SparqlJson {
  results?: { bindings?: SparqlBinding[] };
}

/** Wikidata SPARQL JSON → `{ ORCID: deathYear }`. Malformed rows are skipped. */
export function parseDeathBindings(json: SparqlJson): Record<string, number> {
  const out: Record<string, number> = {};
  for (const b of json.results?.bindings ?? []) {
    const orcid = b.orcid?.value?.trim().toUpperCase();
    const year = Number(b.year?.value);
    if (orcid && ORCID_RE.test(orcid) && Number.isInteger(year) && year > 1000 && year < 3000) {
      out[orcid] = year;
    }
  }
  return out;
}
