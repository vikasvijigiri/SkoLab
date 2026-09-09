import {
  collection,
  doc,
  addDoc,
  setDoc,
  updateDoc,
  deleteDoc,
  query,
  where,
  orderBy,
  onSnapshot,
  arrayUnion,
  getDocs,
  serverTimestamp,
  type Unsubscribe,
  type FirestoreError,
} from "firebase/firestore";
import { requireDb } from "./client";
import type {
  CollabProject,
  CollabRole,
  CollabDocument,
  CollabPresence,
  CollabMessage,
  CollabTask,
  CollabMeeting,
  SkoLabUser,
} from "@/lib/types";

// ── Roles (Overleaf model) ───────────────────────────────────────────────────
// owner: everything + manage members + delete project
// editor: edit documents, chat, tasks/meetings
// reviewer: read documents, chat (comments), no direct edits
// viewer: read-only everywhere

export function roleFor(project: Pick<CollabProject, "ownerUid" | "members">, uid: string | undefined): CollabRole | null {
  if (!uid) return null;
  if (project.ownerUid === uid) return "owner";
  const m = project.members.find((x) => x.uid === uid);
  if (!m) return null;
  return m.role ?? "editor"; // legacy rows had no role
}

export const canEdit = (r: CollabRole | null) => r === "owner" || r === "editor";
export const canManage = (r: CollabRole | null) => r === "owner";
export const canComment = (r: CollabRole | null) => r === "owner" || r === "editor" || r === "reviewer";

// Mirrors CoLabWorkspaceScreen.kt's Firestore model — `collabs_groups/{id}` with
// messages/tasks/meetings subcollections. No REST backend exists for this (see
// research notes); the web client talks to Firestore directly, same as mobile,
// so projects created on either platform stay in sync.
//
// Every subscribe* function takes an `onError` callback — Firestore's onSnapshot
// throws into a void if you omit the third argument, which surfaces as an
// "Uncaught Error in snapshot listener" that crashes the whole page in dev
// instead of a handleable error (e.g. a security-rules permission-denied).

export type SubscribeErrorHandler = (error: FirestoreError) => void;

// `requireDb()` throws synchronously while the query is being *built* —
// before onSnapshot's own onError callback is ever registered. Without this
// wrapper, that throw (e.g. "Firebase is not configured") propagates straight
// out of the subscribe* call, and since there's no error.tsx boundary
// anywhere in the app, it used to crash the route instead of surfacing
// through the same friendly-error path as every other Firestore failure.
function safeSubscribe(run: () => Unsubscribe, onError?: SubscribeErrorHandler): Unsubscribe {
  try {
    return run();
  } catch (err) {
    onError?.(err as FirestoreError);
    return () => {};
  }
}

export function subscribeProjects(
  uid: string,
  cb: (projects: CollabProject[]) => void,
  onError?: SubscribeErrorHandler
): Unsubscribe {
  return safeSubscribe(() => {
    const q = query(collection(requireDb(), "collabs_groups"), where("memberUids", "array-contains", uid));
    return onSnapshot(
      q,
      (snap) => cb(snap.docs.map((d) => ({ id: d.id, ...d.data() } as CollabProject))),
      onError
    );
  }, onError);
}

export async function createProject(opts: {
  name: string;
  description: string;
  ownerUid: string;
  ownerName: string;
  ownerEmail: string;
}) {
  const ref = await addDoc(collection(requireDb(), "collabs_groups"), {
    name: opts.name,
    description: opts.description,
    ownerUid: opts.ownerUid,
    ownerName: opts.ownerName,
    members: [{ uid: opts.ownerUid, name: opts.ownerName, email: opts.ownerEmail, role: "owner" }],
    memberUids: [opts.ownerUid],
    recentEquations: "",
    manuscriptProgress: 0,
    manuscriptDraft: "",
    createdAt: serverTimestamp(),
    updatedAt: Date.now(),
    updatedByName: opts.ownerName,
  });
  // Seed the first document so the editor opens onto something.
  await setDoc(doc(requireDb(), "collabs_groups", ref.id, "documents", "main"), {
    title: "main",
    body: "",
    order: 0,
    updatedAt: Date.now(),
    updatedByUid: opts.ownerUid,
    updatedByName: opts.ownerName,
  });
  return ref.id;
}

