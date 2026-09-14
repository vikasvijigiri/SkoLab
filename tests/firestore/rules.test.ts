/**
 * Firestore security rules tests for firestore.rules — the rest of this repo
 * talks to Firestore directly from the client (decisions/0004), so this file
 * is the only place `collabs_groups` and `researchers` access control is
 * verified before it reaches production.
 *
 * Requires the Firestore emulator (Java 11+). Run with:
 *   npx firebase emulators:exec --only firestore \
 *     "npx vitest run --config tests/firestore/vitest.config.mts"
 *
 * Not wired into `npm test` or CI: this repo's CI runners aren't provisioned
 * for the emulator today. Run it locally before changing firestore.rules,
 * and see docs/plans/2026-09-11-colab-gap-closure.md for the follow-up to
 * wire a CI job once a Java toolchain is added to the pipeline.
 */
import { afterAll, beforeAll, beforeEach, describe, expect, it } from "vitest";
import {
  type RulesTestEnvironment,
  assertFails,
  assertSucceeds,
  initializeTestEnvironment,
} from "@firebase/rules-unit-testing";
import { readFileSync } from "node:fs";
import { doc, setDoc, getDoc, updateDoc, deleteDoc, collection, addDoc } from "firebase/firestore";

const PROJECT_ID = "skolab-rules-test";

let testEnv: RulesTestEnvironment;

beforeAll(async () => {
  testEnv = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: {
      rules: readFileSync("firestore.rules", "utf8"),
      host: "127.0.0.1",
      port: 8085,
    },
  });
});

afterAll(async () => {
  await testEnv?.cleanup();
});

beforeEach(async () => {
  await testEnv.clearFirestore();
});

const OWNER = "owner-uid";
const EDITOR = "editor-uid";
const VIEWER = "viewer-uid";
const OUTSIDER = "outsider-uid";

async function seedProject(overrides: Record<string, unknown> = {}) {
  // An override set to `undefined` means "omit this field" (simulating a
  // legacy doc that never had it) -- Firestore's client SDK rejects an
  // explicit `undefined` field value outright, so it must be deleted from
  // the payload, not merely spread over the default.
  const payload: Record<string, unknown> = {
    name: "Quantum foam",
    description: "",
    ownerUid: OWNER,
    ownerName: "Owner",
    members: [
      { uid: OWNER, name: "Owner", email: "o@x.edu", role: "owner" },
      { uid: EDITOR, name: "Editor", email: "e@x.edu", role: "editor" },
      { uid: VIEWER, name: "Viewer", email: "v@x.edu", role: "viewer" },
    ],
    memberUids: [OWNER, EDITOR, VIEWER],
    editorUids: [OWNER, EDITOR],
    commenterUids: [OWNER, EDITOR, VIEWER].filter((u) => u !== VIEWER),
    recentEquations: "",
    manuscriptProgress: 0,
    manuscriptDraft: "",
    ...overrides,
  };
  for (const key of Object.keys(payload)) {
    if (payload[key] === undefined) delete payload[key];
  }
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    await setDoc(doc(ctx.firestore(), "collabs_groups", "p1"), payload);
  });
}

describe("researchers/{uid}", () => {
  it("lets a signed-in user read any profile", async () => {
    await testEnv.withSecurityRulesDisabled(async (ctx) => {
      await setDoc(doc(ctx.firestore(), "researchers", OWNER), { uid: OWNER, name: "Owner" });
    });
    const asOutsider = testEnv.authenticatedContext(OUTSIDER).firestore();
    await assertSucceeds(getDoc(doc(asOutsider, "researchers", OWNER)));
  });

  it("blocks an anonymous (unauthenticated) reader", async () => {
    await testEnv.withSecurityRulesDisabled(async (ctx) => {
      await setDoc(doc(ctx.firestore(), "researchers", OWNER), { uid: OWNER, name: "Owner" });
    });
    const anon = testEnv.unauthenticatedContext().firestore();
    await assertFails(getDoc(doc(anon, "researchers", OWNER)));
  });

  it("lets you write your own profile document", async () => {
    const asOwner = testEnv.authenticatedContext(OWNER).firestore();
    await assertSucceeds(setDoc(doc(asOwner, "researchers", OWNER), { uid: OWNER, name: "Me" }));
  });

  it("blocks writing someone else's profile document", async () => {
    const asOutsider = testEnv.authenticatedContext(OUTSIDER).firestore();
    await assertFails(setDoc(doc(asOutsider, "researchers", OWNER), { uid: OWNER, name: "Hijacked" }));
  });
});

