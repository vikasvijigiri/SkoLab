"use client";

import { useEffect, useState } from "react";
import { friendlyFirestoreError } from "@/components/ui/ErrorBanner";

export type FirestoreSubscribe<T> = (
  next: (rows: T[]) => void,
  onErr: (e: { code?: string; message?: string }) => void,
) => () => void;

interface Result<T> {
  data: T[];
  loading: boolean;
  error: string | null;
}

type Entry<T> = { data: T[]; error: string | null };

/**
 * Subscribe to a Firestore collection/query via an existing `subscribe*`
 * wrapper (see `lib/firebase/workspace.ts`). The `subscribe` closure identity
 * changes every render, so the effect keys on the caller-supplied stable
 * `opts.deps` array. A `null` subscribe disables the subscription.
 *
 * State is keyed by `JSON.stringify(opts.deps)` so a deps change shows the new
 * key's loading state, and every `setState` runs inside the async callbacks.
 */
export function useFirestoreCollection<T>(
  subscribe: FirestoreSubscribe<T> | null,
  opts: { deps: unknown[] },
): Result<T> {
  const key = JSON.stringify(opts.deps);
  const [byKey, setByKey] = useState<Record<string, Entry<T>>>({});

  useEffect(() => {
    if (subscribe == null) return;
    const unsub = subscribe(
      (rows) => setByKey((m) => ({ ...m, [key]: { data: rows, error: null } })),
      (e) =>
        setByKey((m) => ({
          ...m,
          [key]: { data: m[key]?.data ?? [], error: friendlyFirestoreError(e) },
        })),
    );
    return unsub;
    // Keyed on the caller's stable deps by design — `subscribe` is a fresh
    // closure each render.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, opts.deps);

  if (subscribe == null) return { data: [], loading: false, error: null };
  const entry = byKey[key];
  return {
    data: entry?.data ?? [],
    loading: entry === undefined,
    error: entry?.error ?? null,
  };
}
