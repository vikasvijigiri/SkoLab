"use client";

import { createContext, useContext, useEffect, useState, useCallback } from "react";
import { onAuthStateChanged, type User } from "firebase/auth";
import { auth, isFirebaseConfigured } from "@/lib/firebase/client";
import { signOutUser } from "@/lib/firebase/auth";

interface AuthContextValue {
  user: User | null;
  loading: boolean;
  /** False until a Firebase Web app is registered and .env.local is filled in — see .env.local.example. */
  configured: boolean;
  /** Fresh Firebase ID token, fetched on demand — attach as `Authorization: Bearer <token>`. */
  getIdToken: () => Promise<string | null>;
  signOut: () => Promise<void>;
}

const AuthContext = createContext<AuthContextValue | undefined>(undefined);

// Playwright uses a deterministic, local-only session so protected screens can
// be rendered and audited without putting Firebase credentials in the repo.
// This branch is enabled only by the test server and is never active in a
// normal Next.js or Render process.
const PLAYWRIGHT_USER = {
  uid: "playwright-researcher",
  displayName: "Playwright Researcher",
  email: "playwright@skolab.local",
} as User;

export function AuthProvider({ children }: { children: React.ReactNode }) {
  const playwrightAuth = process.env.NEXT_PUBLIC_PLAYWRIGHT_AUTH === "1";
  const [user, setUser] = useState<User | null>(playwrightAuth ? PLAYWRIGHT_USER : null);
  const [loading, setLoading] = useState(playwrightAuth ? false : isFirebaseConfigured);

  useEffect(() => {
    if (playwrightAuth || !auth) return;
    const unsub = onAuthStateChanged(auth, (u) => {
      setUser(u);
      setLoading(false);
    });
    return unsub;
  }, [playwrightAuth]);

  const getIdToken = useCallback(async () => {
    if (!auth?.currentUser) return null;
    return auth.currentUser.getIdToken();
  }, []);

  const signOut = useCallback(async () => {
    await signOutUser();
  }, []);

  return (
    <AuthContext.Provider value={{ user, loading, configured: isFirebaseConfigured, getIdToken, signOut }}>
      {children}
    </AuthContext.Provider>
  );
}

export function useAuth() {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error("useAuth must be used within AuthProvider");
  return ctx;
}
