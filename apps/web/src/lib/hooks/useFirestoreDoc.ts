"use client";

import { useEffect, useState } from "react";
import { doc, onSnapshot } from "firebase/firestore";
import { requireDb } from "@/lib/firebase/client";
import { friendlyFirestoreError } from "@/components/ui/ErrorBanner";

interface Result<T> {
  data: (T & { id: string }) | null;
  loading: boolean;
  error: string | null;
}

type Entry<T> = { data: (T & { id: string }) | null; error: string | null };

/**
 * Subscribe to a single Firestore document by slash-path (e.g.
 * `"collabs_groups/abc"`). A `null` path disables the subscription. Returns
 * live `{ data, loading, error }` — no `refetch`, a listener needs none.
 *
 * State is keyed by `path`, so changing the path immediately shows the new
 * path's loading state rather than the previous doc's data, and every
 * `setState` happens inside the async `onSnapshot` callbacks (never
 * synchronously in the effect body).
 */
export function useFirestoreDoc<T>(path: string | null): Result<T> {
  const [byPath, setByPath] = useState<Record<string, Entry<T>>>({});

  useEffect(() => {
    if (path == null) return;
    const unsub = onSnapshot(
      // `doc()` accepts a full slash-delimited path as one argument.
      doc(requireDb(), path),
      (snap) => {
        setByPath((m) => ({
          ...m,
          [path]: {
            data: snap.exists()
              ? ({ id: snap.id, ...(snap.data() as T) } as T & { id: string })
              : null,
            error: null,
          },
        }));
      },
      (err) => {
        setByPath((m) => ({
          ...m,
          [path]: {
            data: m[path]?.data ?? null,
            error: friendlyFirestoreError(err),
          },
        }));
      },
    );
    return unsub;
  }, [path]);

  if (path == null) return { data: null, loading: false, error: null };
  const entry = byPath[path];
  return {
    data: entry?.data ?? null,
    loading: entry === undefined,
    error: entry?.error ?? null,
  };
}
