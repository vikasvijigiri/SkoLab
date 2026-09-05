import * as Sentry from "@sentry/nextjs";

/**
 * Formats an error from the sign-up/sign-in flow. That flow calls both Firebase
 * Auth (codes like "auth/...") AND Firestore (createResearcherProfile — codes
 * like "permission-denied", no "auth/" prefix) under the hood, so this must
 * recognize both namespaces or Firestore errors get masked behind the generic
 * fallback below, same bug as before just in a different call path.
 *
 * Every call site (login and signup pages) only ever surfaced the *friendly*
 * string and threw the real error away — an unhandled code silently became
 * "Something went wrong. Please try again." with the actual `.code` visible
 * nowhere, not even the browser console. Diagnosing a real Google sign-in
 * failure this way needed a live debugger session instead of just reading
 * Sentry. Logged here, once, so every caller gets it for free.
 */
export function friendlyAuthError(err: unknown): string {
  const code = (err as { code?: string })?.code;

  console.error("[auth]", code ?? err);
  Sentry.captureException(err, { tags: { source: "friendlyAuthError" } });

  // Not a Firebase AuthError (no .code) — e.g. our own "Firebase is not configured" guard.
  // Surface its real message instead of masking it behind a generic one.
  if (!code && err instanceof Error && err.message) {
    return err.message;
  }

  switch (code) {
    case "auth/invalid-credential":
    case "auth/wrong-password":
    case "auth/user-not-found":
      return "Incorrect email or password.";
    case "auth/email-already-in-use":
      return "An account with this email already exists.";
    case "auth/weak-password":
      return "Password should be at least 6 characters.";
    case "auth/invalid-email":
      return "Enter a valid email address.";
    case "auth/popup-closed-by-user":
    case "auth/cancelled-popup-request":
      return "Sign-in was cancelled.";
    case "auth/popup-blocked":
      return "Your browser blocked the sign-in popup — allow popups for this site and try again.";
    case "auth/account-exists-with-different-credential":
      return "An account already exists with this email, signed in a different way — try email/password instead.";
    case "auth/unauthorized-domain":
      return "This domain isn't authorized for sign-in yet — contact support.";
    case "auth/operation-not-allowed":
      return "This sign-in method isn't enabled yet — contact support.";
    case "auth/network-request-failed":
      return "Network error — check your connection and try again.";
    case "permission-denied":
      return "Your account was created, but saving your profile was denied — check Firestore security rules for the \"researchers\" collection.";
    case "unavailable":
      return "Your account was created, but couldn't reach Firestore to save your profile. Check your connection and try again.";
    default:
      return "Something went wrong. Please try again.";
  }
}
