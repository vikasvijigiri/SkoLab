"use client";

import { useCallback, useEffect, useState } from "react";

/** SSR-safe localStorage-backed state. Mirrors the pattern ThemeToggle.tsx hand-rolls, generalized. */
export function useLocalStorage<T>(key: string, initial: T): [T, (value: T) => void] {
  const [value, setValue] = useState<T>(initial);

  useEffect(() => {
    // Deliberately hydrating post-mount rather than via a lazy useState initializer:
    // localStorage doesn't exist during SSR, so reading it synchronously at initializer
    // time would make the client's first render diverge from the server-rendered HTML
    // and trigger a hydration mismatch. This is the safer of the two tradeoffs.
    try {
      const stored = localStorage.getItem(key);
      // eslint-disable-next-line react-hooks/set-state-in-effect
      if (stored !== null) setValue(JSON.parse(stored) as T);
    } catch {
      // Ignore malformed stored values — fall back to initial.
    }
  }, [key]);

  const set = useCallback(
    (next: T) => {
      setValue(next);
      try {
        localStorage.setItem(key, JSON.stringify(next));
      } catch {
        // Storage may be unavailable (private browsing, quota) — state still updates in-memory.
      }
    },
    [key]
  );

  return [value, set];
}
