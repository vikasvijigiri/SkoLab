import type { CollabMember } from "@/lib/types";

/**
 * Matches literal `@Name` mentions in a sent CoLab chat message against the
 * project's real member list — case-insensitive exact match on display
 * name, no fuzzy matching and no autocomplete UI (decisions/0022's mention
 * kind: plain-text parsing on send is sufficient; a rich mention-picker is
 * out of scope). Excludes the sender, so a stray self-mention never queues
 * a notification to yourself, and de-dupes so mentioning someone twice in
 * one message only notifies them once.
 */
export function parseMentionedMembers(
  text: string,
  members: CollabMember[],
  senderUid: string,
): CollabMember[] {
  const lower = text.toLowerCase();
  const out: CollabMember[] = [];
  for (const m of members) {
    if (m.uid === senderUid) continue;
    const name = m.name?.trim();
    if (!name) continue;
    if (lower.includes(`@${name.toLowerCase()}`)) out.push(m);
  }
  return out;
}
