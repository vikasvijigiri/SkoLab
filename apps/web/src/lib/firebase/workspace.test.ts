import { describe, expect, it, vi, beforeEach } from "vitest";
import { getDocs, addDoc, updateDoc } from "firebase/firestore";
import {
  createProject,
  inviteMember,
  updateMemberRole,
  removeMemberByUid,
  findResearcherByOpenAlexId,
  roleFor,
} from "./workspace";
import type { CollabProject } from "@/lib/types";

const OWNER = { uid: "owner-1", name: "Owner", email: "owner@x.edu" };

// The test double in src/test/firestore.ts is a loose JS mock; real
// firebase/firestore types are still what TS checks calls against, so every
// mock read/write needs a cast through `unknown`.
type WritePayload = Record<string, unknown>;
const lastWrite = (fn: typeof addDoc | typeof updateDoc) =>
  (vi.mocked(fn).mock.calls.at(-1) as unknown as [unknown, WritePayload])[1];
const fakeSnap = (docs: { id: string; data: () => unknown }[]) =>
  ({ docs }) as unknown as Awaited<ReturnType<typeof getDocs>>;

beforeEach(() => {
  vi.mocked(addDoc).mockClear();
  vi.mocked(updateDoc).mockClear();
  vi.mocked(getDocs).mockReset();
  vi.mocked(getDocs).mockResolvedValue(fakeSnap([]));
});

describe("createProject", () => {
  it("seeds memberUids, editorUids and commenterUids with just the owner", async () => {
    await createProject({
      name: "Quantum foam",
      description: "",
      ownerUid: OWNER.uid,
      ownerName: OWNER.name,
      ownerEmail: OWNER.email,
    });

    const payload = lastWrite(addDoc);
    expect(payload.memberUids).toEqual([OWNER.uid]);
    expect(payload.editorUids).toEqual([OWNER.uid]);
    expect(payload.commenterUids).toEqual([OWNER.uid]);
  });
});

describe("deriveRoleArrays via role-changing calls", () => {
  const baseProject: CollabProject = {
    id: "p1",
    name: "Project",
    description: "",
    ownerUid: OWNER.uid,
    ownerName: OWNER.name,
    members: [
      { uid: OWNER.uid, name: OWNER.name, email: OWNER.email, role: "owner" },
    ],
    memberUids: [OWNER.uid],
    recentEquations: "",
    manuscriptProgress: 0,
    manuscriptDraft: "",
  };

  it("inviteMember puts an editor in editorUids and commenterUids", async () => {
    await inviteMember(baseProject, { uid: "peer-1", name: "Peer", email: "peer@x.edu" }, "editor");
    const payload = lastWrite(updateDoc);
    expect(payload.memberUids).toEqual([OWNER.uid, "peer-1"]);
    expect(payload.editorUids).toEqual([OWNER.uid, "peer-1"]);
    expect(payload.commenterUids).toEqual([OWNER.uid, "peer-1"]);
  });

  it("inviteMember puts a viewer in memberUids only, not editorUids or commenterUids", async () => {
    await inviteMember(baseProject, { uid: "peer-1", name: "Peer", email: "peer@x.edu" }, "viewer");
    const payload = lastWrite(updateDoc);
    expect(payload.memberUids).toEqual([OWNER.uid, "peer-1"]);
    expect(payload.editorUids).toEqual([OWNER.uid]);
    expect(payload.commenterUids).toEqual([OWNER.uid]);
  });

  it("inviteMember puts a reviewer in commenterUids but not editorUids", async () => {
    await inviteMember(baseProject, { uid: "peer-1", name: "Peer", email: "peer@x.edu" }, "reviewer");
    const payload = lastWrite(updateDoc);
    expect(payload.editorUids).toEqual([OWNER.uid]);
    expect(payload.commenterUids).toEqual([OWNER.uid, "peer-1"]);
  });

  it("updateMemberRole demoting an editor to viewer drops them from editorUids and commenterUids", async () => {
    const withEditor: CollabProject = {
      ...baseProject,
      members: [...baseProject.members, { uid: "peer-1", name: "Peer", email: "peer@x.edu", role: "editor" }],
      memberUids: [OWNER.uid, "peer-1"],
    };
    await updateMemberRole(withEditor, "peer-1", "viewer");
    const payload = lastWrite(updateDoc);
    expect(payload.editorUids).toEqual([OWNER.uid]);
    expect(payload.commenterUids).toEqual([OWNER.uid]);
    expect(payload.memberUids).toEqual([OWNER.uid, "peer-1"]); // still a member
  });

  it("removeMemberByUid drops the uid from every derived array", async () => {
    const withEditor: CollabProject = {
      ...baseProject,
      members: [...baseProject.members, { uid: "peer-1", name: "Peer", email: "peer@x.edu", role: "editor" }],
      memberUids: [OWNER.uid, "peer-1"],
    };
    await removeMemberByUid(withEditor, "peer-1");
    const payload = lastWrite(updateDoc);
    expect(payload.memberUids).toEqual([OWNER.uid]);
    expect(payload.editorUids).toEqual([OWNER.uid]);
    expect(payload.commenterUids).toEqual([OWNER.uid]);
  });

  it("removeMemberByUid refuses to remove the owner", async () => {
    await removeMemberByUid(baseProject, OWNER.uid);
    expect(updateDoc).not.toHaveBeenCalled();
  });
});

describe("findResearcherByOpenAlexId", () => {
  it("returns the matching researcher when one is linked", async () => {
    vi.mocked(getDocs).mockResolvedValueOnce(
      fakeSnap([{ id: "u9", data: () => ({ uid: "u9", name: "Marie Curie", email: "mc@x.edu" }) }]),
    );
    const result = await findResearcherByOpenAlexId("A5023888391");
    expect(result?.name).toBe("Marie Curie");
  });

  it("returns null — never a fabricated match — when no SkoLab account is linked", async () => {
    vi.mocked(getDocs).mockResolvedValueOnce(fakeSnap([]));
    const result = await findResearcherByOpenAlexId("A_unknown");
    expect(result).toBeNull();
  });
});

describe("roleFor", () => {
  const project: Pick<CollabProject, "ownerUid" | "members"> = {
    ownerUid: OWNER.uid,
    members: [
      { uid: OWNER.uid, name: OWNER.name, email: OWNER.email, role: "owner" },
      { uid: "legacy-1", name: "Legacy", email: "l@x.edu" }, // no role field
    ],
  };

  it("resolves the owner regardless of their row's role field", () => {
    expect(roleFor(project, OWNER.uid)).toBe("owner");
  });

  it("defaults a legacy row with no role to editor", () => {
    expect(roleFor(project, "legacy-1")).toBe("editor");
  });

  it("returns null for a non-member", () => {
    expect(roleFor(project, "stranger")).toBeNull();
  });
});
