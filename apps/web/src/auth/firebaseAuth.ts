import { initializeApp } from "@firebase/app";
import {
  browserLocalPersistence,
  browserPopupRedirectResolver,
  createUserWithEmailAndPassword,
  getRedirectResult,
  GoogleAuthProvider,
  indexedDBLocalPersistence,
  initializeAuth,
  onAuthStateChanged,
  sendEmailVerification,
  sendPasswordResetEmail,
  signInWithEmailAndPassword,
  signInWithPopup,
  signInWithRedirect,
  signOut,
  updateProfile,
  type ActionCodeSettings,
  type User,
} from "@firebase/auth";
import type { FirebaseWebConfig } from "../config";
import { toAuthError } from "./errors";
import type { AuthService, AuthUser, SignInMethod } from "./types";

function methodOf(user: User): SignInMethod {
  const providers = user.providerData.map((p) => p.providerId);
  if (providers.includes("google.com")) return "google";
  if (providers.includes("password")) return "password";
  return "other";
}

function toUser(user: User): AuthUser {
  return {
    uid: user.uid,
    email: user.email,
    displayName: user.displayName,
    emailVerified: user.emailVerified,
    method: methodOf(user),
  };
}

/** Where links in Firebase emails return to. The domain must be authorized in Firebase. */
function continueTo(path: string): ActionCodeSettings {
  return { url: new URL(path, window.location.origin).toString() };
}

function isContinueUrlError(error: unknown): boolean {
  const code = (error as { code?: unknown } | null)?.code;
  return code === "auth/unauthorized-continue-uri" || code === "auth/invalid-continue-uri";
}

export function createFirebaseAuth(config: FirebaseWebConfig): AuthService {
  const app = initializeApp(config);
  const auth = initializeAuth(app, {
    // IndexedDB first; local storage where IndexedDB is unavailable.
    persistence: [indexedDBLocalPersistence, browserLocalPersistence],
    popupRedirectResolver: browserPopupRedirectResolver,
  });
  auth.useDeviceLanguage();

  const listeners = new Set<(user: AuthUser | null) => void>();
  const emit = () => {
    const user = auth.currentUser ? toUser(auth.currentUser) : null;
    listeners.forEach((listener) => listener(user));
  };
  onAuthStateChanged(auth, emit);
  // Completes a Google sign-in that fell back to a full-page redirect.
  getRedirectResult(auth).catch(() => undefined);

  const google = new GoogleAuthProvider();
  google.setCustomParameters({ prompt: "select_account" });

  const run = async <T>(action: () => Promise<T>): Promise<T> => {
    try {
      return await action();
    } catch (error) {
      throw toAuthError(error);
    }
  };

  const verify = async (user: User) => {
    try {
      await sendEmailVerification(user, continueTo("/verify-email"));
    } catch (error) {
      // An unlisted domain only loses the "continue" button in the email.
      if (!isContinueUrlError(error)) throw error;
      await sendEmailVerification(user);
    }
  };

  return {
    onChange(listener) {
      listeners.add(listener);
      // authStateReady resolves once persistence has been read, so the first
      // call never reports a signed-in user as signed out.
      void auth.authStateReady().then(() => {
        if (listeners.has(listener)) listener(auth.currentUser ? toUser(auth.currentUser) : null);
      });
      return () => listeners.delete(listener);
    },
    signInWithEmail: (email, password) =>
      run(async () => toUser((await signInWithEmailAndPassword(auth, email, password)).user)),
    signUpWithEmail: (name, email, password) =>
      run(async () => {
        const { user } = await createUserWithEmailAndPassword(auth, email, password);
        await updateProfile(user, { displayName: name });
        await verify(user);
        emit();
        return toUser(user);
      }),
    signInWithGoogle: () =>
      run(async () => {
        try {
          return toUser((await signInWithPopup(auth, google)).user);
        } catch (error) {
          if ((error as { code?: unknown }).code !== "auth/popup-blocked") throw error;
          await signInWithRedirect(auth, google);
          return null;
        }
      }),
    sendPasswordReset: (email) =>
      run(async () => {
        try {
          await sendPasswordResetEmail(auth, email, continueTo("/sign-in"));
        } catch (error) {
          if (!isContinueUrlError(error)) throw error;
          await sendPasswordResetEmail(auth, email);
        }
      }),
    sendVerificationEmail: () =>
      run(async () => {
        if (auth.currentUser) await verify(auth.currentUser);
      }),
    reload: () =>
      run(async () => {
        if (!auth.currentUser) return null;
        await auth.currentUser.reload();
        // emailVerified only reaches the API in a fresh ID token.
        await auth.currentUser.getIdToken(true);
        emit();
        return toUser(auth.currentUser);
      }),
    getIdToken: (forceRefresh = false) => run(async () => (auth.currentUser ? auth.currentUser.getIdToken(forceRefresh) : null)),
    signOut: () => run(() => signOut(auth)),
  };
}