describe("collabs_groups/{projectId}", () => {
  it("lets a member read the project", async () => {
    await seedProject();
    const asEditor = testEnv.authenticatedContext(EDITOR).firestore();
    await assertSucceeds(getDoc(doc(asEditor, "collabs_groups", "p1")));
  });

  it("blocks a non-member from reading the project", async () => {
    await seedProject();
    const asOutsider = testEnv.authenticatedContext(OUTSIDER).firestore();
    await assertFails(getDoc(doc(asOutsider, "collabs_groups", "p1")));
  });

  it("lets the creator create a project naming themself as owner and sole member", async () => {
    const asOwner = testEnv.authenticatedContext(OWNER).firestore();
    await assertSucceeds(
      addDoc(collection(asOwner, "collabs_groups"), {
        name: "New project",
        description: "",
        ownerUid: OWNER,
        ownerName: "Owner",
        members: [{ uid: OWNER, name: "Owner", email: "o@x.edu", role: "owner" }],
        memberUids: [OWNER],
        editorUids: [OWNER],
        commenterUids: [OWNER],
        recentEquations: "",
        manuscriptProgress: 0,
        manuscriptDraft: "",
      })
    );
  });

  it("blocks creating a project owned by someone else", async () => {
    const asOutsider = testEnv.authenticatedContext(OUTSIDER).firestore();
    await assertFails(
      addDoc(collection(asOutsider, "collabs_groups"), {
        name: "Spoofed",
        ownerUid: OWNER,
        memberUids: [OWNER],
      })
    );
  });

  it("lets an editor update non-membership fields", async () => {
    await seedProject();
    const asEditor = testEnv.authenticatedContext(EDITOR).firestore();
    await assertSucceeds(updateDoc(doc(asEditor, "collabs_groups", "p1"), { recentEquations: "E=mc^2" }));
  });

  it("blocks a viewer from updating project fields", async () => {
    await seedProject();
    const asViewer = testEnv.authenticatedContext(VIEWER).firestore();
    await assertFails(updateDoc(doc(asViewer, "collabs_groups", "p1"), { recentEquations: "nope" }));
  });

  it("blocks an editor (non-owner) from changing membership", async () => {
    await seedProject();
    const asEditor = testEnv.authenticatedContext(EDITOR).firestore();
    await assertFails(
      updateDoc(doc(asEditor, "collabs_groups", "p1"), {
        members: [{ uid: EDITOR, name: "Editor", email: "e@x.edu", role: "owner" }],
        ownerUid: EDITOR,
      })
    );
  });

  it("lets the owner change membership", async () => {
    await seedProject();
    const asOwner = testEnv.authenticatedContext(OWNER).firestore();
    await assertSucceeds(
      updateDoc(doc(asOwner, "collabs_groups", "p1"), {
        members: [
          { uid: OWNER, name: "Owner", email: "o@x.edu", role: "owner" },
          { uid: EDITOR, name: "Editor", email: "e@x.edu", role: "viewer" },
        ],
        memberUids: [OWNER, EDITOR],
        editorUids: [OWNER],
        commenterUids: [OWNER],
      })
    );
  });

  it("blocks anyone but the owner from deleting the project", async () => {
    await seedProject();
    const asEditor = testEnv.authenticatedContext(EDITOR).firestore();
    await assertFails(deleteDoc(doc(asEditor, "collabs_groups", "p1")));
  });

  it("falls back to any-member-may-edit for legacy projects with no editorUids", async () => {
    await seedProject({ editorUids: undefined, commenterUids: undefined });
    const asViewer = testEnv.authenticatedContext(VIEWER).firestore();
    // Legacy fallback is coarse (isMember, not real role check) — matches
    // today's de facto behavior for projects written before this migration.
    await assertSucceeds(updateDoc(doc(asViewer, "collabs_groups", "p1"), { recentEquations: "legacy ok" }));
  });
});

describe("collabs_groups/{projectId}/messages", () => {
  it("lets a commenter (editor) post a message as themself", async () => {
    await seedProject();
    const asEditor = testEnv.authenticatedContext(EDITOR).firestore();
    await assertSucceeds(
      addDoc(collection(asEditor, "collabs_groups", "p1", "messages"), {
        senderUid: EDITOR,
        senderName: "Editor",
        text: "hi",
        timestamp: Date.now(),
      })
    );
  });

  it("blocks a viewer from posting a message", async () => {
    await seedProject();
    const asViewer = testEnv.authenticatedContext(VIEWER).firestore();
    await assertFails(
      addDoc(collection(asViewer, "collabs_groups", "p1", "messages"), {
        senderUid: VIEWER,
        senderName: "Viewer",
        text: "should fail",
        timestamp: Date.now(),
      })
    );
  });

  it("blocks impersonating another sender", async () => {
    await seedProject();
    const asEditor = testEnv.authenticatedContext(EDITOR).firestore();
    await assertFails(
      addDoc(collection(asEditor, "collabs_groups", "p1", "messages"), {
        senderUid: OWNER,
        senderName: "Owner",
        text: "spoofed",
        timestamp: Date.now(),
      })
    );
  });

  it("blocks editing a sent message", async () => {
    let msgId = "";
    await testEnv.withSecurityRulesDisabled(async (ctx) => {
      const ref = await addDoc(collection(ctx.firestore(), "collabs_groups", "p1", "messages"), {
        senderUid: EDITOR,
        senderName: "Editor",
        text: "hi",
        timestamp: Date.now(),
      });
      msgId = ref.id;
    });
    await seedProject();
    const asEditor = testEnv.authenticatedContext(EDITOR).firestore();
    await assertFails(updateDoc(doc(asEditor, "collabs_groups", "p1", "messages", msgId), { text: "edited" }));
  });
});

