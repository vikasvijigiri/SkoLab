import { AuthError, type AuthErrorCode } from "./types";

const firebaseCodes: Record<string, AuthErrorCode> = {
  "auth/invalid-credential": "invalid-credentials",
  "auth/invalid-login-credentials": "invalid-credentials",
  "auth/wrong-password": "invalid-credentials",
  "auth/user-not-found": "invalid-credentials",
  "auth/invalid-email": "invalid-email",
  "auth/missing-email": "invalid-email",
  "auth/email-already-in-use": "email-in-use",
  "auth/weak-password": "weak-password",
  "auth/password-does-not-meet-requirements": "weak-password",
  "auth/too-many-requests": "too-many-requests",
  "auth/user-disabled": "user-disabled",
  "auth/network-request-failed": "network",
  "auth/popup-closed-by-user": "popup-closed",
  "auth/cancelled-popup-request": "popup-closed",
  "auth/user-cancelled": "popup-closed",
  "auth/popup-blocked": "popup-blocked",
  "auth/account-exists-with-different-credential": "account-exists-with-different-credential",
  "auth/requires-recent-login": "requires-recent-login",
  "auth/unauthorized-domain": "unauthorized-domain",
};

/** Maps any thrown value from the Firebase SDK onto an AuthError. */
export function toAuthError(error: unknown): AuthError {
  if (error instanceof AuthError) {
    return error;
  }
  const code =
    typeof error === "object" && error !== null && "code" in error && typeof error.code === "string"
      ? error.code
      : "";
  return new AuthError(firebaseCodes[code] ?? "unknown", { cause: error });
}

const messages: Record<AuthErrorCode, string> = {
  "invalid-credentials": "That email and password don't match. Check them and try again.",
  "invalid-email": "Enter a valid email address.",
  "email-in-use": "An account with this email already exists. Sign in instead.",
  "weak-password": "Choose a stronger password: at least 8 characters, mixing letters and numbers.",
  "too-many-requests": "Too many attempts. Wait a few minutes, then try again.",
  "user-disabled": "This account has been disabled. Contact support if you think this is a mistake.",
  network: "We couldn't reach the server. Check your connection and try again.",
  "popup-closed": "Google sign-in was closed before it finished.",
  "popup-blocked": "Your browser blocked the Google sign-in window. Allow pop-ups for this site and try again.",
  "account-exists-with-different-credential":
    "This email already has an account with a different sign-in method. Sign in with your password.",
  "requires-recent-login": "For your security, sign in again to continue.",
  "unauthorized-domain": "Google sign-in isn't available on this address. Use your email and password instead.",
  unknown: "Something went wrong. Please try again.",
};

/** Words for people. Never reveals whether an email has an account on sign-in. */
export function messageFor(error: unknown): string {
  return messages[toAuthError(error).code];
}
