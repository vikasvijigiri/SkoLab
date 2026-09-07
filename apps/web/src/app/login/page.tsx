"use client";

import { useEffect, useRef, useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { AuthCard } from "@/components/auth/AuthCard";
import { FirebaseConfigBanner } from "@/components/auth/FirebaseConfigBanner";
import { GoogleSignInButton } from "@/components/auth/GoogleSignInButton";
import { Input } from "@/components/ui/Input";
import { Button } from "@/components/ui/Button";
import { useAuth } from "@/lib/hooks/AuthProvider";
import {
  signInWithEmail,
  signInWithGoogle,
  signInAsGuest,
  completeGoogleRedirectSignIn,
} from "@/lib/firebase/auth";
import { friendlyAuthError } from "@/lib/firebase/errors";

export default function LoginPage() {
  const router = useRouter();
  const { configured } = useAuth();
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState<"email" | "google" | "guest" | null>(null);
  // Click-only by default: providers first, the typed email form is opt-in.
  const [showEmail, setShowEmail] = useState(false);

  // Picks up the result of signInWithGoogle's redirect round trip -- Firebase
  // sends the browser back to this exact page. Runs once; the ref guards
  // React 19's dev-mode double-invoke from processing the same return twice.
  // Gated on `configured`: requireAuth() throws "Firebase is not configured"
  // when it isn't, which is already surfaced by <FirebaseConfigBanner />
  // below -- without this guard, that same message rendered a second time,
  // in the error paragraph, on every ordinary page load in that state, not
  // just after a real failed sign-in attempt (confirmed live: CI's own a11y
  // suite caught this exact regression by tripping on the notification
  // color the first time this code path actually ran unconditionally).
  const redirectChecked = useRef(false);
  useEffect(() => {
    if (!configured || redirectChecked.current) return;
    redirectChecked.current = true;
    completeGoogleRedirectSignIn()
      .then((res) => {
        if (res) router.push(res.isNewUser ? "/onboarding" : "/home");
      })
      .catch((err) => setError(friendlyAuthError(err)));
  }, [configured, router]);

  async function handleEmailLogin(e: React.FormEvent) {
    e.preventDefault();
    setError(null);
    setLoading("email");
    try {
      await signInWithEmail(email, password);
      router.push("/home");
    } catch (err) {
      setError(friendlyAuthError(err));
    } finally {
      setLoading(null);
    }
  }

  async function handleGoogle() {
    setError(null);
    setLoading("google");
    try {
      // Navigates the whole page to Google -- this only throws if the
      // redirect itself couldn't start. The actual sign-in result is picked
      // up by completeGoogleRedirectSignIn above, after the round trip back.
      await signInWithGoogle();
    } catch (err) {
      setError(friendlyAuthError(err));
      setLoading(null);
    }
  }

  async function handleGuest() {
    setError(null);
    setLoading("guest");
    try {
      await signInAsGuest();
      router.push("/home");
    } catch (err) {
      setError(friendlyAuthError(err));
    } finally {
      setLoading(null);
    }
  }

  return (
    <AuthCard>
      <h1 className="font-display text-h2 font-bold text-text-primary">Welcome back</h1>
      <p className="mt-2 font-body text-body text-text-secondary">
        Sign in to continue to SkoLab.
      </p>

      {!configured && <div className="mt-5"><FirebaseConfigBanner /></div>}

      <div className="mt-6 flex flex-col gap-3">
        <GoogleSignInButton onClick={handleGoogle} loading={loading === "google"} />
        <Button variant="text" onClick={handleGuest} disabled={loading === "guest"}>
          Continue as guest
        </Button>
      </div>

      {error && <p className="mt-3 font-body text-body-s text-notification">{error}</p>}

      {showEmail ? (
        <>
          <div className="my-5 flex items-center gap-3">
            <div className="h-px flex-1 bg-border" />
            <span className="font-body text-[12px] text-text-muted">or with email</span>
            <div className="h-px flex-1 bg-border" />
          </div>
          <form onSubmit={handleEmailLogin} className="flex flex-col gap-3">
            <Input
              label="Email"
              type="email"
              placeholder="Email"
              required
              value={email}
              onChange={(e) => setEmail(e.target.value)}
            />
            <Input
              label="Password"
              type="password"
              placeholder="Password"
              required
              value={password}
              onChange={(e) => setPassword(e.target.value)}
            />
            <Button type="submit" loading={loading === "email"}>
              Sign in
            </Button>
          </form>
        </>
      ) : (
        <button
          type="button"
          onClick={() => setShowEmail(true)}
          className="mt-4 block w-full cursor-pointer text-center font-body text-body-s text-text-muted underline-offset-2 transition-colors hover:text-text-secondary hover:underline"
        >
          Use email and password instead
        </button>
      )}

      <p className="mt-6 text-center font-body text-body-s text-text-secondary">
        Don&apos;t have an account?{" "}
        <Link href="/signup" className="font-medium text-primary">
          Sign up
        </Link>
      </p>
    </AuthCard>
  );
}
