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
  signUpWithEmail,
  signInWithGoogle,
  completeGoogleRedirectSignIn,
} from "@/lib/firebase/auth";
import { friendlyAuthError } from "@/lib/firebase/errors";

export default function SignupPage() {
  const router = useRouter();
  const { configured } = useAuth();
  const [name, setName] = useState("");
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState<"email" | "google" | null>(null);
  const [showEmail, setShowEmail] = useState(false);

  // Picks up the result of signInWithGoogle's redirect round trip -- see the
  // matching effect in app/login/page.tsx for the full reasoning, including
  // why this is gated on `configured`.
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

  async function handleSignup(e: React.FormEvent) {
    e.preventDefault();
    setError(null);
    setLoading("email");
    try {
      await signUpWithEmail(email, password, name);
      router.push("/onboarding");
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

  return (
    <AuthCard>
      <h1 className="font-display text-h2 font-bold text-text-primary">Create your account</h1>
      <p className="mt-2 font-body text-body text-text-secondary">
        Join SkoLab and start mapping your research impact.
      </p>

      {!configured && <div className="mt-5"><FirebaseConfigBanner /></div>}

      <div className="mt-6">
        <GoogleSignInButton onClick={handleGoogle} loading={loading === "google"} />
      </div>

      {error && <p className="mt-3 font-body text-body-s text-notification">{error}</p>}

      {showEmail ? (
        <>
          <div className="my-5 flex items-center gap-3">
            <div className="h-px flex-1 bg-border" />
            <span className="font-body text-[12px] text-text-muted">or with email</span>
            <div className="h-px flex-1 bg-border" />
          </div>
          <form onSubmit={handleSignup} className="flex flex-col gap-3">
            <Input
              label="Full name"
              type="text"
              placeholder="Full name"
              required
              value={name}
              onChange={(e) => setName(e.target.value)}
            />
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
              placeholder="Password (min. 6 characters)"
              required
              minLength={6}
              value={password}
              onChange={(e) => setPassword(e.target.value)}
            />
            <Button type="submit" loading={loading === "email"}>
              Create account
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
        Already have an account?{" "}
        <Link href="/login" className="font-medium text-primary">
          Sign in
        </Link>
      </p>
    </AuthCard>
  );
}