describe("users/{uid} (decisions/0022 Signals + decisions/0021 Track)", () => {
  it("lets the owner read and write their own notification settings", async () => {
    const asOwner = testEnv.authenticatedContext(OWNER).firestore();
    await assertSucceeds(
      setDoc(doc(asOwner, "users", OWNER, "settings", "notifications"), { citations: "off" })
    );
    await assertSucceeds(getDoc(doc(asOwner, "users", OWNER, "settings", "notifications")));
  });

  it("lets an anonymous (guest) uid write their own notification settings — no account required", async () => {
    // authenticatedContext simulates any signed-in uid, anonymous auth included
    // (the app treats an anonymous Firebase user as "signed in" — see
    // useAuth's isAnonymous usage) — the rule must not distinguish the two.
    const asGuest = testEnv.authenticatedContext("guest-uid").firestore();
    await assertSucceeds(
      setDoc(doc(asGuest, "users", "guest-uid", "settings", "notifications"), { citations: "realtime" })
    );
  });

  it("blocks reading or writing someone else's notification settings", async () => {
    const asOutsider = testEnv.authenticatedContext(OUTSIDER).firestore();
    await assertFails(getDoc(doc(asOutsider, "users", OWNER, "settings", "notifications")));
    await assertFails(setDoc(doc(asOutsider, "users", OWNER, "settings", "notifications"), { citations: "off" }));
  });

  it("lets the owner track a researcher naming themself honestly", async () => {
    const asOwner = testEnv.authenticatedContext(OWNER).firestore();
    await assertSucceeds(
      setDoc(doc(asOwner, "users", OWNER, "tracked_researchers", "A123"), {
        authorId: "A123",
        name: "Ada Lovelace",
        trackedAt: Date.now(),
      })
    );
  });

  it("blocks writing a tracked_researchers doc whose authorId doesn't match its id", async () => {
    const asOwner = testEnv.authenticatedContext(OWNER).firestore();
    await assertFails(
      setDoc(doc(asOwner, "users", OWNER, "tracked_researchers", "A123"), {
        authorId: "A999",
        name: "Spoofed",
        trackedAt: Date.now(),
      })
    );
  });

  it("blocks tracking a researcher into someone else's list", async () => {
    const asOutsider = testEnv.authenticatedContext(OUTSIDER).firestore();
    await assertFails(
      setDoc(doc(asOutsider, "users", OWNER, "tracked_researchers", "A123"), {
        authorId: "A123",
        name: "Ada Lovelace",
        trackedAt: Date.now(),
      })
    );
  });

  it("blocks any client write to notification_state (server-only watermark)", async () => {
    const asOwner = testEnv.authenticatedContext(OWNER).firestore();
    await assertFails(setDoc(doc(asOwner, "users", OWNER, "notification_state", "state"), { lastSeenCitationCount: 5 }));
  });

  it("lets the owner read notification_state but blocks an outsider", async () => {
    await testEnv.withSecurityRulesDisabled(async (ctx) => {
      await setDoc(doc(ctx.firestore(), "users", OWNER, "notification_state", "state"), { lastSeenCitationCount: 5 });
    });
    const asOwner = testEnv.authenticatedContext(OWNER).firestore();
    await assertSucceeds(getDoc(doc(asOwner, "users", OWNER, "notification_state", "state")));
    const asOutsider = testEnv.authenticatedContext(OUTSIDER).firestore();
    await assertFails(getDoc(doc(asOutsider, "users", OWNER, "notification_state", "state")));
  });

  it("lets the owner track a topic naming themself honestly", async () => {
    const asOwner = testEnv.authenticatedContext(OWNER).firestore();
    await assertSucceeds(
      setDoc(doc(asOwner, "users", OWNER, "tracked_topics", "T123"), {
        topicId: "T123",
        name: "Quantum computing",
        trackedAt: Date.now(),
      })
    );
  });

  it("blocks writing a tracked_topics doc whose topicId doesn't match its id", async () => {
    const asOwner = testEnv.authenticatedContext(OWNER).firestore();
    await assertFails(
      setDoc(doc(asOwner, "users", OWNER, "tracked_topics", "T123"), {
        topicId: "T999",
        name: "Spoofed",
        trackedAt: Date.now(),
      })
    );
  });

  it("blocks tracking a topic into someone else's list", async () => {
    const asOutsider = testEnv.authenticatedContext(OUTSIDER).firestore();
    await assertFails(
      setDoc(doc(asOutsider, "users", OWNER, "tracked_topics", "T123"), {
        topicId: "T123",
        name: "Quantum computing",
        trackedAt: Date.now(),
      })
    );
  });

  it("lets the owner read and delete their own tracked_topics doc", async () => {
    await testEnv.withSecurityRulesDisabled(async (ctx) => {
      await setDoc(doc(ctx.firestore(), "users", OWNER, "tracked_topics", "T123"), {
        topicId: "T123",
        name: "Quantum computing",
        trackedAt: Date.now(),
      });
    });
    const asOwner = testEnv.authenticatedContext(OWNER).firestore();
    await assertSucceeds(getDoc(doc(asOwner, "users", OWNER, "tracked_topics", "T123")));
    await assertSucceeds(deleteDoc(doc(asOwner, "users", OWNER, "tracked_topics", "T123")));
  });
});

