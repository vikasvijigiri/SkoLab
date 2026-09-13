"use client";

import { useCallback } from "react";
import { useFirestoreDoc } from "./useFirestoreDoc";
import { useFirestoreCollection } from "./useFirestoreCollection";
import { DEFAULT_NOTIFICATION_SETTINGS, saveNotificationCadence, subscribeTrackedResearchers } from "@/lib/firebase/notifications";
import type { AlertCadence, NotificationSettings, TrackedResearcher } from "@/lib/types";

/** Live `users/{uid}/settings/notifications` doc, defaulted so every kind
 *  always has a cadence to render even before the doc exists (a brand-new
 *  user has never written one). `setCadence` merges a single field. */
export function useNotificationSettings(uid: string | undefined) {
  const { data, loading, error } = useFirestoreDoc<NotificationSettings>(
    uid ? `users/${uid}/settings/notifications` : null,
  );

  const settings: NotificationSettings = { ...DEFAULT_NOTIFICATION_SETTINGS, ...(data ?? {}) };

  const setCadence = useCallback(
    (key: keyof NotificationSettings, cadence: AlertCadence) => {
      if (!uid) return Promise.resolve();
      return saveNotificationCadence(uid, key, cadence);
    },
    [uid],
  );

  return { settings, loading, error, setCadence };
}

/** Live count of researchers the user is tracking (decisions/0021) — reads
 *  the same collection the Go activity feed reads server-side. Empty (not an
 *  error) until Track ships and/or the user has tracked anyone. */
export function useTrackedResearchers(uid: string | undefined) {
  const subscribe = uid
    ? (cb: (rows: TrackedResearcher[]) => void, onErr: (e: { code?: string; message?: string }) => void) =>
        subscribeTrackedResearchers(uid, cb, onErr)
    : null;
  return useFirestoreCollection<TrackedResearcher>(subscribe, { deps: [uid ?? null] });
}
