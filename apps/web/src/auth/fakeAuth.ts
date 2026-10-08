import { AuthError, type AuthService, type AuthUser } from "./types";

interface Account {
  user: AuthUser;
  password: string;
}

export interface FakeAuthControls {
  /** Marks an account's email as verified, as clicking the emailed link would. */
  verify(email: string): void;
  /** Makes the next call fail with this code. */
  failNext(code: AuthError["code"]): void;
  sentEmails: { kind: "verify" | "reset"; email: string }[];
}

const GOOGLE_USER: AuthUser = {
  uid: "google-ada",
  email: "ada@example.com",
  displayName: "Ada Lovelace",
  emailVerified: true,
  method: "google",
};

/**
 * An in-memory identity provider with Firebase's observable behavior, used by
 * unit tests and the e2e build. It is never bundled into production.
 */
export function createFakeAuth(options: { persist?: Storage } = {}): AuthService & FakeAuthControls {
  const store = options.persist;
  const saved = store?.getItem("fake-auth");
  const state: { accounts: Record<string, Account>; current: string | null } = saved
    ? (JSON.parse(saved) as { accounts: Record<string, Account>; current: string | null })
    : { accounts: {}, current: null };
  const listeners = new Set<(user: AuthUser | null) => void>();
  let pendingFailure: AuthError["code"] | null = null;
  const sentEmails: FakeAuthControls["sentEmails"] = [];

  const accountFor = (email: string): Account | undefined => state.accounts[email];
  const currentUser = (): AuthUser | null => {
    const account = state.current ? accountFor(state.current) : undefined;
    return account ? { ...account.user } : null;
  };
  const signedIn = (): AuthUser => {
    const user = currentUser();
    if (!user) throw new AuthError("unknown");
    return user;
  };
  const save = () => store?.setItem("fake-auth", JSON.stringify(state));
  const emit = () => {
    save();
    const user = currentUser();
    listeners.forEach((listener) => listener(user));
  };
  const step = async () => {
    await Promise.resolve();
    if (pendingFailure) {
      const code = pendingFailure;
      pendingFailure = null;
      throw new AuthError(code);
    }
  };
  const key = (email: string) => email.trim().toLowerCase();

  return {
    sentEmails,
    verify(email) {
      const account = state.accounts[key(email)];
      if (account) account.user.emailVerified = true;
      save();
    },
    failNext(code) {
      pendingFailure = code;
    },
    onChange(listener) {
      listeners.add(listener);
      queueMicrotask(() => {
        if (listeners.has(listener)) listener(currentUser());
      });
      return () => listeners.delete(listener);
    },
    async signInWithEmail(email, password) {
      await step();
      const account = state.accounts[key(email)];
      if (!account || account.password !== password) throw new AuthError("invalid-credentials");
      state.current = key(email);
      emit();
      return signedIn();
    },
    async signUpWithEmail(name, email, password) {
      await step();
      if (state.accounts[key(email)]) throw new AuthError("email-in-use");
      if (password.length < 8) throw new AuthError("weak-password");
      state.accounts[key(email)] = {
        password,
        user: { uid: `uid-${key(email)}`, email: key(email), displayName: name, emailVerified: false, method: "password" },
      };
      state.current = key(email);
      sentEmails.push({ kind: "verify", email: key(email) });
      emit();
      return signedIn();
    },
    async signInWithGoogle() {
      await step();
      const googleKey = "ada@example.com";
      state.accounts[googleKey] ??= { user: { ...GOOGLE_USER }, password: "" };
      state.current = googleKey;
      emit();
      return currentUser();
    },
    async sendPasswordReset(email) {
      await step();
      // Like Firebase with enumeration protection: succeeds for unknown emails too.
      if (state.accounts[key(email)]) sentEmails.push({ kind: "reset", email: key(email) });
    },
    async sendVerificationEmail() {
      await step();
      const user = currentUser();
      if (user?.email) sentEmails.push({ kind: "verify", email: user.email });
    },
    async reload() {
      await step();
      emit();
      return currentUser();
    },
    async getIdToken() {
      await step();
      const user = currentUser();
      return user ? `fake-token:${user.uid}` : null;
    },
    async signOut() {
      await step();
      state.current = null;
      emit();
    },
  };
}