export async function deleteProject(projectId: string) {
  const subcollections = ["messages", "tasks", "meetings", "activity", "documents", "presence"] as const;
  for (const sub of subcollections) {
    const snap = await getDocs(collection(requireDb(), "collabs_groups", projectId, sub));
    await Promise.all(snap.docs.map((d) => deleteDoc(d.ref)));
  }
  await deleteDoc(doc(requireDb(), "collabs_groups", projectId));
}

export async function updateEquations(projectId: string, latex: string) {
  await updateDoc(doc(requireDb(), "collabs_groups", projectId), { recentEquations: latex });
}

export async function updateManuscript(projectId: string, draft: string, progress: number) {
  await updateDoc(doc(requireDb(), "collabs_groups", projectId), {
    manuscriptDraft: draft,
    manuscriptProgress: progress,
  });
}

export function subscribeMessages(
  projectId: string,
  cb: (messages: CollabMessage[]) => void,
  onError?: SubscribeErrorHandler
): Unsubscribe {
  return safeSubscribe(() => {
    const q = query(collection(requireDb(), "collabs_groups", projectId, "messages"), orderBy("timestamp", "asc"));
    return onSnapshot(
      q,
      (snap) => cb(snap.docs.map((d) => ({ id: d.id, ...d.data() } as CollabMessage))),
      onError
    );
  }, onError);
}

export async function sendMessage(projectId: string, senderUid: string, senderName: string, text: string) {
  await addDoc(collection(requireDb(), "collabs_groups", projectId, "messages"), {
    senderUid,
    senderName,
    text,
    timestamp: Date.now(),
  });
}

export function subscribeTasks(
  projectId: string,
  cb: (tasks: CollabTask[]) => void,
  onError?: SubscribeErrorHandler
): Unsubscribe {
  return safeSubscribe(
    () =>
      onSnapshot(
        collection(requireDb(), "collabs_groups", projectId, "tasks"),
        (snap) => cb(snap.docs.map((d) => ({ id: d.id, ...d.data() } as CollabTask))),
        onError
      ),
    onError
  );
}

export async function addTask(projectId: string, title: string, assignee?: string) {
  await addDoc(collection(requireDb(), "collabs_groups", projectId, "tasks"), {
    title,
    isCompleted: false,
    assignee: assignee ?? null,
  });
}

export async function toggleTask(projectId: string, taskId: string, isCompleted: boolean) {
  await updateDoc(doc(requireDb(), "collabs_groups", projectId, "tasks", taskId), { isCompleted });
}

export function subscribeMeetings(
  projectId: string,
  cb: (meetings: CollabMeeting[]) => void,
  onError?: SubscribeErrorHandler
): Unsubscribe {
  return safeSubscribe(() => {
    const q = query(collection(requireDb(), "collabs_groups", projectId, "meetings"), orderBy("timestamp", "asc"));
    return onSnapshot(
      q,
      (snap) => cb(snap.docs.map((d) => ({ id: d.id, ...d.data() } as CollabMeeting))),
      onError
    );
  }, onError);
}

export async function scheduleMeeting(projectId: string, title: string, when: string) {
  await addDoc(collection(requireDb(), "collabs_groups", projectId, "meetings"), {
    title,
    when,
    timestamp: Date.now(),
  });
}

export async function findResearcherByEmail(email: string): Promise<SkoLabUser | null> {
  const q = query(collection(requireDb(), "researchers"), where("email", "==", email));
  const snap = await getDocs(q);
  const first = snap.docs[0];
  if (!first) return null;
  return first.data() as SkoLabUser;
}

export async function inviteMember(
  projectId: string,
  member: { uid: string; name: string; email: string; phone?: string },
  role: CollabRole = "editor"
) {
  await updateDoc(doc(requireDb(), "collabs_groups", projectId), {
    members: arrayUnion({ ...member, role }),
    memberUids: arrayUnion(member.uid),
  });
}

/** Rewrites the whole members array — arrayRemove needs an exact object match,
 *  which the added `role` field and legacy rows without one make unreliable. */
