import { useState, type SyntheticEvent } from "react";
import { Link, useNavigate, useSearchParams } from "react-router";
import { useAuth } from "../auth/AuthProvider";
import { messageFor, toAuthError } from "../auth/errors";
import { needsVerification } from "../auth/types";
import { Alert } from "../components/Alert";
import { AuthLayout, linkClass } from "../components/AuthLayout";
import { Button } from "../components/Button";
import { Divider } from "../components/Divider";
import { GoogleButton } from "../components/GoogleButton";
import { PasswordField } from "../components/PasswordField";
import { TextField } from "../components/TextField";
import { safeNext } from "../lib/redirect";
import { useFormErrors } from "../lib/useFormErrors";
import { emailError, passwordError } from "../lib/validation";

const FIELDS = ["email", "password"] as const;

export function SignIn() {
  const { service } = useAuth();
  const navigate = useNavigate();
  const [params] = useSearchParams();
  const next = safeNext(params.get("next"));
  const [email, setEmail] = useState(params.get("email") ?? "");
  const [password, setPassword] = useState("");
  const [pending, setPending] = useState<"email" | "google" | null>(null);
  const [failure, setFailure] = useState<string | null>(null);
  const { errors, register, check, revalidate } = useFormErrors(FIELDS);

  const query = next === "/" ? "" : `?next=${encodeURIComponent(next)}`;

  async function onSubmit(event: SyntheticEvent<HTMLFormElement>) {
    event.preventDefault();
    setFailure(null);
    if (!check({ email: emailError(email), password: passwordError(password, "existing") })) return;
    setPending("email");
    try {
      const user = await service.signInWithEmail(email.trim(), password);
      void navigate(needsVerification(user) ? `/verify-email${query}` : next, { replace: true });
    } catch (error) {
      setFailure(messageFor(error));
      setPassword("");
      setPending(null);
    }
  }

  async function onGoogle() {
    setFailure(null);
    setPending("google");
    try {
      const user = await service.signInWithGoogle();
      if (user) void navigate(next, { replace: true });
    } catch (error) {
      if (toAuthError(error).code !== "popup-closed") setFailure(messageFor(error));
      setPending(null);
    }
  }

  return (
    <AuthLayout
      title="Welcome back"
      subtitle="Sign in to pick up where you left off."
      footer={
        <>
          New to SkoLab?{" "}
          <Link to={`/sign-up${query}`} className={linkClass}>
            Create an account
          </Link>
        </>
      }
    >
      <GoogleButton onClick={() => void onGoogle()} loading={pending === "google"} disabled={pending !== null} />
      <Divider label="or" />
      <form noValidate onSubmit={(event) => void onSubmit(event)} className="space-y-5" aria-describedby={failure ? "sign-in-error" : undefined}>
        {failure ? (
          <Alert tone="error" id="sign-in-error">
            {failure}
          </Alert>
        ) : null}
        <TextField
          ref={register("email")}
          label="Email"
          type="email"
          name="email"
          autoComplete="email"
          inputMode="email"
          autoCapitalize="none"
          spellCheck={false}
          autoFocus
          value={email}
          error={errors.email}
          onChange={(event) => {
            setEmail(event.target.value);
            revalidate("email", emailError(event.target.value));
          }}
        />
        <PasswordField
          ref={register("password")}
          label="Password"
          name="password"
          autoComplete="current-password"
          value={password}
          error={errors.password}
          labelAside={
            <Link to={`/forgot-password${email ? `?email=${encodeURIComponent(email.trim())}` : ""}`} className={`text-sm ${linkClass}`}>
              Forgot password?
            </Link>
          }
          onChange={(event) => {
            setPassword(event.target.value);
            revalidate("password", passwordError(event.target.value, "existing"));
          }}
        />
        <Button type="submit" loading={pending === "email"} disabled={pending !== null}>
          Sign in
        </Button>
      </form>
    </AuthLayout>
  );
}
