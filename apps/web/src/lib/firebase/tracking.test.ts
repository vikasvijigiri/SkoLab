import { describe, expect, it, vi, beforeEach } from "vitest";
import { setDoc, deleteDoc } from "firebase/firestore";
import { trackResearcher, untrackResearcher, trackTopic, untrackTopic } from "./tracking";

// The exact Firestore path and document shape matter here: a separate
// Signals workstream reads `users/{uid}/tracked_researchers/{authorId}`
// directly, so a rename or reshape here would silently break it.

beforeEach(() => {
  vi.mocked(setDoc).mockClear();
  vi.mocked(deleteDoc).mockClear();
});

describe("trackResearcher", () => {
  it("writes to users/{uid}/tracked_researchers/{authorId} with the exact field shape", async () => {
    await trackResearcher("uid-1", "A5000000001", "Ada Lovelace");

    const [ref, data] = vi.mocked(setDoc).mock.calls.at(-1) as unknown as [
      { path: unknown[] },
      Record<string, unknown>,
    ];
    // First path segment is the (mocked) db handle — the rest is the slash path.
    expect(ref.path.slice(1)).toEqual(["users", "uid-1", "tracked_researchers", "A5000000001"]);
    expect(data.authorId).toBe("A5000000001");
    expect(data.name).toBe("Ada Lovelace");
    expect(data.trackedAt).toBeDefined();
  });
});

describe("untrackResearcher", () => {
  it("deletes the same document path", async () => {
    await untrackResearcher("uid-1", "A5000000001");

    const [ref] = vi.mocked(deleteDoc).mock.calls.at(-1) as unknown as [{ path: unknown[] }];
    expect(ref.path.slice(1)).toEqual(["users", "uid-1", "tracked_researchers", "A5000000001"]);
  });
});

// The topic half of decision 0021 — same exact-shape contract, since the Go
// gateway's tracked_topic_activity source reads this collection directly.

describe("trackTopic", () => {
  it("writes to users/{uid}/tracked_topics/{topicId} with the exact field shape", async () => {
    await trackTopic("uid-1", "T10001", "Quantum computing");

    const [ref, data] = vi.mocked(setDoc).mock.calls.at(-1) as unknown as [
      { path: unknown[] },
      Record<string, unknown>,
    ];
    expect(ref.path.slice(1)).toEqual(["users", "uid-1", "tracked_topics", "T10001"]);
    expect(data.topicId).toBe("T10001");
    expect(data.name).toBe("Quantum computing");
    expect(data.trackedAt).toBeDefined();
  });
});

describe("untrackTopic", () => {
  it("deletes the same document path", async () => {
    await untrackTopic("uid-1", "T10001");

    const [ref] = vi.mocked(deleteDoc).mock.calls.at(-1) as unknown as [{ path: unknown[] }];
    expect(ref.path.slice(1)).toEqual(["users", "uid-1", "tracked_topics", "T10001"]);
  });
});