describe("users/{uid}/inbox (mention/invite queue)", () => {
  it("lets a sender write a mention/invite naming themself as actor into someone else's inbox", async () => {
    const asSender = testEnv.authenticatedContext(EDITOR).firestore();
    await assertSucceeds(
      addDoc(collection(asSender, "users", OWNER, "inbox"), {
        type: "mention",
        verb: "mentioned you",
        actor: { id: EDITOR, display_name: "Editor" },
        why: "check this out",
        href: "/workspace/p1",
        ts: new Date().toISOString(),
      })
    );
  });

  it("blocks writing an inbox entry naming someone else as the actor (forged mention)", async () => {
    const asSender = testEnv.authenticatedContext(EDITOR).firestore();
    await assertFails(
      addDoc(collection(asSender, "users", OWNER, "inbox"), {
        type: "mention",
        actor: { id: OUTSIDER, display_name: "Spoofed" },
        why: "forged",
        href: "/workspace/p1",
        ts: new Date().toISOString(),
      })
    );
  });

  it("blocks writing an inbox entry into your own inbox", async () => {
    const asOwner = testEnv.authenticatedContext(OWNER).firestore();
    await assertFails(
      addDoc(collection(asOwner, "users", OWNER, "inbox"), {
        type: "mention",
        actor: { id: OWNER, display_name: "Owner" },
        why: "self mention",
        href: "/workspace/p1",
        ts: new Date().toISOString(),
      })
    );
  });

  it("lets the recipient read and delete their own inbox, but blocks an outsider", async () => {
    let itemId = "";
    await testEnv.withSecurityRulesDisabled(async (ctx) => {
      const ref = await addDoc(collection(ctx.firestore(), "users", OWNER, "inbox"), {
        type: "invite",
        actor: { id: EDITOR, display_name: "Editor" },
        why: "Quantum foam",
        href: "/workspace/p1",
        ts: new Date().toISOString(),
      });
      itemId = ref.id;
    });
    const asOwner = testEnv.authenticatedContext(OWNER).firestore();
    await assertSucceeds(getDoc(doc(asOwner, "users", OWNER, "inbox", itemId)));

    const asOutsider = testEnv.authenticatedContext(OUTSIDER).firestore();
    await assertFails(getDoc(doc(asOutsider, "users", OWNER, "inbox", itemId)));
    await assertFails(deleteDoc(doc(asOutsider, "users", OWNER, "inbox", itemId)));

    await assertSucceeds(deleteDoc(doc(asOwner, "users", OWNER, "inbox", itemId)));
  });
});

describe("collabs_groups/{projectId}/presence", () => {
  it("lets a member set their own presence", async () => {
    await seedProject();
    const asViewer = testEnv.authenticatedContext(VIEWER).firestore();
    await assertSucceeds(
      setDoc(doc(asViewer, "collabs_groups", "p1", "presence", VIEWER), {
        uid: VIEWER,
        name: "Viewer",
        docId: null,
        lastSeen: Date.now(),
      })
    );
  });

  it("blocks setting someone else's presence", async () => {
    await seedProject();
    const asViewer = testEnv.authenticatedContext(VIEWER).firestore();
    await assertFails(
      setDoc(doc(asViewer, "collabs_groups", "p1", "presence", EDITOR), {
        uid: EDITOR,
        name: "Spoofed",
        docId: null,
        lastSeen: Date.now(),
      })
    );
  });
});
