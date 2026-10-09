import { useState, type SyntheticEvent } from "react";
import { Link, useNavigate, useSearchParams } from "react-router";
import { useAuth } from "../auth/AuthProvider";
import { messageFor, toAuthError } from "../auth/errors";
import { Alert } from "../components/Alert";
import { AuthLayout, linkClass } from "../components/AuthLayout";
import { Button } from "../components/Button";
import { Divider } from "../components/Divider";
import { config } from "../config";
import { GoogleButton } from "../components/GoogleButton";
import { PasswordField } from "../components/PasswordField";
import { PasswordStrength } from "../components/PasswordStrength";
import { TextField } from "../components/TextField";
import { safeNext } from "../lib/redirect";
import { useFormErrors } from "../lib/useFormErrors";
import { emailError, nameError, passwordError } from "../lib/validation";

const FIELDS = ["name", "email", "password"] as const;

export function SignUp() {
  const { service } = useAuth();
  const navigate = useNavigate();
  const [params] = useSearchParams();
  const next = safeNext(params.get("next"));
  const query = next === "/" ? "" : `?next=${encodeURIComponent(next)}`;
  const [name, setName] = useState("");
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [pending, setPending] = useState<"email" | "google" | null>(null);
  const [failure, setFailure] = useState<{ message: string; emailInUse: boolean } | null>(null);
  const { errors, register, check, revalidate } = useFormErrors(FIELDS);

  async function onSubmit(event: SyntheticEvent<HTMLFormElement>) {
    event.preventDefault();
    setFailure(null);
    const valid = check({ name: nameError(name), email: emailError(email), password: passwordError(password, "new") });
    if (!valid) return;
    setPending("email");
    try {
      await service.signUpWithEmail(name.trim(), email.trim(), password);
      void navigate(`/verify-email${query}`, { replace: true });
    } catch (error) {
      setFailure({ message: messageFor(error), emailInUse: toAuthError(error).code === "email-in-use" });
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
      if (toAuthError(error).code !== "popup-closed") setFailure({ message: messageFor(error), emailInUse: false });
      setPending(null);
    }
  }

  return (
    <AuthLayout
      title="Create your account"
      subtitle="Start writing with your team in under a minute."
      footer={
        <>
          Already have an account?{" "}
          <Link to={`/sign-in${query}`} className={linkClass}>
            Sign in
          </Link>
        </>
      }
    >
      {config.googleSignIn ? (
        <>
          <GoogleButton onClick={() => void onGoogle()} loading={pending === "google"} disabled={pending !== null} />
          <Divider label="or" />
        </>
      ) : null}
      <form noValidate onSubmit={(event) => void onSubmit(event)} className="space-y-5">
        {failure ? (
          <Alert tone="error">
            {failure.message}
            {failure.emailInUse ? (
              <>
                {" "}
                <Link to={`/sign-in?email=${encodeURIComponent(email.trim())}${next === "/" ? "" : `&next=${encodeURIComponent(next)}`}`} className={linkClass}>
                  Go to sign in
                </Link>
              </>
            ) : null}
          </Alert>
        ) : null}
        <TextField
          ref={register("name")}
          label="Full name"
          name="name"
          autoComplete="name"
          autoFocus
          value={name}
          error={errors.name}
          onChange={(event) => {
            setName(event.target.value);
            revalidate("name", nameError(event.target.value));
          }}
        />
        <TextField
          ref={register("email")}
          label="Email"
          type="email"
          name="email"
          autoComplete="email"
          inputMode="email"
          autoCapitalize="none"
          spellCheck={false}
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
          name="new-password"
          autoComplete="new-password"
          value={password}
          error={errors.password}
          hint={<PasswordStrength password={password} />}
          onChange={(event) => {
            setPassword(event.target.value);
            revalidate("password", passwordError(event.target.value, "new"));
          }}
        />
        <Button type="submit" loading={pending === "email"} disabled={pending !== null}>
          Create account
        </Button>
        <p className="text-center text-xs leading-5 text-zinc-500 dark:text-zinc-400">
          We'll email you a link to confirm your address before you can use SkoLab.
        </p>
      </form>
    </AuthLayout>
  );
}
