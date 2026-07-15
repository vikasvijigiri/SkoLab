/**
 * Formats an error from the sign-up/sign-in flow. That flow calls both Firebase
 * Auth (codes like "auth/...") AND Firestore (createResearcherProfile — codes
 * like "permission-denied", no "auth/" prefix) under the hood, so this must
 * recognize both namespaces or Firestore errors get masked behind the generic
 * fallback below, same bug as before just in a different call path.
 */
export function friendlyAuthError(err: unknown): string {
  const code = (err as { code?: string })?.code;

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
      return "Sign-in was cancelled.";
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
