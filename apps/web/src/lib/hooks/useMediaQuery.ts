"use client";

import { useCallback, useSyncExternalStore } from "react";

/** SSR-safe media query hook, built on useSyncExternalStore for the browser's matchMedia API. */
export function useMediaQuery(query: string): boolean {
  const subscribe = useCallback(
    (callback: () => void) => {
      const mql = window.matchMedia(query);
      mql.addEventListener("change", callback);
      return () => mql.removeEventListener("change", callback);
    },
    [query]
  );
  const getSnapshot = useCallback(() => window.matchMedia(query).matches, [query]);

  return useSyncExternalStore(subscribe, getSnapshot, () => false);
}

/** Matches the redesign's desktop breakpoint (Tailwind `lg`). */
export function useIsDesktop(): boolean {
  return useMediaQuery("(min-width: 1024px)");
}
