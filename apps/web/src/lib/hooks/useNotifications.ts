"use client";

import { useCallback, useMemo } from "react";
import { useQuery } from "@tanstack/react-query";
import { activityFeedQuery } from "@/lib/api/queries";
import { useAuth } from "./AuthProvider";
import { useLocalStorage } from "./useLocalStorage";
import type { ActivityItem, ActivityType } from "@/lib/types";

export type NotificationKind =
  | "connection"
  | "paper"
  | "citation"
  | "tracked_paper"
  | "tracked_topic"
  | "mention"
  | "invite";

/** The five chips on the full Notifications page (decisions/0022). */
export type NotificationFilter = "citations" | "following" | "colab" | "connections";

export interface Notification {
  id: string;
  kind: NotificationKind;
  filter: NotificationFilter;
  text: string;
  href: string;
  ts: string;
  unread: boolean;
}

// "trending" (the public cold-start floor) deliberately has no entry — it's
// not personal to this user, so it never appears in the inbox.
const KIND_BY_TYPE: Partial<Record<ActivityType, NotificationKind>> = {
  connection_made: "connection",
  paper_published: "paper",
  citation_received: "citation",
  tracked_researcher_paper: "tracked_paper",
  tracked_topic_activity: "tracked_topic",
  mention: "mention",
  invite: "invite",
};

// `paper` (an untracked connection's paper) is grouped under "Following"
// alongside `tracked_paper` — decisions/0022's second addendum folds the two
// together rather than giving the legacy kind its own filter, since tracking
// a connection is one click.
const FILTER_BY_KIND: Record<NotificationKind, NotificationFilter> = {
  connection: "connections",
  paper: "following",
  citation: "citations",
  tracked_paper: "following",
  tracked_topic: "following",
  mention: "colab",
  invite: "colab",
};

/**
 * Builds the card's headline. Every branch names the real actor, paper,
 * topic, or backend-computed count — never a generic "something happened"
 * placeholder (decisions/0022's honesty rule, extended from CoLab/Discovery).
 *
 * `citation` and `tracked_topic` intentionally stay coarse: the backend only
 * knows a *count* went up (a citation-count watermark; see
 * internal/activity/activity.go), not which specific paper is responsible,
 * so the copy never invents a citing work or a citer's name.
 */
function describeText(kind: NotificationKind, it: ActivityItem): string {
  const who = it.actor?.display_name ?? "A researcher";
  switch (kind) {
    case "connection":
      return `${who} is now connected with you`;
    case "paper":
      return `${who} published “${it.object?.title ?? "a new paper"}”`;
    case "citation": {
      const n = it.count ?? 1;
      return n === 1
        ? "Your papers picked up a new citation since you last checked"
        : `Your papers picked up ${n} new citations since you last checked`;
    }
    case "tracked_paper":
      return `${who} (tracked) published “${it.object?.title ?? "a new paper"}”`;
    case "tracked_topic": {
      const n = it.count ?? 1;
      const topic = it.object?.title ?? "a topic you follow";
      return `${n} new paper${n === 1 ? "" : "s"} in ${topic} (topic you follow)`;
    }
    case "mention":
      return it.why ? `${who} mentioned you in “${it.why}”` : `${who} mentioned you`;
    case "invite":
      return it.why ? `${who} invited you to “${it.why}”` : `${who} invited you to a workspace`;
    default:
      return it.verb;
  }
}

/**
 * Notifications derived from the activity feed — connections, papers from
 * people/topics you track, citations to your own work, and CoLab
 * mentions/invites. Unread = newer than the last time the panel or the full
 * Notifications page marked everything read (`notifications:seenAt` in
 * localStorage).
 *
 * `limit` bounds how many items TanStack Query asks the gateway for (default
 * 20, gateway max 40); `cap` bounds how many are actually rendered (default
 * 12, matching the bell dropdown). The full `/notifications` page passes a
 * higher `limit`/`cap` so it isn't capped at dropdown size.
 */
export function useNotifications(
  authorId?: string,
  userId?: string,
  opts: { limit?: number; cap?: number } = {},
) {
  const { cap = 12 } = opts;
  const { getIdToken } = useAuth();
  const q = useQuery({
    ...activityFeedQuery(authorId, userId, getIdToken, opts.limit),
    enabled: Boolean(userId || authorId),
  });
  const [seenAt, setSeenAt] = useLocalStorage<string>("notifications:seenAt", "");

  const items = useMemo<Notification[]>(() => {
    const feed = q.data?.items ?? [];
    return feed
      .map((it: ActivityItem): Notification | null => {
        const kind = KIND_BY_TYPE[it.type];
        if (!kind) return null;
        return {
          id: it.id,
          kind,
          filter: FILTER_BY_KIND[kind],
          text: describeText(kind, it),
          href: it.href,
          ts: it.ts,
          unread: !seenAt || it.ts > seenAt,
        };
      })
      .filter((n): n is Notification => n !== null)
      .slice(0, cap);
  }, [q.data, seenAt, cap]);

  const unreadCount = items.filter((n) => n.unread).length;
  const markAllRead = useCallback(() => setSeenAt(new Date().toISOString()), [setSeenAt]);

  return {
    items,
    unreadCount,
    markAllRead,
    loading: q.isPending,
    error: q.isError
      ? q.error instanceof Error
        ? q.error.message
        : "Couldn't load your notifications."
      : null,
    refetch: q.refetch,
  };
}
