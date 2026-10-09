import { useEffect, useState } from "react";
import { Link, useNavigate } from "react-router";
import { ApiError } from "../api/client";
import {
  createWorkspace,
  deleteWorkspace,
  DOMAIN_LABELS,
  DOMAINS,
  getTemplate,
  listTemplates,
  listWorkspaces,
  saveDocument,
  type Domain,
  type TemplateSummary,
  type Workspace,
} from "../api/editor";
import { useAuth } from "../auth/AuthProvider";
import { useIdToken } from "../auth/useIdToken";
import { Alert } from "../components/Alert";
import { Button } from "../components/Button";
import { Logo } from "../components/Logo";
import { Spinner } from "../components/Spinner";
import { fallbackName } from "../lib/validation";

const createdFormat = new Intl.DateTimeFormat(undefined, { dateStyle: "medium" });

type Load<T> = { state: "loading" } | { state: "ready"; items: T[] } | { state: "error"; message: string };

function loadMessage(error: unknown, what: string): string {
  if (error instanceof ApiError && error.code === "network") return error.message;
  if (error instanceof ApiError && error.status === 401) return "Your session expired. Sign in again.";
  return `We couldn't load ${what}.`;
}

const ROLE_LABELS: Record<Workspace["role"], string> = {
  owner: "Owner",
  editor: "Can edit",
  commenter: "Can comment",
  viewer: "Can view",
};

