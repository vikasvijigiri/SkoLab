import { useCallback, useEffect, useState } from "react";
import { useNavigate, useSearchParams } from "react-router";
import { useAuth } from "../auth/AuthProvider";
import { messageFor } from "../auth/errors";
import { Alert } from "../components/Alert";
import { AuthLayout, linkClass } from "../components/AuthLayout";
import { Button } from "../components/Button";
import { safeNext } from "../lib/redirect";

export const RESEND_COOLDOWN_SECONDS = 60;

export function VerifyEmail() {
  const { service, user } = useAuth();
  const navigate = useNavigate();
  const [params] = useSearchParams();
  const next = safeNext(params.get("next"));
  const [pending, setPending] = useState<"check" | "resend" | null>(null);
  const [notice, setNotice] = useState<{ tone: "error" | "success" | "info"; text: string } | null>(null);
  const [cooldown, setCooldown] = useState(RESEND_COOLDOWN_SECONDS);

  useEffect(() => {
    if (cooldown <= 0) return;
    const timer = window.setTimeout(() => setCooldown((s) => s - 1), 1000);
    return () => window.clearTimeout(timer);
  }, [cooldown]);

  const check = useCallback(
    async (quiet: boolean) => {
      if (!quiet) {
        setPending("check");
        setNotice(null);
      }
      try {
        const fresh = await service.reload();
        if (fresh?.emailVerified) void navigate(next, { replace: true });
        else if (!quiet) setNotice({ tone: "info", text: "Not verified yet. Open the link in the email, then try again." });
      } catch (error) {
        if (!quiet) setNotice({ tone: "error", text: messageFor(error) });
      } finally {
        if (!quiet) setPending(null);
      }
    },
    [service, navigate, next],
  );

  // Coming back to this tab after clicking the link should just work.
  useEffect(() => {
    const onFocus = () => {
      if (document.visibilityState === "visible") void check(true);
    };
    window.addEventListener("focus", onFocus);
    document.addEventListener("visibilitychange", onFocus);
    return () => {
      window.removeEventListener("focus", onFocus);
      document.removeEventListener("visibilitychange", onFocus);
    };
  }, [check]);

  async function resend() {
    setPending("resend");
    setNotice(null);
    try {
      await service.sendVerificationEmail();
      setNotice({ tone: "success", text: "A new link is on its way." });
      setCooldown(RESEND_COOLDOWN_SECONDS);
    } catch (error) {
      setNotice({ tone: "error", text: messageFor(error) });
    } finally {
      setPending(null);
    }
  }

  async function switchAccount() {
    await service.signOut();
    void navigate("/sign-in", { replace: true });
  }

  return (
    <AuthLayout
      title="Verify your email"
      subtitle={
        <>
          We sent a confirmation link to <strong className="font-semibold break-all text-zinc-900 dark:text-white">{user?.email}</strong>. Open it to
          activate your account.
        </>
      }
      footer={
        <>
          Wrong address?{" "}
          <button type="button" onClick={() => void switchAccount()} className={`cursor-pointer ${linkClass}`}>
            Use a different account
          </button>
        </>
      }
    >
      <div className="space-y-4">
        {notice ? <Alert tone={notice.tone}>{notice.text}</Alert> : null}
        <Button onClick={() => void check(false)} loading={pending === "check"} disabled={pending !== null}>
          I've verified my email
        </Button>
        <Button variant="secondary" onClick={() => void resend()} loading={pending === "resend"} disabled={pending !== null || cooldown > 0}>
          {cooldown > 0 ? `Resend email in ${cooldown}s` : "Resend email"}
        </Button>
      </div>
    </AuthLayout>
  );
}
