import { clsx, type ClassValue } from "clsx";
import { twMerge } from "tailwind-merge";

export function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(inputs));
}

/**
 * Bare OpenAlex id (`W2741809807`, `A5023888391`) from any of the forms the
 * APIs hand us — a full URL (`https://openalex.org/W…`, and by extension
 * `https://api.openalex.org/works/W…`), an already-bare id, or a trailing
 * slash. This is what a `/paper/[id]` or `/author/[id]` route param must be:
 * the value flows into `https://api.openalex.org/works/${encodeURIComponent(id)}`,
 * and a URL-encoded `https%3A%2F%2F…` there is a hard 400 from OpenAlex — the
 * "Couldn't load this paper" bug. The Go gateway already normalises every id
 * it receives (`cleanID`); this mirrors that on the web side so links built
 * from `daily_feed` (which returns canonical URL ids) resolve.
 *
 * Non-OpenAlex strings pass through unchanged (last path segment of a bare
 * word is the word itself), so it is safe to call on any href-ish id.
 */
export function shortOpenAlexId(id: string): string {
  if (!id) return id;
  const trimmed = id.trim().replace(/\/+$/, "");
  return trimmed.slice(trimmed.lastIndexOf("/") + 1) || id;
}

/**
 * Keyboard focus ring for interactive wrappers (a `<Link>` / `<button>` around
 * a Card, a row, a nav item). Mouse users never see it; keyboard users always
 * do. Pair it with the element's own `rounded-*` so the ring follows the
 * corners.
 *
 * Implemented as a `box-shadow` ring, not an `outline`: Tailwind v4's
 * `outline-2` sets `outline-width` but leaves `outline-style: none` (from the
 * base `outline-none`), so an outline-only ring renders invisibly — a WCAG
 * 2.4.7 failure. A double box-shadow (surface gap + primary ring) is
 * unambiguous and survives any `outline` cascade.
 */
export const focusRing =
  "outline-none focus-visible:shadow-[0_0_0_2px_var(--surface),0_0_0_4px_var(--primary)]";
