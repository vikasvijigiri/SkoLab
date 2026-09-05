import {
  createUserWithEmailAndPassword,
  signInWithEmailAndPassword,
  signInWithRedirect,
  getRedirectResult,
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

/**
 * Redirect-based, not signInWithPopup: the popup flow depends on a hidden
 * cross-origin iframe (on Firebase's authDomain) relaying the result back to
 * this origin via postMessage/shared storage. Confirmed live in production
 * (Sentry SKOLAB-WEB-1) that this relay silently breaks under Edge's default
 * tracking-prevention setting -- the popup completes Google's own screens
 * fine, then fails on the follow-up call that depends on the relay, with a
 * misleading "missing OAuth credential" error nowhere near the real cause.
 * A full-page redirect never needs that cross-origin relay at all, so it
 * isn't exposed to this class of failure -- confirmed by two Console-side
 * fixes (JS origins, client secret) NOT changing the error, then a different
 * browser working immediately, which pointed at the browser/relay, not
 * server config.
 *
 * This function does not return a user -- the browser navigates away to
 * Google and back. See completeGoogleRedirectSignIn, which every caller
 * must run once on mount to pick up the result after the round trip.
 */
export async function signInWithGoogle(): Promise<void> {
  const provider = new GoogleAuthProvider();
  await signInWithRedirect(requireAuth(), provider);
}

/**
 * Completes the redirect started by signInWithGoogle. Firebase redirects
 * back to the exact page that called signInWithRedirect, so this belongs in
 * a `useEffect` on mount in that same page (login/signup) -- not a one-time
 * global listener, since which page it should navigate to next (home vs.
 * onboarding) depends on which page initiated the sign-in.
 *
 * Returns the signed-in user if this page load is completing a real
 * redirect round trip, or null for an ordinary page visit (the common case).
 */
export async function completeGoogleRedirectSignIn(): Promise<User | null> {
  const cred = await getRedirectResult(requireAuth());
  if (!cred) return null;
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