export async function updateMemberRole(project: CollabProject, uid: string, role: CollabRole) {
  const members = project.members.map((m) => (m.uid === uid ? { ...m, role } : m));
  await updateDoc(doc(requireDb(), "collabs_groups", project.id), { members });
}

export async function removeMemberByUid(project: CollabProject, uid: string) {
  if (uid === project.ownerUid) return; // never remove the owner
  const members = project.members.filter((m) => m.uid !== uid);
  const memberUids = project.memberUids.filter((x) => x !== uid);
  await updateDoc(doc(requireDb(), "collabs_groups", project.id), { members, memberUids });
}

// ── Documents (Overleaf file list) ───────────────────────────────────────────

export function subscribeDocuments(
  projectId: string,
  cb: (docs: CollabDocument[]) => void,
  onError?: SubscribeErrorHandler
): Unsubscribe {
  return safeSubscribe(() => {
    const q = query(collection(requireDb(), "collabs_groups", projectId, "documents"), orderBy("order", "asc"));
    return onSnapshot(
      q,
      (snap) => cb(snap.docs.map((d) => ({ id: d.id, ...d.data() } as CollabDocument))),
      onError
    );
  }, onError);
}

export async function createDocument(
  projectId: string,
  title: string,
  order: number,
  by: { uid: string; name: string }
) {
  const ref = await addDoc(collection(requireDb(), "collabs_groups", projectId, "documents"), {
    title: title.trim() || "untitled",
    body: "",
    order,
    updatedAt: Date.now(),
    updatedByUid: by.uid,
    updatedByName: by.name,
  });
  return ref.id;
}

export async function updateDocument(
  projectId: string,
  docId: string,
  fields: Partial<Pick<CollabDocument, "title" | "body">>,
  by: { uid: string; name: string }
) {
  const now = Date.now();
  await updateDoc(doc(requireDb(), "collabs_groups", projectId, "documents", docId), {
    ...fields,
    updatedAt: now,
    updatedByUid: by.uid,
    updatedByName: by.name,
  });
  // Bump the project so the dashboard "updated" sort/label reflects the edit.
  await updateDoc(doc(requireDb(), "collabs_groups", projectId), {
    updatedAt: now,
    updatedByName: by.name,
  }).catch(() => {});
}

export async function deleteDocument(projectId: string, docId: string) {
  await deleteDoc(doc(requireDb(), "collabs_groups", projectId, "documents", docId));
}

/** One-time backfill: older projects have a single `manuscriptDraft` string and
 *  no documents subcollection. Move it into a `main` document on first open. */
export async function ensureMainDocument(project: CollabProject, by: { uid: string; name: string }) {
  const snap = await getDocs(collection(requireDb(), "collabs_groups", project.id, "documents"));
  if (!snap.empty) return;
  await setDoc(doc(requireDb(), "collabs_groups", project.id, "documents", "main"), {
    title: "main",
    body: project.manuscriptDraft || "",
    order: 0,
    updatedAt: Date.now(),
    updatedByUid: by.uid,
    updatedByName: by.name,
  });
}

// ── Presence (who's viewing) ─────────────────────────────────────────────────

export function subscribePresence(
  projectId: string,
  cb: (people: CollabPresence[]) => void,
  onError?: SubscribeErrorHandler
): Unsubscribe {
  return safeSubscribe(
    () =>
      onSnapshot(
        collection(requireDb(), "collabs_groups", projectId, "presence"),
        (snap) => {
          const cutoff = Date.now() - 45_000;
          cb(
            snap.docs
              .map((d) => d.data() as CollabPresence)
              .filter((p) => (p.lastSeen ?? 0) > cutoff)
          );
        },
        onError
      ),
    onError
  );
}

export async function heartbeatPresence(
  projectId: string,
  person: { uid: string; name: string },
  docId: string | null
) {
  await setDoc(doc(requireDb(), "collabs_groups", projectId, "presence", person.uid), {
    uid: person.uid,
    name: person.name,
    docId,
    lastSeen: Date.now(),
  });
}

export async function clearPresence(projectId: string, uid: string) {
  await deleteDoc(doc(requireDb(), "collabs_groups", projectId, "presence", uid)).catch(() => {});
}
