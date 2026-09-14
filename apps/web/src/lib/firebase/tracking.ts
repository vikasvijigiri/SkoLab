import { collection, doc, setDoc, deleteDoc, onSnapshot, serverTimestamp, type Unsubscribe } from "firebase/firestore";
import { requireDb } from "./client";
import type { SubscribeErrorHandler } from "./workspace";
import type { TrackedResearcher, TrackedTopic } from "@/lib/types";

/**
 * "Track" (decision 0021) — a per-user bookmark on a researcher, distinct
 * from CoLab's "Start a project with X". Direct client<->Firestore access,
 * same convention as `workspace.ts` and `researchers/{uid}` (decision 0004):
 * no REST/Go endpoint, the web and Android clients would both talk to this
 * collection directly.
 *
 * Path and field shape are exact — `users/{uid}/tracked_researchers/{authorId}`
 * with `{ authorId, name, trackedAt }` — because a separate Signals
 * workstream reads this same collection for tracked-researcher alerts.
 */

function safeSubscribe(run: () => Unsubscribe, onError?: SubscribeErrorHandler): Unsubscribe {
  try {
    return run();
  } catch (err) {
    onError?.(err as Parameters<SubscribeErrorHandler>[0]);
    return () => {};
  }
}

export function subscribeTrackedResearchers(
  uid: string,
  cb: (rows: TrackedResearcher[]) => void,
  onError?: SubscribeErrorHandler,
): Unsubscribe {
  return safeSubscribe(
    () =>
      onSnapshot(
        collection(requireDb(), "users", uid, "tracked_researchers"),
        (snap) => cb(snap.docs.map((d) => d.data() as TrackedResearcher)),
        onError,
      ),
    onError,
  );
}

export async function trackResearcher(uid: string, authorId: string, name: string): Promise<void> {
  await setDoc(doc(requireDb(), "users", uid, "tracked_researchers", authorId), {
    authorId,
    name,
    trackedAt: serverTimestamp(),
  });
}

export async function untrackResearcher(uid: string, authorId: string): Promise<void> {
  await deleteDoc(doc(requireDb(), "users", uid, "tracked_researchers", authorId));
}

/**
 * "Track" for a Discovery topic — the topic half of decision 0021, mirroring
 * `trackResearcher`/`untrackResearcher`/`subscribeTrackedResearchers` exactly
 * but on `users/{uid}/tracked_topics/{topicId}` with `{ topicId, name,
 * trackedAt }`. Also read by the Go gateway's tracked_topic_activity source
 * (internal/activity/activity.go) — keep the field shape in sync with that.
 */
export function subscribeTrackedTopics(
  uid: string,
  cb: (rows: TrackedTopic[]) => void,
  onError?: SubscribeErrorHandler,
): Unsubscribe {
  return safeSubscribe(
    () =>
      onSnapshot(
        collection(requireDb(), "users", uid, "tracked_topics"),
        (snap) => cb(snap.docs.map((d) => d.data() as TrackedTopic)),
        onError,
      ),
    onError,
  );
}

export async function trackTopic(uid: string, topicId: string, name: string): Promise<void> {
  await setDoc(doc(requireDb(), "users", uid, "tracked_topics", topicId), {
    topicId,
    name,
    trackedAt: serverTimestamp(),
  });
}

export async function untrackTopic(uid: string, topicId: string): Promise<void> {
  await deleteDoc(doc(requireDb(), "users", uid, "tracked_topics", topicId));
}
