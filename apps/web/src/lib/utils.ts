import { clsx, type ClassValue } from "clsx";
import { twMerge } from "tailwind-merge";

export function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(inputs));
}

/**
 * Keyboard focus ring for interactive wrappers (a `<Link>` / `<button>` around
 * a Card, a row, a tile). Matches Button's own focus-visible treatment so every
 * focusable surface in the app rings the same way. Mouse users never see it;
 * keyboard users always do.
 */
export const focusRing =
  "rounded-lg outline-none focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-primary";
