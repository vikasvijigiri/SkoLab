"use client";

import { useCallback, useMemo } from "react";
import { useFirestoreCollection, type FirestoreSubscribe } from "./useFirestoreCollection";
import { subscribeTrackedResearchers, trackResearcher, untrackResearcher } from "@/lib/firebase/tracking";
import type { TrackedResearcher } from "@/lib/types";

/**
 * Live "Track" state for the signed-in user (decision 0021) — backed by
 * `users/{uid}/tracked_researchers/{authorId}`, the same collection
 * `ResearcherCard`'s Track button and the author page's Highlights layer
 * both read/write, so a toggle on one surface reflects instantly on the
 * other (both subscribe to the same Firestore query).
 *
 * `uid` may be `null`/`undefined` for a signed-out visitor — the
 * subscription is simply disabled and every mutator becomes a no-op.
 */
export function useTrackedResearchers(uid: string | null | undefined) {
  const subscribe: FirestoreSubscribe<TrackedResearcher> | null = uid
    ? (next, err) => subscribeTrackedResearchers(uid, next, err)
    : null;
  const { data, loading, error } = useFirestoreCollection(subscribe, { deps: [uid ?? null] });

  const trackedIds = useMemo(() => new Set(data.map((row) => row.authorId)), [data]);
  const isTracked = useCallback((authorId: string) => trackedIds.has(authorId), [trackedIds]);

  const track = useCallback(
    (authorId: string, name: string) => (uid ? trackResearcher(uid, authorId, name) : Promise.resolve()),
    [uid],
  );
  const untrack = useCallback(
    (authorId: string) => (uid ? untrackResearcher(uid, authorId) : Promise.resolve()),
    [uid],
  );
  const toggle = useCallback(
    (authorId: string, name: string) => (isTracked(authorId) ? untrack(authorId) : track(authorId, name)),
    [isTracked, track, untrack],
  );

  return { tracked: data, trackedIds, isTracked, track, untrack, toggle, loading, error };
}
