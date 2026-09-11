"use client";

import { useState, useSyncExternalStore } from "react";
import { motion } from "framer-motion";
import { applyTheme, initialTheme, nextTheme, type Theme } from "@/lib/theme";
import { TRANSITION_FAST, TRANSITION_NORMAL } from "@/lib/motion";

// Nothing external notifies us when localStorage changes elsewhere -- this
// component owns writes via `cycle`, so a subscribe-only-once stub is enough.
function subscribeNever() {
  return () => {};
}
const getServerSnapshot = (): Theme => "light";

export function ThemeToggle() {
  // `useState(initialTheme)` used to read localStorage straight into the
  // lazy initializer: SSR/the static shell always assumes "light" (no
  // localStorage there), but the client's first render can see a real
  // persisted "dark"/"system" choice -- a hydration mismatch caught live
  // (React regenerating this whole subtree, `rect` vs `circle` in the
  // console diff). useSyncExternalStore is the React-documented fix: it
  // renders `getServerSnapshot` during hydration to match the static HTML,
  // then reconciles to the real client value immediately after, with no
  // mismatch warning and no visible flash -- same pattern already proven in
  // useGoogleRedirectPending. `override` lets a click take effect
  // immediately without waiting on a localStorage round trip.
  const persisted = useSyncExternalStore(subscribeNever, initialTheme, getServerSnapshot);
  const [override, setOverride] = useState<Theme | null>(null);
  const theme = override ?? persisted;
  const [hovered, setHovered] = useState(false);

  const cycle = () => {
    const next = nextTheme(theme);
    setOverride(next);
    applyTheme(next);
  };

  return (
    <motion.button
      onClick={cycle}
      onHoverStart={() => setHovered(true)}
      onHoverEnd={() => setHovered(false)}
      aria-label={`Theme: ${theme}`}
      title={`Theme: ${theme} (click to change)`}
      transition={TRANSITION_FAST}
      className="flex h-8 w-8 cursor-pointer items-center justify-center rounded-full border border-border bg-surface text-text-muted transition-[color,border-color,background-color] duration-[var(--motion-fast)] hover:border-primary/40 hover:bg-surface-subtle hover:text-primary"
      style={{ transitionTimingFunction: "var(--ease-standard)" }}
    >
      <motion.span
        className="flex items-center justify-center"
        animate={{ rotate: hovered ? 25 : 0 }}
        transition={TRANSITION_NORMAL}
      >
        {theme === "light" && (
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
            <circle cx="12" cy="12" r="4.5" />
            <path d="M12 2.5v2M12 19.5v2M4.2 4.2l1.4 1.4M18.4 18.4l1.4 1.4M2.5 12h2M19.5 12h2M4.2 19.8l1.4-1.4M18.4 5.6l1.4-1.4" strokeLinecap="round" />
          </svg>
        )}
        {theme === "dark" && (
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
            <path d="M20 14.5A8 8 0 1 1 9.5 4a6.5 6.5 0 0 0 10.5 10.5Z" strokeLinejoin="round" />
          </svg>
        )}
        {theme === "system" && (
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
            <rect x="3" y="4.5" width="18" height="12" rx="1.5" />
            <path d="M8 20h8M12 16.5V20" strokeLinecap="round" />
          </svg>
        )}
      </motion.span>
    </motion.button>
  );
}
