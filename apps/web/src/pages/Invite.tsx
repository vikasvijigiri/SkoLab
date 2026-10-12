import { useEffect, useState } from "react";
import { Link, Navigate, useLocation, useNavigate } from "react-router";
import { ApiError } from "../api/client";
import { ROLE_LABELS, type InvitePreview } from "../api/editorTypes";
import { acceptInvite, previewInvite } from "../api/sharing";
import { useAuth } from "../auth/AuthProvider";
import { needsVerification } from "../auth/types";
import { useIdToken } from "../auth/useIdToken";
import { Alert } from "../components/Alert";
import { Button } from "../components/Button";
import { Logo } from "../components/Logo";
import { FullPageLoader } from "../components/Spinner";
import { formatDate } from "../lib/dates";
import { signInPath } from "../lib/redirect";

// The token waits here while its holder signs in or verifies their email,
// so it never has to travel in a ?next= query string.
const STASH = "skolab.invite";

function stash(token: string | null) {
  try {
    if (token) sessionStorage.setItem(STASH, token);
    else sessionStorage.removeItem(STASH);
  } catch {
    // Storage blocked: the link still works if they are already signed in.
  }
}

function stashed(): string | null {
  try {
    return sessionStorage.getItem(STASH);
  } catch {
    return null;
  }
}

function inviteMessage(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.code === "invite_invalid" || error.status === 404) return "This invite link has expired, has been used up, or was revoked. Ask for a new one.";
    if (error.code === "network") return error.message;
    if (error.status === 429) return "Too many tries. Wait a minute and open the link again.";
  }
  return "We couldn't open this invite. Try again.";
}

type State = { state: "loading" } | { state: "ready"; preview: InvitePreview } | { state: "error"; message: string };

/** /invite#inv_…: join a shared document. */
export function Invite() {
  const { status, user } = useAuth();
  const location = useLocation();
  // Kept before any redirect to sign in, which drops the fragment.
  const [token] = useState(() => {
    const fromLink = location.hash.startsWith("#inv_") ? location.hash.slice(1) : null;
    if (fromLink) stash(fromLink);
    return fromLink ?? stashed();
  });

  if (!token) return <Message title="This invite link is incomplete">Ask the person who invited you to send the whole link again.</Message>;
  if (status === "loading") return <FullPageLoader />;
  if (!user) return <Navigate to={signInPath("/invite")} replace />;
  if (needsVerification(user)) return <Navigate to="/verify-email?next=%2Finvite" replace />;
  return <Join token={token} />;
}

function Join({ token }: { token: string }) {
  const idToken = useIdToken();
  const navigate = useNavigate();
  const { hash } = useLocation();

  // Take the token out of the address bar and history once it is kept.
  useEffect(() => {
    if (hash) void navigate("/invite", { replace: true });
  }, [hash, navigate]);
  const [page, setPage] = useState<State>({ state: "loading" });
  const [joining, setJoining] = useState(false);
  const [failure, setFailure] = useState<string | null>(null);

  useEffect(() => {
    let current = true;
    void idToken()
      .then((auth) => previewInvite(auth, token))
      .then(
        (preview) => current && setPage({ state: "ready", preview }),
        (error: unknown) => current && setPage({ state: "error", message: inviteMessage(error) }),
      );
    return () => {
      current = false;
    };
  }, [idToken, token]);

  async function join() {
    setJoining(true);
    setFailure(null);
    try {
      const membership = await acceptInvite(await idToken(), token);
      stash(null);
      await navigate(`/editor/${encodeURIComponent(membership.workspace_id)}`, { replace: true });
    } catch (error) {
      setFailure(inviteMessage(error));
      setJoining(false);
    }
  }

  if (page.state === "loading") return <FullPageLoader label="Opening the invite" />;
  if (page.state === "error") return <Message title="Invite unavailable">{page.message}</Message>;

  const { preview } = page;
  return (
    <main id="main" className="grid min-h-dvh place-items-center px-4 py-10">
      <title>{`Join “${preview.workspace_title}” · SkoLab`}</title>
      <div className="w-full max-w-md animate-enter rounded-2xl border border-zinc-200 bg-white p-7 shadow-xl shadow-zinc-900/5 dark:border-zinc-800 dark:bg-zinc-900">
        <Logo />
        <p className="mt-8 text-sm font-medium text-brand-700 dark:text-brand-300">You're invited to co-author</p>
        <h1 className="mt-1 text-2xl font-semibold tracking-tight break-words">{preview.workspace_title}</h1>
        <dl className="mt-5 space-y-2 rounded-xl bg-zinc-50 p-4 text-sm dark:bg-zinc-950">
          <div className="flex justify-between gap-4">
            <dt className="text-zinc-600 dark:text-zinc-400">Your access</dt>
            <dd className="font-medium">{ROLE_LABELS[preview.role]}</dd>
          </div>
          <div className="flex justify-between gap-4">
            <dt className="text-zinc-600 dark:text-zinc-400">Link works until</dt>
            <dd className="font-medium">{formatDate(preview.expires_at)}</dd>
          </div>
        </dl>
        {failure && (
          <div className="mt-4">
            <Alert tone="error">{failure}</Alert>
          </div>
        )}
        <div className="mt-6 space-y-2">
          <Button loading={joining} onClick={() => void join()}>
            Open the document
          </Button>
          <Link
            to="/"
            onClick={() => stash(null)}
            className="flex h-11 items-center justify-center rounded-lg text-sm font-semibold text-zinc-600 hover:bg-zinc-100 dark:text-zinc-400 dark:hover:bg-zinc-800"
          >
            Not now
          </Link>
        </div>
      </div>
    </main>
  );
}

function Message({ title, children }: { title: string; children: string }) {
  return (
    <main id="main" className="mx-auto max-w-xl px-4 py-16 text-center">
      <title>{`${title} · SkoLab`}</title>
      <h1 className="text-xl font-semibold">{title}</h1>
      <p className="mt-2 text-sm text-zinc-600 dark:text-zinc-400">{children}</p>
      <Link to="/" className="mt-6 inline-block font-semibold text-brand-700 underline underline-offset-4 dark:text-brand-300">
        Go to your documents
      </Link>
    </main>
  );
}
