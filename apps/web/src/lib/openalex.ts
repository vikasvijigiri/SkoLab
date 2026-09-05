/**
 * OpenAlex auth. OpenAlex retired the `mailto` "polite pool" before Feb 2026 —
 * the parameter is now ignored — and replaced it with a free API key
 * (openalex.org/settings/api). A key raises the daily budget ~10× over the
 * keyless allowance; without one, bursts hit 429.
 *
 * Set OPENALEX_API_KEY (server env, both services) to that key. It's read only
 * in server-side API routes (app/api/openalex/*), so a plain env var is fine —
 * no NEXT_PUBLIC_. OPENALEX_MAILTO stays only for the descriptive User-Agent;
 * it no longer affects rate limits.
 */
export const OPENALEX_API_KEY = process.env.OPENALEX_API_KEY?.trim() || "";

export const OPENALEX_MAILTO =
  process.env.OPENALEX_EMAIL?.trim() || "support@skolab-web.onrender.com";

/** Apply the API key (if configured) to an OpenAlex request URL. */
export function withOpenAlexKey(url: URL): URL {
  if (OPENALEX_API_KEY) url.searchParams.set("api_key", OPENALEX_API_KEY);
  return url;
}
