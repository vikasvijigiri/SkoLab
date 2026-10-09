import { useState } from "react";
import { Link, useNavigate } from "react-router";
import { useAuth } from "../auth/AuthProvider";
import { Alert } from "../components/Alert";
import { Button } from "../components/Button";
import { Logo } from "../components/Logo";
import { StorageFullError, type DocumentSummary } from "../editor/documents";
import { CATEGORIES, templateById, TEMPLATES, type Category, type Template } from "../editor/templates";
import { useDocumentStore } from "../editor/useDocumentStore";
import { fallbackName } from "../lib/validation";

const updatedFormat = new Intl.DateTimeFormat(undefined, { dateStyle: "medium", timeStyle: "short" });

/** The signed-in landing page: your documents, and templates to start a new one. */
export function Home() {
  const { user, profile, service, retryProfileSync } = useAuth();
  const store = useDocumentStore();
  const navigate = useNavigate();
  const [signingOut, setSigningOut] = useState(false);
  const [documents, setDocuments] = useState<DocumentSummary[]>(() => store.list());
  const [category, setCategory] = useState<Category | "All">("All");
  const [error, setError] = useState<string | null>(null);
  const name = user?.displayName?.trim() || fallbackName(user?.email ?? null);
  const initials = name
    .split(/\s+/)
    .slice(0, 2)
    .map((part) => part[0]?.toUpperCase() ?? "")
    .join("");

  function start(template: Template) {
    try {
      const doc = store.create(template.id === "blank" ? "Untitled document" : template.name, template.source, template.id);
      void navigate(`/editor/${doc.id}`);
    } catch (caught) {
      setError(caught instanceof StorageFullError ? "This browser's storage is full. Delete a document and try again." : "We couldn't create the document.");
    }
  }

  function remove(doc: DocumentSummary) {
    if (!window.confirm(`Delete "${doc.title}"? This can't be undone.`)) return;
    store.remove(doc.id);
    setDocuments(store.list());
  }

  const shown = category === "All" ? TEMPLATES : TEMPLATES.filter((template) => template.category === category);

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
            <Alert tone="success">You're all set. Pick a template below to start writing.</Alert>
          ) : (
            <Alert tone="info">Setting up your account…</Alert>
          )}
          {error && <Alert tone="error">{error}</Alert>}
        </div>

        <section aria-labelledby="documents-heading" className="mt-10">
          <h2 id="documents-heading" className="text-lg font-semibold">
            Your documents
          </h2>
          {documents.length === 0 ? (
            <p className="mt-2 text-sm text-zinc-600 dark:text-zinc-400">No documents yet. Start one from a template below.</p>
          ) : (
            <ul className="mt-3 divide-y divide-zinc-200 overflow-hidden rounded-xl border border-zinc-200 bg-white dark:divide-zinc-800 dark:border-zinc-800 dark:bg-zinc-900">
              {documents.map((doc) => (
                <li key={doc.id} className="flex items-center gap-3 px-4 py-3">
                  <div className="min-w-0 flex-1">
                    <Link to={`/editor/${doc.id}`} className="block truncate font-medium text-brand-700 hover:underline dark:text-brand-300">
                      {doc.title}
                    </Link>
                    <p className="truncate text-xs text-zinc-600 dark:text-zinc-400">
                      {(doc.templateId && templateById(doc.templateId)?.name) ?? "Blank"} · edited {updatedFormat.format(doc.updatedAt)}
                    </p>
                  </div>
                  <button
                    type="button"
                    onClick={() => remove(doc)}
                    aria-label={`Delete ${doc.title}`}
                    className="cursor-pointer rounded-md px-2 py-1 text-sm text-zinc-600 hover:bg-red-50 hover:text-red-700 dark:text-zinc-400 dark:hover:bg-red-950 dark:hover:text-red-300"
                  >
                    Delete
                  </button>
                </li>
              ))}
            </ul>
          )}
          <p className="mt-2 text-xs text-zinc-600 dark:text-zinc-400">Documents are saved in this browser for now.</p>
        </section>

        <section aria-labelledby="templates-heading" className="mt-10">
          <div className="flex flex-wrap items-end justify-between gap-3">
            <div>
              <h2 id="templates-heading" className="text-lg font-semibold">
                Start from a template
              </h2>
              <p className="text-sm text-zinc-600 dark:text-zinc-400">Official, openly licensed templates from journals, publishers and class authors.</p>
            </div>
            <div className="flex flex-wrap gap-1.5" role="group" aria-label="Filter templates">
              {(["All", ...CATEGORIES] as const).map((name) => (
                <button
                  key={name}
                  type="button"
                  aria-pressed={category === name}
                  onClick={() => setCategory(name)}
                  className={`cursor-pointer rounded-full border px-3 py-1 text-xs font-semibold ${
                    category === name
                      ? "border-brand-600 bg-brand-600 text-white"
                      : "border-zinc-300 bg-white text-zinc-700 hover:bg-zinc-50 dark:border-zinc-700 dark:bg-zinc-900 dark:text-zinc-200 dark:hover:bg-zinc-800"
                  }`}
                >
                  {name}
                </button>
              ))}
            </div>
          </div>
          <ul className="mt-4 grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
            {shown.map((template) => (
              <li key={template.id} className="flex flex-col rounded-xl border border-zinc-200 bg-white p-4 shadow-sm dark:border-zinc-800 dark:bg-zinc-900">
                <p className="text-xs font-semibold tracking-wide text-brand-700 uppercase dark:text-brand-300">{template.category}</p>
                <h3 className="mt-1 font-semibold">{template.name}</h3>
                <p className="mt-1 flex-1 text-sm text-zinc-600 dark:text-zinc-400">{template.description}</p>
                <p className="mt-3 text-xs text-zinc-600 dark:text-zinc-400">
                  {template.author} · {template.license}
                  {template.sourceUrl && (
                    <>
                      {" · "}
                      <a href={template.sourceUrl} target="_blank" rel="noreferrer" className="underline underline-offset-2">
                        source
                      </a>
                    </>
                  )}
                </p>
                <Button className="mt-3 !h-9" onClick={() => start(template)} aria-label={`Use the ${template.name} template`}>
                  Use template
                </Button>
              </li>
            ))}
          </ul>
        </section>
      </main>
    </div>
  );
}
