import { useState } from "react";
import { useAuth } from "../auth/AuthProvider";
import { Alert } from "../components/Alert";
import { Button } from "../components/Button";
import { Logo } from "../components/Logo";
import { fallbackName } from "../lib/validation";

/** The signed-in landing page. Placeholder until the workspace dashboard exists. */
export function Home() {
  const { user, profile, service, retryProfileSync } = useAuth();
  const [signingOut, setSigningOut] = useState(false);
  const name = user?.displayName?.trim() || fallbackName(user?.email ?? null);
  const initials = name
    .split(/\s+/)
    .slice(0, 2)
    .map((part) => part[0]?.toUpperCase() ?? "")
    .join("");

  return (
    <div className="min-h-dvh">
      <title>Home · SkoLab</title>
      <header className="border-b border-zinc-200 bg-white/80 backdrop-blur dark:border-zinc-800 dark:bg-zinc-950/80">
        <div className="mx-auto flex h-16 max-w-5xl items-center justify-between px-4 sm:px-6">
          <Logo className="text-zinc-900 dark:text-white" />
          <div className="w-28">
            <Button
              variant="secondary"
              loading={signingOut}
              onClick={() => {
                setSigningOut(true);
                void service.signOut();
              }}
            >
              Sign out
            </Button>
          </div>
        </div>
      </header>
      <main id="main" className="mx-auto max-w-5xl px-4 py-12 sm:px-6">
        <div className="flex animate-enter items-center gap-4">
          <span aria-hidden="true" className="grid size-14 place-items-center rounded-full bg-brand-100 text-lg font-semibold text-brand-800 dark:bg-brand-900 dark:text-brand-100">
            {initials}
          </span>
          <div>
            <h1 className="text-2xl font-semibold tracking-tight">Welcome, {name}</h1>
            <p className="text-sm text-zinc-600 dark:text-zinc-400">{user?.email}</p>
          </div>
        </div>
        <div className="mt-8 max-w-xl">
          {profile === "error" ? (
            <Alert tone="error">
              We couldn't finish setting up your account.{" "}
              <button type="button" onClick={retryProfileSync} className="cursor-pointer font-semibold underline underline-offset-4">
                Try again
              </button>
            </Alert>
          ) : profile === "synced" ? (
            <Alert tone="success">You're all set. Your workspaces will appear here.</Alert>
          ) : (
            <Alert tone="info">Setting up your account…</Alert>
          )}
        </div>
      </main>
    </div>
  );
}
