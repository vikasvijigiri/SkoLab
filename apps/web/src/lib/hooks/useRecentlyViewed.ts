"use client";

import { useLocalStorage } from "./useLocalStorage";

export interface RecentItem {
  id: string;
  title: string;
  type: "paper" | "author";
  href: string;
}

const MAX_RECENTS = 5;
const STORAGE_KEY = "skolab-recently-viewed";

/** Capped MRU list of recently viewed papers/authors, backed by localStorage. Read by the command palette. */
export function useRecentlyViewed() {
  const [recents, setRecents] = useLocalStorage<RecentItem[]>(STORAGE_KEY, []);

  function recordView(item: RecentItem) {
    const next = [item, ...recents.filter((r) => r.id !== item.id)].slice(0, MAX_RECENTS);
    setRecents(next);
  }

  return { recents, recordView };
}
