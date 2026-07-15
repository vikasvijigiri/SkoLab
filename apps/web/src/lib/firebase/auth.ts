import {
  createUserWithEmailAndPassword,
  signInWithEmailAndPassword,
  signInWithPopup,
  signInAnonymously,
  GoogleAuthProvider,
  signOut as fbSignOut,
  updateProfile,
  type User,
} from "firebase/auth";
import { doc, getDoc, setDoc, updateDoc, deleteDoc, serverTimestamp } from "firebase/firestore";
import { requireAuth, requireDb } from "./client";
import type { SkoLabUser } from "@/lib/types";

export async function signUpWithEmail(email: string, password: string, name: string) {
  const cred = await createUserWithEmailAndPassword(requireAuth(), email, password);
  await updateProfile(cred.user, { displayName: name });
  await createResearcherProfile(cred.user, name);
  return cred.user;
}

export async function signInWithEmail(email: string, password: string) {
  const cred = await signInWithEmailAndPassword(requireAuth(), email, password);
  return cred.user;
}

export async function signInWithGoogle() {
  const provider = new GoogleAuthProvider();
  const cred = await signInWithPopup(requireAuth(), provider);
  await createResearcherProfile(cred.user, cred.user.displayName ?? "Researcher");
  return cred.user;
}

export async function signInAsGuest() {
  const cred = await signInAnonymously(requireAuth());
  return cred.user;
}

export async function signOutUser() {
  await fbSignOut(requireAuth());
}

/** Mirrors AuthManager.saveUserToFirestore — creates `researchers/{uid}` if it doesn't exist yet. */
export async function createResearcherProfile(user: User, name: string) {
  const ref = doc(requireDb(), "researchers", user.uid);
  const existing = await getDoc(ref);
  if (existing.exists()) return;

  const profile: Partial<SkoLabUser> = {
    uid: user.uid,
    name,
    username: user.email?.split("@")[0] ?? user.uid.slice(0, 8),
    authorName: name,
    email: user.email ?? "",
    phone: user.phoneNumber ?? "",
    researchFocus: "",
    complexityScore: 0,
    savedPapers: [],
    isOnline: true,
    emailVerified: user.emailVerified,
    academicStatus: "Researcher",
    cvUri: "",
    cvFileName: "",
    about: "",
    openAlexId: "",
    lastActive: Date.now(),
  };
  await setDoc(ref, { ...profile, createdAt: serverTimestamp() });
}

export async function getResearcherProfile(uid: string): Promise<SkoLabUser | null> {
  const snap = await getDoc(doc(requireDb(), "researchers", uid));
  return snap.exists() ? (snap.data() as SkoLabUser) : null;
}

export async function updateResearcherProfile(
  uid: string,
  fields: Partial<Pick<SkoLabUser, "name" | "researchFocus" | "academicStatus" | "about">>
) {
  await updateDoc(doc(requireDb(), "researchers", uid), { ...fields, lastActive: Date.now() });
}

export async function deleteResearcherProfile(uid: string) {
  await deleteDoc(doc(requireDb(), "researchers", uid));
}
