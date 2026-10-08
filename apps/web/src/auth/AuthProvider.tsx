import { createContext, use, useCallback, useEffect, useMemo, useRef, useState, type ReactNode } from "react";
import { syncProfile } from "../api/profile";
import { fallbackName } from "../lib/validation";
import { needsVerification, type AuthService, type AuthUser } from "./types";

export type AuthStatus = "loading" | "signed-out" | "signed-in";
export type ProfileStatus = "idle" | "syncing" | "synced" | "error";

interface AuthContextValue {
  status: AuthStatus;
  user: AuthUser | null;
  profile: ProfileStatus;
  service: AuthService;
  retryProfileSync: () => void;
}

const AuthContext = createContext<AuthContextValue | null>(null);

export function AuthProvider({ service, children }: { service: AuthService; children: ReactNode }) {
  const [user, setUser] = useState<AuthUser | null>(null);
  const [status, setStatus] = useState<AuthStatus>("loading");
  const [profile, setProfile] = useState<ProfileStatus>("idle");
  const syncedFor = useRef<string | null>(null);

  useEffect(
    () =>
      service.onChange((next) => {
        setUser(next);
        setStatus(next ? "signed-in" : "signed-out");
        if (!next) {
          syncedFor.current = null;
          setProfile("idle");
        }
      }),
    [service],
  );

  const sync = useCallback(
    async (current: AuthUser) => {
      syncedFor.current = current.uid;
      setProfile("syncing");
      try {
        const token = await service.getIdToken();
        if (!token) throw new Error("no token");
        await syncProfile(token, current.uid, current.displayName?.trim() || fallbackName(current.email));
        setProfile("synced");
      } catch {
        setProfile("error");
      }
    },
    [service],
  );

  // The API needs a profile row before any workspace call; create it as soon
  // as the account is usable, once per signed-in user.
  useEffect(() => {
    if (user && !needsVerification(user) && syncedFor.current !== user.uid) void sync(user);
  }, [user, sync]);

  const retryProfileSync = useCallback(() => {
    if (user) void sync(user);
  }, [user, sync]);

  const value = useMemo(
    () => ({ status, user, profile, service, retryProfileSync }),
    [status, user, profile, service, retryProfileSync],
  );
  return <AuthContext value={value}>{children}</AuthContext>;
}

export function useAuth(): AuthContextValue {
  const value = use(AuthContext);
  if (!value) throw new Error("useAuth must be used inside <AuthProvider>");
  return value;
}
