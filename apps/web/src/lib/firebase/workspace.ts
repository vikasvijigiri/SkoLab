import {
  collection,
  doc,
  addDoc,
  updateDoc,
  deleteDoc,
  query,
  where,
  orderBy,
  onSnapshot,
  arrayUnion,
  arrayRemove,
  getDocs,
  serverTimestamp,
  type Unsubscribe,
  type FirestoreError,
} from "firebase/firestore";
import { requireDb } from "./client";
import type { CollabProject, CollabMessage, CollabTask, CollabMeeting, SkoLabUser } from "@/lib/types";

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

export function subscribeProjects(
  uid: string,
  cb: (projects: CollabProject[]) => void,
  onError?: SubscribeErrorHandler
): Unsubscribe {
  const q = query(collection(requireDb(), "collabs_groups"), where("memberUids", "array-contains", uid));
  return onSnapshot(
    q,
    (snap) => cb(snap.docs.map((d) => ({ id: d.id, ...d.data() } as CollabProject))),
    onError
  );
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
    members: [{ uid: opts.ownerUid, name: opts.ownerName, email: opts.ownerEmail }],
    memberUids: [opts.ownerUid],
    recentEquations: "",
    manuscriptProgress: 0,
    manuscriptDraft: "",
    createdAt: serverTimestamp(),
  });
  return ref.id;
}

export async function deleteProject(projectId: string) {
  const subcollections = ["messages", "tasks", "meetings", "activity"] as const;
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
  const q = query(collection(requireDb(), "collabs_groups", projectId, "messages"), orderBy("timestamp", "asc"));
  return onSnapshot(
    q,
    (snap) => cb(snap.docs.map((d) => ({ id: d.id, ...d.data() } as CollabMessage))),
    onError
  );
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
  return onSnapshot(
    collection(requireDb(), "collabs_groups", projectId, "tasks"),
    (snap) => cb(snap.docs.map((d) => ({ id: d.id, ...d.data() } as CollabTask))),
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
  const q = query(collection(requireDb(), "collabs_groups", projectId, "meetings"), orderBy("timestamp", "asc"));
  return onSnapshot(
    q,
    (snap) => cb(snap.docs.map((d) => ({ id: d.id, ...d.data() } as CollabMeeting))),
    onError
  );
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
  if (snap.empty) return null;
  return snap.docs[0].data() as SkoLabUser;
}

export async function inviteMember(
  projectId: string,
  member: { uid: string; name: string; email: string; phone?: string }
) {
  await updateDoc(doc(requireDb(), "collabs_groups", projectId), {
    members: arrayUnion(member),
    memberUids: arrayUnion(member.uid),
  });
}

export async function removeMember(
  projectId: string,
  member: { uid: string; name: string; email: string; phone?: string }
) {
  await updateDoc(doc(requireDb(), "collabs_groups", projectId), {
    members: arrayRemove(member),
    memberUids: arrayRemove(member.uid),
  });
}
