export type SignInMethod = "password" | "google" | "other";

export interface AuthUser {
  uid: string;
  email: string | null;
  displayName: string | null;
  emailVerified: boolean;
  method: SignInMethod;
}

/** Stable, provider-neutral failure reasons the UI knows how to explain. */
export type AuthErrorCode =
  | "invalid-credentials"
  | "invalid-email"
  | "email-in-use"
  | "weak-password"
  | "too-many-requests"
  | "user-disabled"
  | "network"
  | "popup-closed"
  | "popup-blocked"
  | "account-exists-with-different-credential"
  | "requires-recent-login"
  | "unknown";

export class AuthError extends Error {
  readonly code: AuthErrorCode;

  constructor(code: AuthErrorCode, options?: { cause?: unknown }) {
    super(code, options);
    this.name = "AuthError";
    this.code = code;
  }
}

/**
 * Everything the UI needs from an identity provider. The app talks to this,
 * never to Firebase directly, so screens are testable without a network and
 * the provider can change without touching them.
 */
export interface AuthService {
  /** Calls back with the current user now (once known) and on every change. */
  onChange(listener: (user: AuthUser | null) => void): () => void;
  signInWithEmail(email: string, password: string): Promise<AuthUser>;
  /** Creates the account, sets its display name and sends the verification email. */
  signUpWithEmail(name: string, email: string, password: string): Promise<AuthUser>;
  /** Resolves null when the browser was sent to Google (popup blocked). */
  signInWithGoogle(): Promise<AuthUser | null>;
  sendPasswordReset(email: string): Promise<void>;
  sendVerificationEmail(): Promise<void>;
  /** Re-reads the signed-in user from the provider (e.g. after verifying). */
  reload(): Promise<AuthUser | null>;
  getIdToken(forceRefresh?: boolean): Promise<string | null>;
  signOut(): Promise<void>;
}

/** Email/password accounts must confirm their address; federated ones are vouched for. */
export function needsVerification(user: AuthUser): boolean {
  return user.method === "password" && !user.emailVerified;
}
