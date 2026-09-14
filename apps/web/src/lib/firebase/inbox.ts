import { collection, addDoc } from "firebase/firestore";
import { requireDb } from "./client";

/**
 * Writes a lightweight "something happened to you" event into the
 * recipient's own `users/{uid}/inbox` queue — the client-writable producer
 * for the `mention`/`invite` ActivityType kinds (decisions/0022), which had
 * no backend producer until now. The Go gateway's activity feed handler
 * (internal/activity/activity.go's `inboxItems`) reads and immediately
 * deletes each doc the next time the recipient's feed is fetched, folding it
 * into the same `ActivityItem` stream citations/tracked researchers/tracked
 * topics already flow through — no separate UI path.
 *
 * Direct client<->Firestore write, matching decision 0004's established
 * pattern (see tracking.ts, cvShare.ts): no REST/Go write endpoint.
 *
 * Callers: ShareModal.tsx (a real invite, right after `inviteMember`
 * succeeds) and ChatTab.tsx (an `@Name` mention matched against the
 * project's real member list). See firestore.rules for who may write here —
 * only as yourself (`actor.id`), and only into someone *else's* inbox.
 */
export interface InboxEventInput {
  /** The recipient — this event is written into *their* inbox. */
  toUid: string;
  type: "mention" | "invite";
  actor: { id: string; display_name: string };
  /** Short, human context — the project name for an invite, a message
   *  excerpt for a mention. Never fabricated; omit rather than guess. */
  why?: string;
  href: string;
}

export async function writeInboxEvent(opts: InboxEventInput): Promise<void> {
  await addDoc(collection(requireDb(), "users", opts.toUid, "inbox"), {
    type: opts.type,
    verb: opts.type === "invite" ? "invited you" : "mentioned you",
    actor: opts.actor,
    why: opts.why ?? "",
    href: opts.href,
    ts: new Date().toISOString(),
  });
}
