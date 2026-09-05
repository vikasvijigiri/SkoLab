/**
 * Identifies this app to OpenAlex's "polite pool" for higher, more reliable
 * rate limits instead of the anonymous common pool. Mirrors
 * services/backend/app/services/data/openalex_service.py, which reads the same
 * intent from its own OPENALEX_EMAIL setting.
 *
 * Set OPENALEX_EMAIL (server env, both services) to a real address you own.
 * OpenAlex doesn't verify deliverability, but it must be a well-formed address
 * with a valid TLD — the previous hardcoded "support@skolab.open" was not, so
 * the polite pool was never actually granted. Used only in server-side API
 * routes (app/api/openalex/*), so a plain env var is fine — no NEXT_PUBLIC_.
 */
export const OPENALEX_MAILTO =
  process.env.OPENALEX_EMAIL?.trim() || "support@skolab-web.onrender.com";
