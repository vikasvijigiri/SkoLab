"use client";

import { useCallback, useMemo } from "react";
import { useLocalStorage } from "./useLocalStorage";
import type { FeedKind, FeedPrefs } from "@/lib/feed/unifiedFeed";

interface DismissRecord {
  id: string;
  kind: FeedKind;
}

/**
 * Per-viewer feed feedback, in localStorage. Two signals only (Semantic
 * Scholar's model): "Save" (positive, keeps the item and lifts it) and
 * "Not relevant" (negative, hides the item and damps that kind).
 */
export function useFeedPrefs() {
  const [saved, setSaved] = useLocalStorage<string[]>("feed:saved", []);
  const [dismissed, setDismissed] = useLocalStorage<DismissRecord[]>("feed:dismissed", []);

  const prefs = useMemo<FeedPrefs>(() => {
    const dismissedKinds: Partial<Record<FeedKind, number>> = {};
    for (const d of dismissed) dismissedKinds[d.kind] = (dismissedKinds[d.kind] ?? 0) + 1;
    return {
      savedIds: new Set(saved),
      dismissedIds: new Set(dismissed.map((d) => d.id)),
      dismissedKinds,
    };
  }, [saved, dismissed]);

  const isSaved = useCallback((id: string) => prefs.savedIds.has(id), [prefs]);

  const toggleSave = useCallback(
    (id: string) => {
      setSaved(saved.includes(id) ? saved.filter((s) => s !== id) : [...saved, id]);
    },
    [saved, setSaved],
  );

  const dismiss = useCallback(
    (id: string, kind: FeedKind) => {
      if (dismissed.some((d) => d.id === id)) return;
      // Cap the log so it can't grow forever; keep the most recent 200.
      setDismissed([...dismissed, { id, kind }].slice(-200));
    },
    [dismissed, setDismissed],
  );

  const reset = useCallback(() => {
    setSaved([]);
    setDismissed([]);
  }, [setSaved, setDismissed]);

  return { prefs, isSaved, toggleSave, dismiss, reset, savedCount: saved.length };
}
