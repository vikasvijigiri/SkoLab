"use client";

import { useCallback, useMemo } from "react";
import { useFirestoreCollection, type FirestoreSubscribe } from "./useFirestoreCollection";
import { subscribeTrackedTopics, trackTopic, untrackTopic } from "@/lib/firebase/tracking";
import type { TrackedTopic } from "@/lib/types";

/**
 * Live "Track" state for the signed-in user's followed Discovery topics —
 * the topic half of decision 0021, mirroring `useTrackedResearchers` exactly
 * but backed by `users/{uid}/tracked_topics/{topicId}`.
 *
 * `uid` may be `null`/`undefined` for a signed-out visitor — the
 * subscription is simply disabled and every mutator becomes a no-op.
 */
export function useTrackedTopics(uid: string | null | undefined) {
  const subscribe: FirestoreSubscribe<TrackedTopic> | null = uid
    ? (next, err) => subscribeTrackedTopics(uid, next, err)
    : null;
  const { data, loading, error } = useFirestoreCollection(subscribe, { deps: [uid ?? null] });

  const trackedIds = useMemo(() => new Set(data.map((row) => row.topicId)), [data]);
  const isTracked = useCallback((topicId: string) => trackedIds.has(topicId), [trackedIds]);

  const track = useCallback(
    (topicId: string, name: string) => (uid ? trackTopic(uid, topicId, name) : Promise.resolve()),
    [uid],
  );
  const untrack = useCallback(
    (topicId: string) => (uid ? untrackTopic(uid, topicId) : Promise.resolve()),
    [uid],
  );
  const toggle = useCallback(
    (topicId: string, name: string) => (isTracked(topicId) ? untrack(topicId) : track(topicId, name)),
    [isTracked, track, untrack],
  );

  return { tracked: data, trackedIds, isTracked, track, untrack, toggle, loading, error };
}
