import { collection, doc, onSnapshot, setDoc, type FirestoreError, type Unsubscribe } from "firebase/firestore";
import { requireDb } from "./client";
import type { AlertCadence, NotificationSettings, TrackedResearcher } from "@/lib/types";

// decisions/0004: direct client<->Firestore access, no REST layer, same
// pattern as researchers/{uid} and collabs_groups/*. decisions/0022 adds this
// per-user alert-cadence doc and a read-only view of Discovery's Track list
// (decisions/0021) so the Manage Alerts screen can show a real "N tracked"
// count instead of a placeholder.

export const DEFAULT_NOTIFICATION_SETTINGS: NotificationSettings = {
  citations: "realtime",
  trackedResearchers: "daily",
  trackedTopics: "weekly",
  colabMentionsInvites: "realtime",
  connections: "daily",
};

/** users/{uid}/settings/notifications — merge:true so changing one kind's
 *  cadence never clobbers the others. Works the same for an anonymous
 *  (guest) Firebase session as a fully signed-up one — no account creation
 *  is required to change or even see these controls. */
export async function saveNotificationCadence(
  uid: string,
  key: keyof NotificationSettings,
  cadence: AlertCadence,
) {
  await setDoc(
    doc(requireDb(), "users", uid, "settings", "notifications"),
    { [key]: cadence },
    { merge: true },
  );
}

/** users/{uid}/tracked_researchers — written by Discovery's Track feature
 *  (decisions/0021). Read-only here: this subscribes to the live collection
 *  so the Manage Alerts screen can show a real, current count rather than a
 *  fabricated one. Returns an empty list (not an error) until that feature
 *  ships and/or the user has tracked anyone. */
export function subscribeTrackedResearchers(
  uid: string,
  cb: (rows: TrackedResearcher[]) => void,
  onError?: (err: FirestoreError) => void,
): Unsubscribe {
  try {
    return onSnapshot(
      collection(requireDb(), "users", uid, "tracked_researchers"),
      (snap) => cb(snap.docs.map((d) => d.data() as TrackedResearcher)),
      onError,
    );
  } catch (err) {
    onError?.(err as FirestoreError);
    return () => {};
  }
}