/** The signed-in landing page: your documents, and journal templates to start a new one. */
export function Home() {
  const { user, profile, service, retryProfileSync } = useAuth();
  const idToken = useIdToken();
  const navigate = useNavigate();
  const [signingOut, setSigningOut] = useState(false);
  const [documents, setDocuments] = useState<Load<Workspace>>({ state: "loading" });
  const [templates, setTemplates] = useState<Load<TemplateSummary>>({ state: "loading" });
  const [reloads, setReloads] = useState(0);
  const [domain, setDomain] = useState<Domain | "all">("all");
  const [starting, setStarting] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const name = user?.displayName?.trim() || fallbackName(user?.email ?? null);
  const initials = name
    .split(/\s+/)
    .slice(0, 2)
    .map((part) => part[0]?.toUpperCase() ?? "")
    .join("");

  useEffect(() => {
    const controller = new AbortController();
    void (async () => {
      let token: string;
      try {
        token = await idToken();
      } catch (caught) {
        setDocuments({ state: "error", message: loadMessage(caught, "your documents") });
        setTemplates({ state: "error", message: loadMessage(caught, "the templates") });
        return;
      }
      const [docs, catalog] = await Promise.allSettled([listWorkspaces(token, controller.signal), listTemplates(token)]);
      if (controller.signal.aborted) return;
      setDocuments(docs.status === "fulfilled" ? { state: "ready", items: docs.value } : { state: "error", message: loadMessage(docs.reason, "your documents") });
      setTemplates(
        catalog.status === "fulfilled" ? { state: "ready", items: catalog.value } : { state: "error", message: loadMessage(catalog.reason, "the templates") },
      );
    })();
    return () => controller.abort();
  }, [idToken, reloads]);

  function retry() {
    setDocuments({ state: "loading" });
    setTemplates({ state: "loading" });
    setReloads((count) => count + 1);
  }

  async function start(template: TemplateSummary) {
    setStarting(template.id);
    setError(null);
    let workspace: Workspace | null = null;
    try {
      const token = await idToken();
      const [created, full] = await Promise.all([createWorkspace(token, template.name, crypto.randomUUID()), getTemplate(token, template.id)]);
      workspace = created;
      await saveDocument(token, created.id, { source: full.source, base_version: 0, template_id: template.id });
      void navigate(`/editor/${created.id}`);
    } catch (caught) {
      // Don't leave an empty document behind for a start that failed half way.
      if (workspace) void idToken().then((token) => deleteWorkspace(token, workspace?.id ?? "")).catch(() => undefined);
      setError(
        caught instanceof ApiError && caught.status === 429
          ? "You're creating documents too quickly. Wait a moment and try again."
          : caught instanceof ApiError && caught.code === "network"
            ? caught.message
            : "We couldn't create the document. Try again.",
      );
      setStarting(null);
    }
  }

  async function remove(doc: Workspace) {
    if (!window.confirm(`Delete "${doc.title}" for everyone it's shared with? This can't be undone.`)) return;
    setError(null);
    try {
      await deleteWorkspace(await idToken(), doc.id);
      setDocuments((current) => (current.state === "ready" ? { state: "ready", items: current.items.filter((item) => item.id !== doc.id) } : current));
    } catch (caught) {
      // Already gone is what the user wanted.
      if (caught instanceof ApiError && caught.status === 404) {
        setDocuments((current) => (current.state === "ready" ? { state: "ready", items: current.items.filter((item) => item.id !== doc.id) } : current));
        return;
      }
      setError(`We couldn't delete "${doc.title}". Try again.`);
    }
  }

  const catalog = templates.state === "ready" ? templates.items : [];
  const shown = domain === "all" ? catalog : catalog.filter((template) => template.domain === domain);

  return (
    <div className="min-h-dvh">
      <title>Home · SkoLab</title>
      <header className="border-b border-zinc-200 bg-white/80 backdrop-blur dark:border-zinc-800 dark:bg-zinc-950/80">
        <div className="mx-auto flex h-16 max-w-6xl items-center justify-between px-4 sm:px-6">
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
      <main id="main" className="mx-auto max-w-6xl px-4 py-10 sm:px-6">
        <div className="flex animate-enter items-center gap-4">
          <span aria-hidden="true" className="grid size-14 place-items-center rounded-full bg-brand-100 text-lg font-semibold text-brand-800 dark:bg-brand-900 dark:text-brand-100">
            {initials}
          </span>
          <div>
            <h1 className="text-2xl font-semibold tracking-tight">Welcome, {name}</h1>
            <p className="text-sm text-zinc-600 dark:text-zinc-400">{user?.email}</p>
          </div>
        </div>
        <div className="mt-6 max-w-xl space-y-3">
          {profile === "error" ? (
            <Alert tone="error">
              We couldn't finish setting up your account.{" "}
              <button type="button" onClick={retryProfileSync} className="cursor-pointer font-semibold underline underline-offset-4">
                Try again
              </button>
            </Alert>
          ) : profile === "synced" ? (
            <Alert tone="success">You're all set. Pick a journal template below to start writing.</Alert>
          ) : (
            <Alert tone="info">Setting up your account…</Alert>
          )}
          {error && <Alert tone="error">{error}</Alert>}
        </div>

        <section aria-labelledby="documents-heading" aria-busy={documents.state === "loading"} className="mt-10">
          <h2 id="documents-heading" className="text-lg font-semibold">
            Your documents
          </h2>
          {documents.state === "loading" ? (
            <p role="status" className="mt-3 flex items-center gap-2 text-sm text-zinc-600 dark:text-zinc-400">
              <Spinner /> Loading your documents…
            </p>
          ) : documents.state === "error" ? (
            <div className="mt-3 max-w-xl">
              <Alert tone="error">
                {documents.message}{" "}
                <button type="button" onClick={retry} className="cursor-pointer font-semibold underline underline-offset-4">
                  Retry
                </button>
              </Alert>
            </div>
          ) : documents.items.length === 0 ? (
            <p className="mt-2 text-sm text-zinc-600 dark:text-zinc-400">No documents yet. Start one from a template below.</p>
          ) : (
            <ul className="mt-3 divide-y divide-zinc-200 overflow-hidden rounded-xl border border-zinc-200 bg-white dark:divide-zinc-800 dark:border-zinc-800 dark:bg-zinc-900">
              {documents.items.map((doc) => (
                <li key={doc.id} className="flex items-center gap-3 px-4 py-3">
                  <div className="min-w-0 flex-1">
                    <Link to={`/editor/${doc.id}`} className="block truncate font-medium text-brand-700 hover:underline dark:text-brand-300">
                      {doc.title}
                    </Link>
                    <p className="truncate text-xs text-zinc-600 dark:text-zinc-400">
                      {ROLE_LABELS[doc.role]} · created {createdFormat.format(new Date(doc.created_at))}
                    </p>
                  </div>
                  {doc.role === "owner" && (
                    <button
                      type="button"
                      onClick={() => void remove(doc)}
                      aria-label={`Delete ${doc.title}`}
                      className="cursor-pointer rounded-md px-2 py-1 text-sm text-zinc-600 hover:bg-red-50 hover:text-red-700 dark:text-zinc-400 dark:hover:bg-red-950 dark:hover:text-red-300"
                    >
                      Delete
                    </button>
                  )}
                </li>
              ))}
            </ul>
          )}
          <p className="mt-2 text-xs text-zinc-600 dark:text-zinc-400">Documents are saved to your SkoLab account and open on any device.</p>
        </section>

        <section aria-labelledby="templates-heading" aria-busy={templates.state === "loading"} className="mt-10">
          <div className="flex flex-wrap items-end justify-between gap-3">
            <div>
              <h2 id="templates-heading" className="text-lg font-semibold">
                Start from a journal template
              </h2>
              <p className="text-sm text-zinc-600 dark:text-zinc-400">The publishers' own LaTeX templates, unchanged apart from what each file's header lists.</p>
            </div>
            <div className="flex flex-wrap gap-1.5" role="group" aria-label="Filter templates by field">
              {(["all", ...DOMAINS] as const).map((key) => (
                <button
                  key={key}
                  type="button"
                  aria-pressed={domain === key}
                  onClick={() => setDomain(key)}
                  className={`cursor-pointer rounded-full border px-3 py-1 text-xs font-semibold ${
                    domain === key
                      ? "border-brand-600 bg-brand-600 text-white"
                      : "border-zinc-300 bg-white text-zinc-700 hover:bg-zinc-50 dark:border-zinc-700 dark:bg-zinc-900 dark:text-zinc-200 dark:hover:bg-zinc-800"
                  }`}
                >
                  {key === "all" ? "All" : DOMAIN_LABELS[key]}
                </button>
              ))}
            </div>
          </div>
          {templates.state === "loading" ? (
            <p role="status" className="mt-4 flex items-center gap-2 text-sm text-zinc-600 dark:text-zinc-400">
              <Spinner /> Loading templates…
            </p>
          ) : templates.state === "error" ? (
            <div className="mt-4 max-w-xl">
              <Alert tone="error">
                {templates.message}{" "}
                <button type="button" onClick={retry} className="cursor-pointer font-semibold underline underline-offset-4">
                  Retry
                </button>
              </Alert>
            </div>
          ) : (
            <ul className="mt-4 grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
              {shown.map((template) => (
                <li key={template.id} className="flex flex-col rounded-xl border border-zinc-200 bg-white p-4 shadow-sm dark:border-zinc-800 dark:bg-zinc-900">
                  <p className="text-xs font-semibold tracking-wide text-brand-700 uppercase dark:text-brand-300">{DOMAIN_LABELS[template.domain]}</p>
                  <h3 className="mt-1 font-semibold">{template.name}</h3>
                  <p className="mt-0.5 text-xs font-medium text-zinc-700 dark:text-zinc-300">{template.journals}</p>
                  <p className="mt-2 flex-1 text-sm text-zinc-600 dark:text-zinc-400">{template.description}</p>
                  <p className="mt-3 text-xs text-zinc-600 dark:text-zinc-400">
                    {template.publisher} · <code>{template.class}</code> · {template.license} ·{" "}
                    <a href={template.source_url} target="_blank" rel="noreferrer" className="underline underline-offset-2">
                      source
                    </a>
                  </p>
                  <Button
                    className="mt-3 !h-9"
                    loading={starting === template.id}
                    disabled={starting !== null}
                    onClick={() => void start(template)}
                    aria-label={`Use the ${template.name} template`}
                  >
                    Use template
                  </Button>
                </li>
              ))}
            </ul>
          )}
        </section>
      </main>
    </div>
  );
}
