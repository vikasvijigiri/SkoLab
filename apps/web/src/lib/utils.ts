import { clsx, type ClassValue } from "clsx";
import { twMerge } from "tailwind-merge";

export function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(inputs));
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
