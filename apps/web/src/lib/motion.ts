/**
 * Shared framer-motion timing constants. framer-motion needs JS values (not
 * CSS custom properties), so this is the JS-side counterpart to the motion
 * tokens in globals.css (`--motion-fast/normal/slow`, `--ease-standard`).
 *
 * EASE_STANDARD intentionally does NOT match globals.css's `--ease-standard`
 * (cubic-bezier(0.4, 0, 0.2, 1) — a Material-style curve used for CSS
 * `transition-timing-function`). [0.22, 1, 0.36, 1] (a snappier ease-out) is
 * what the framer-motion components across this app had already converged on
 * independently, hardcoded in ~18 files — this file just centralizes that
 * existing de facto standard so it's one source of truth instead of ~35
 * copies that can drift, not a new value.
 *
 * Durations are in seconds (framer-motion's unit), converted from the same
 * millisecond scale as globals.css's --motion-fast/normal/slow.
 */
export const EASE_STANDARD: [number, number, number, number] = [0.22, 1, 0.36, 1];

export const DURATION_FAST = 0.15;
export const DURATION_NORMAL = 0.3;
export const DURATION_SLOW = 0.4;

/** Common shape for a snappy micro-interaction (hover/tap on buttons, chips, icons). */
export const TRANSITION_FAST = { duration: DURATION_FAST, ease: EASE_STANDARD };

/** Common shape for a standard reveal/fade (cards, sections appearing). */
export const TRANSITION_NORMAL = { duration: DURATION_NORMAL, ease: EASE_STANDARD };

/** Common shape for a slower, more deliberate reveal (page-level entrances). */
export const TRANSITION_SLOW = { duration: DURATION_SLOW, ease: EASE_STANDARD };
