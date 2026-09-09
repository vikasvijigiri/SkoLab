"use client";

import { useEffect } from "react";
import { useFirestoreCollection } from "@/lib/hooks/useFirestoreCollection";
import { subscribePresence, heartbeatPresence, clearPresence } from "@/lib/firebase/workspace";
import { useAuth } from "@/lib/hooks/AuthProvider";
import type { CollabPresence } from "@/lib/types";

/** Live "who's here" avatar stack. Heartbeats every 15s while mounted;
 *  entries older than 45s are filtered out by the subscription. */
export function PresenceStack({ projectId, activeDocId }: { projectId: string; activeDocId: string | null }) {
  const { user } = useAuth();

  const { data: people } = useFirestoreCollection<CollabPresence>(
    (next, onErr) => subscribePresence(projectId, next, onErr),
    { deps: [projectId] }
  );

  useEffect(() => {
    if (!user) return;
    const me = { uid: user.uid, name: user.displayName ?? "Researcher" };
    heartbeatPresence(projectId, me, activeDocId).catch(() => {});
    const t = setInterval(() => heartbeatPresence(projectId, me, activeDocId).catch(() => {}), 15_000);
    return () => {
      clearInterval(t);
      clearPresence(projectId, user.uid);
    };
  }, [projectId, activeDocId, user]);

  const others = people.filter((p) => p.uid !== user?.uid);
  if (others.length === 0) return null;

  return (
    <div className="flex items-center gap-2" title={`${others.length} other${others.length > 1 ? "s" : ""} viewing`}>
      <div className="flex -space-x-1.5">
        {others.slice(0, 4).map((p) => (
          <div
            key={p.uid}
            title={p.name}
            className="flex h-6 w-6 items-center justify-center rounded-full border border-surface bg-accent-teal font-display text-[10px] font-bold text-text-on-primary"
          >
            {p.name.slice(0, 1).toUpperCase()}
          </div>
        ))}
      </div>
      {others.length > 4 && (
        <span className="font-body text-[11px] text-text-muted">+{others.length - 4}</span>
      )}
    </div>
  );
}
