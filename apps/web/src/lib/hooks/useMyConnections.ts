"use client";

import { useEffect, useState } from "react";
import { subscribeProjects } from "@/lib/firebase/workspace";
import type { CollabMember } from "@/lib/types";

export interface Connection {
  uid: string;
  name: string;
  email: string;
}

/**
 * "Connections" for the CV share flow's click-first "Send to a connection"
 * list (decisions/0023) — real SkoLab users the signed-in researcher actually
 * works with, derived from CoLab Workspace membership. This app has no
 * separate friends/connections graph; CoLab project membership
 * (`collabs_groups`, via the same `subscribeProjects` the Workspace page
 * uses) is the closest real, already-modeled relationship. Dedupes members
 * across every project the viewer belongs to, excluding themself.
 *
 * State is keyed by uid (mirroring useFirestoreDoc's pattern) so every
 * `setState` happens inside the async onSnapshot/onError callbacks, never
 * synchronously in the effect body.
 */
export function useMyConnections(uid: string | undefined): {
  connections: Connection[];
  loading: boolean;
} {
  const [byUid, setByUid] = useState<Record<string, Connection[]>>({});

  useEffect(() => {
    if (!uid) return;
    const unsub = subscribeProjects(
      uid,
      (projects) => {
        const dedup = new Map<string, Connection>();
        for (const project of projects) {
          for (const m of project.members as CollabMember[]) {
            if (m.uid === uid || dedup.has(m.uid)) continue;
            dedup.set(m.uid, { uid: m.uid, name: m.name, email: m.email });
          }
        }
        setByUid((prev) => ({ ...prev, [uid]: [...dedup.values()] }));
      },
      () => {
        setByUid((prev) => ({ ...prev, [uid]: prev[uid] ?? [] }));
      },
    );
    return unsub;
  }, [uid]);

  if (!uid) return { connections: [], loading: false };
  const entry = byUid[uid];
  return { connections: entry ?? [], loading: entry === undefined };
}
