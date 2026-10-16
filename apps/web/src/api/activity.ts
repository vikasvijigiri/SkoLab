import { apiRequest } from "./client";
import type { ActivityItem, ActivityPage, Role } from "./editorTypes";

export type { ActivityItem, ActivityKind, ActivityPage, ActivityPerson } from "./editorTypes";

/**
 * What co-authors did in the reader's workspaces: the notifications bell.
 * The gateway writes these as changes happen (GET /api/v1/activity); the
 * reader's own actions never appear.
 */

export function listActivity(token: string, options: { pageSize?: number; pageToken?: string; signal?: AbortSignal } = {}): Promise<ActivityPage> {
  const query = new URLSearchParams();
  if (options.pageSize) query.set("page_size", String(options.pageSize));
  if (options.pageToken) query.set("page_token", options.pageToken);
  const suffix = query.size ? `?${query.toString()}` : "";
  return apiRequest<ActivityPage>(`/api/v1/activity${suffix}`, { token, ...(options.signal ? { signal: options.signal } : {}) });
}

/** Everything up to now is read; never moves backwards. */
export function markActivitySeen(token: string): Promise<{ seen_at: string | null; unread: 0 }> {
  return apiRequest(`/api/v1/activity/seen`, { method: "POST", token });
}

/**
 * One line for an item, from the reader's point of view, as the words before
 * and after the document's title: ["Bo joined", " as an editor"].
 */
export function describe(item: ActivityItem, me: string): [string, string] {
  const actor = item.actor?.display_name || "Someone";
  const subject = item.subject?.id === me ? "you" : item.subject?.display_name || "someone";
  const role = item.role ? ROLE_WORDS[item.role] : "";
  switch (item.kind) {
    case "document_edited":
      return [`${actor} edited`, ""];
    case "member_joined":
      return [`${actor} joined`, role ? ` as ${role}` : ""];
    case "member_left":
      return [`${actor} left`, ""];
    case "member_removed":
      return [`${actor} removed ${subject} from`, ""];
    case "role_changed":
      return item.subject?.id === item.actor?.id ? [`${actor} is now ${role || "a member"} on`, ""] : [`${actor} made ${subject} ${role || "a member"} on`, ""];
    case "ownership_transferred":
      return [`${actor} made ${subject} the owner of`, ""];
  }
}

const ROLE_WORDS: Record<Role, string> = { owner: "the owner", editor: "an editor", commenter: "a commenter", viewer: "a viewer" };
