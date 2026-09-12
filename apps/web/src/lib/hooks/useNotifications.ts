"use client";

import { useCallback, useMemo } from "react";
import { useQuery } from "@tanstack/react-query";
import { activityFeedQuery } from "@/lib/api/queries";
import { useAuth } from "./AuthProvider";
import { useLocalStorage } from "./useLocalStorage";
import type { ActivityItem } from "@/lib/types";

export interface Notification {
  id: string;
  kind: "connection" | "paper";
  text: string;
  href: string;
  ts: string;
  unread: boolean;
}

/**
 * Lightweight notifications derived from the activity feed: a new connection or
 * a connected researcher's new paper. Unread = newer than the last time the
 * panel was opened (`notifications:seenAt` in localStorage).
 *
 * A dedicated notifications backend (connection *requests* to accept, workspace
 * invites, @mentions) is a follow-up — this gives the bell real content now.
 */
export function useNotifications(authorId?: string, userId?: string) {
  const { getIdToken } = useAuth();
  const q = useQuery({
    ...activityFeedQuery(authorId, userId, getIdToken),
    enabled: Boolean(userId || authorId),
  });
  const [seenAt, setSeenAt] = useLocalStorage<string>("notifications:seenAt", "");

  const items = useMemo<Notification[]>(() => {
    const feed = q.data?.items ?? [];
    return feed
      .filter((it: ActivityItem) => it.type === "connection_made" || it.type === "paper_published")
      .slice(0, 12)
      .map((it) => {
        const who = it.actor?.display_name ?? "A researcher";
        return {
          id: it.id,
          kind: it.type === "connection_made" ? ("connection" as const) : ("paper" as const),
          text:
            it.type === "connection_made"
              ? `${who} is now connected with you`
              : `${who} published “${it.object?.title ?? "a new paper"}”`,
          href: it.href,
          ts: it.ts,
          unread: !seenAt || it.ts > seenAt,
        };
      });
  }, [q.data, seenAt]);

  const unreadCount = items.filter((n) => n.unread).length;
  const markAllRead = useCallback(() => setSeenAt(new Date().toISOString()), [setSeenAt]);

  return { items, unreadCount, markAllRead, loading: q.isPending };
}
