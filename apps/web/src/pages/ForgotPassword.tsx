import { useState, type SyntheticEvent } from "react";
import { Link, useSearchParams } from "react-router";
import { useAuth } from "../auth/AuthProvider";
import { messageFor, toAuthError } from "../auth/errors";
import { Alert } from "../components/Alert";
import { AuthLayout, linkClass } from "../components/AuthLayout";
import { Button } from "../components/Button";
import { TextField } from "../components/TextField";
import { useFormErrors } from "../lib/useFormErrors";
import { emailError } from "../lib/validation";

const FIELDS = ["email"] as const;

export function ForgotPassword() {
  const { service } = useAuth();
  const [params] = useSearchParams();
  const [email, setEmail] = useState(params.get("email") ?? "");
  const [pending, setPending] = useState(false);
  const [sentTo, setSentTo] = useState<string | null>(null);
  const [failure, setFailure] = useState<string | null>(null);
  const { errors, register, check, revalidate } = useFormErrors(FIELDS);

  async function onSubmit(event: SyntheticEvent<HTMLFormElement>) {
    event.preventDefault();
    setFailure(null);
    if (!check({ email: emailError(email) })) return;
    setPending(true);
    try {
      await service.sendPasswordReset(email.trim());
      setSentTo(email.trim());
    } catch (error) {
      // An unknown address is not an error worth showing: saying so would
      // tell anyone which emails have accounts.
      if (toAuthError(error).code === "invalid-credentials") setSentTo(email.trim());
      else setFailure(messageFor(error));
    } finally {
      setPending(false);
    }
  }

  const back = (
    <Link to={sentTo ? `/sign-in?email=${encodeURIComponent(sentTo)}` : "/sign-in"} className={linkClass}>
      Back to sign in
    </Link>
  );

  if (sentTo) {
    return (
      <AuthLayout title="Check your email" footer={back}>
        <Alert tone="success">
          If <strong className="font-semibold break-all">{sentTo}</strong> has a SkoLab account, a link to reset your password is on its way.
          It expires in one hour.
        </Alert>
        <p className="mt-6 text-sm leading-6 text-zinc-600 dark:text-zinc-400">
          Nothing after a few minutes? Check your spam folder, or{" "}
          <button type="button" onClick={() => setSentTo(null)} className={`cursor-pointer ${linkClass}`}>
            try a different email
          </button>
          .
        </p>
      </AuthLayout>
    );
  }

  return (
    <AuthLayout title="Reset your password" subtitle="Enter the email you sign in with and we'll send you a reset link." footer={back}>
      <form noValidate onSubmit={(event) => void onSubmit(event)} className="space-y-5">
        {failure ? <Alert tone="error">{failure}</Alert> : null}
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
        <Button type="submit" loading={pending}>
          Send reset link
        </Button>
      </form>
    </AuthLayout>
  );
}
