import { describe, expect, it, vi, beforeEach } from "vitest";
import { addDoc } from "firebase/firestore";
import { writeInboxEvent } from "./inbox";

// The exact Firestore path and document shape matter here: the Go gateway's
// inboxItems reads users/{uid}/inbox directly (internal/activity/activity.go).

beforeEach(() => {
  vi.mocked(addDoc).mockClear();
});

describe("writeInboxEvent", () => {
  it("writes into the recipient's users/{uid}/inbox with the actor honestly attributed", async () => {
    await writeInboxEvent({
      toUid: "uid-recipient",
      type: "mention",
      actor: { id: "uid-sender", display_name: "Ada Lovelace" },
      why: "hey @You check this out",
      href: "/workspace/p1",
    });

    const [ref, data] = vi.mocked(addDoc).mock.calls.at(-1) as unknown as [
      { path: unknown[] },
      Record<string, unknown>,
    ];
    expect(ref.path.slice(1)).toEqual(["users", "uid-recipient", "inbox"]);
    expect(data.type).toBe("mention");
    expect(data.actor).toEqual({ id: "uid-sender", display_name: "Ada Lovelace" });
    expect(data.why).toBe("hey @You check this out");
    expect(data.href).toBe("/workspace/p1");
    expect(typeof data.ts).toBe("string");
  });

  it("defaults why to an empty string when omitted", async () => {
    await writeInboxEvent({
      toUid: "uid-recipient",
      type: "invite",
      actor: { id: "uid-sender", display_name: "Grace Hopper" },
      href: "/workspace/p2",
    });

    const [, data] = vi.mocked(addDoc).mock.calls.at(-1) as unknown as [unknown, Record<string, unknown>];
    expect(data.type).toBe("invite");
    expect(data.why).toBe("");
    expect(data.verb).toBe("invited you");
  });
});
