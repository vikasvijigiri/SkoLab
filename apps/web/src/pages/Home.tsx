import { useEffect, useRef, useState, type DragEvent, type ReactElement } from "react";
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
import { MAX_UPLOAD_BYTES } from "../api/editorTypes";
import { importProject } from "../api/files";
import { useAuth } from "../auth/AuthProvider";
import { useIdToken } from "../auth/useIdToken";
import { Alert } from "../components/Alert";
import {
  ArrowRightIcon,
  AtomIcon,
  DnaIcon,
  ExternalIcon,
  FileTextIcon,
  FlaskIcon,
  FolderIcon,
  GridIcon,
  SigmaIcon,
  SparkIcon,
  TrashIcon,
  UploadIcon,
  ZipIcon,
} from "../components/Icons";
import { AppHeader, type NewKind } from "../components/AppHeader";
import { Sheet } from "../components/Sheet";
import { Spinner } from "../components/Spinner";
import type { Command } from "../editor/CommandBar";
import { droppedFiles, packProject, pickedFiles, type PackedProject, type PickedFile } from "../lib/projectUpload";
import { fallbackName } from "../lib/validation";

const createdFormat = new Intl.DateTimeFormat(undefined, { dateStyle: "medium" });

type Load<T> = { state: "loading" } | { state: "ready"; items: T[] } | { state: "error"; message: string };

function loadMessage(error: unknown, what: string): string {
  if (error instanceof ApiError && error.code === "network") return error.message;
  if (error instanceof ApiError && error.status === 401) return "Your session expired. Sign in again.";
  return `We couldn't load ${what}.`;
}

/** Why an upload was refused, in words. */
function importMessage(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.code === "network") return error.message;
    if (error.code === "no_tex_file") return "There's no .tex file in what you picked. Upload a .zip, a project folder, or .tex files.";
    if (error.code === "no_main_file")
      return "We couldn't find the main file: there's no main.tex, and no .tex file at the top of the project with \\documentclass. Add one and try again.";
    if (error.code === "invalid_main_file") return "That main file isn't at the top of the project. Upload the project again.";
    if (error.code === "invalid_archive" || error.status === 415) return "That file isn't a zip we can read. Choose a .zip of your LaTeX project.";
    if (error.code === "main_too_large") return "The main .tex file is too long. It can be up to 100,000 characters of UTF-8 text.";
    if (error.code === "workspace_limit_reached") return "You have the most documents you can keep. Delete one, then import again.";
    if (error.status === 413) return "That project is too large. It can be up to 12 MB, and unpack to at most 10 MB in 200 files and folders.";
    if (error.status === 429) return "You're creating documents too quickly. Wait a moment and try again.";
    if (error.status === 401) return "Your session expired. Sign in again.";
  }
  return "We couldn't import the project. Try again.";
}

/** The ways in, each its own picker: a zip, a folder, or loose files. */
const UPLOADS = [
  { kind: "zip", title: ".zip archive", detail: "From Overleaf, GitHub or anywhere else" },
  { kind: "folder", title: "Folder", detail: "A project folder on your computer" },
  { kind: "files", title: ".tex files", detail: "One or more, with their .bib and figures" },
] as const;
type UploadKind = (typeof UPLOADS)[number]["kind"];

/** A packed upload waiting for the reader to say which .tex file is the document. */
type Choosing = PackedProject & { candidates: string[] };

/** Whether a drag carries files (not text or a link). */
const carriesFiles = (event: DragEvent<HTMLElement>) => Array.from(event.dataTransfer.types).includes("Files");

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
  const [importing, setImporting] = useState(false);
  const [uploadMenu, setUploadMenu] = useState(false);
  const [choosing, setChoosing] = useState<Choosing | null>(null);
  const [dragging, setDragging] = useState(false);
  const pickers = useRef<Partial<Record<UploadKind, HTMLInputElement | null>>>({});
  const menuBox = useRef<HTMLDivElement>(null);
  const [creating, setCreating] = useState(false);
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

  /** A new empty article, opened in the editor. */
  async function startBlank() {
    setCreating(true);
    setError(null);
    let workspace: Workspace | null = null;
    try {
      const token = await idToken();
      workspace = await createWorkspace(token, "Untitled document", crypto.randomUUID());
      await saveDocument(token, workspace.id, { source: BLANK_DOCUMENT, base_version: 0 });
      void navigate(`/editor/${workspace.id}`);
    } catch (caught) {
      if (workspace) void idToken().then((token) => deleteWorkspace(token, workspace?.id ?? "")).catch(() => undefined);
      setError(
        caught instanceof ApiError && caught.status === 429
          ? "You're creating documents too quickly. Wait a moment and try again."
          : caught instanceof ApiError && caught.code === "network"
            ? caught.message
            : "We couldn't create the document. Try again.",
      );
      setCreating(false);
    }
  }

  function startNew(kind: NewKind) {
    if (kind === "blank") void startBlank();
    else if (kind === "template") document.getElementById("templates-heading")?.scrollIntoView({ behavior: "smooth", block: "start" });
    else pickers.current[kind]?.click();
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

  // The upload menu closes on Escape or a click elsewhere.
  useEffect(() => {
    if (!uploadMenu) return;
    const away = (event: PointerEvent) => {
      if (!menuBox.current?.contains(event.target as Node)) setUploadMenu(false);
    };
    const escape = (event: KeyboardEvent) => {
      if (event.key === "Escape") setUploadMenu(false);
    };
    document.addEventListener("pointerdown", away);
    document.addEventListener("keydown", escape);
    return () => {
      document.removeEventListener("pointerdown", away);
      document.removeEventListener("keydown", escape);
    };
  }, [uploadMenu]);

  /** Sends a packed project; asks which file is main when the project has several. */
  async function sendProject(packed: PackedProject, main?: string) {
    setImporting(true);
    try {
      // Loose files have no folder to name the document after; the chosen main file does.
      const title = packed.title ?? main?.replace(/\.tex$/i, "");
      const created = await importProject(await idToken(), packed.archive, { ...(title ? { title } : {}), ...(main ? { main } : {}) });
      void navigate(`/editor/${created.id}`, { state: { skipped: [...packed.skipped, ...created.skipped] } });
    } catch (caught) {
      const candidates = caught instanceof ApiError && caught.code === "main_file_ambiguous" ? caught.details.candidates : null;
      if (Array.isArray(candidates) && candidates.every((name) => typeof name === "string")) setChoosing({ ...packed, candidates });
      else setError(importMessage(caught));
      setImporting(false);
    }
  }

  /** A .zip, a folder or loose files, picked or dropped: packed into one archive and imported. */
  async function importPicked(picked: PickedFile[]) {
    setError(null);
    setUploadMenu(false);
    if (picked.length === 0) return;
    setImporting(true);
    let packed: PackedProject;
    try {
      packed = await packProject(picked);
    } catch (caught) {
      setError(importMessage(caught));
      setImporting(false);
      return;
    }
    if (packed.archive.size > MAX_UPLOAD_BYTES) {
      setError(importMessage(new ApiError(413, "body_too_large", "")));
      setImporting(false);
      return;
    }
    await sendProject(packed);
  }

  function chooseMain(main: string) {
    const pending = choosing;
    setChoosing(null);
    if (pending) void sendProject(pending, main);
  }

  function pickerChanged(input: HTMLInputElement) {
    const picked = pickedFiles(input.files ?? []);
    input.value = "";
    void importPicked(picked);
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
  const searchCommands: Command[] = [
    ...(documents.state === "ready" ? documents.items : []).map((doc) => ({
      id: `doc-${doc.id}`,
      title: doc.title,
      group: "Documents",
      note: ROLE_LABELS[doc.role],
      keywords: "open document paper",
      run: () => void navigate(`/editor/${doc.id}`),
    })),
    ...catalog.map((template) => ({
      id: `template-${template.id}`,
      title: `New from ${template.name}`,
      group: "Templates",
      note: DOMAIN_LABELS[template.domain],
      keywords: `${template.journals} ${template.publisher} ${template.class} template journal`,
      run: () => void start(template),
    })),
  ];
  const count = (key: Domain | "all") => (key === "all" ? catalog.length : catalog.filter((template) => template.domain === key).length);

  return (
    // A drop target for projects; the Upload project menu is the keyboard and touch way in.
    // eslint-disable-next-line jsx-a11y/no-static-element-interactions
    <div
      className="min-h-dvh"
      onDragOver={(event) => {
        if (!carriesFiles(event) || importing) return;
        event.preventDefault();
        event.dataTransfer.dropEffect = "copy";
        setDragging(true);
      }}
      onDragLeave={(event) => {
        // Only when the drag leaves the window, not when it crosses into a child.
        if (event.relatedTarget === null) setDragging(false);
      }}
      onDrop={(event) => {
        if (!carriesFiles(event)) return;
        event.preventDefault();
        setDragging(false);
        if (importing) return;
        // Read the transfer now: the browser empties it once this handler returns.
        void droppedFiles(event.dataTransfer).then(importPicked);
      }}
    >
      <title>Home · SkoLab</title>
      {dragging && (
        <div aria-hidden="true" className="pointer-events-none fixed inset-3 z-50 grid animate-fade place-items-center rounded-3xl border-2 border-dashed border-brand-500 bg-brand-50/90 backdrop-blur-sm dark:bg-brand-950/85">
          <div className="text-center">
            <span className="mx-auto grid size-16 place-items-center rounded-2xl bg-brand-600 text-white shadow-lg shadow-brand-600/30">
              <UploadIcon className="size-8" />
            </span>
            <p className="mt-4 text-xl font-semibold text-brand-900 dark:text-brand-50">Drop to start a project</p>
            <p className="mt-1 text-[15px] text-brand-700 dark:text-brand-200">A .zip, a project folder, or .tex files with their figures</p>
          </div>
        </div>
      )}
      {choosing && (
        <Sheet title="Which file is the main document?" description="Several .tex files in this project start a document. Pick the one to compile." onClose={() => setChoosing(null)}>
          <ul className="space-y-2">
            {choosing.candidates.map((candidate, n) => (
              <li key={candidate}>
                <button
                  type="button"
                  {...(n === 0 ? { "data-autofocus": true } : {})}
                  onClick={() => chooseMain(candidate)}
                  className="flex w-full cursor-pointer items-center gap-3 rounded-xl border border-zinc-200 bg-white px-3.5 py-3 text-left font-mono text-sm transition-colors hover:border-brand-500 hover:bg-brand-50 dark:border-zinc-700 dark:bg-zinc-900 dark:hover:bg-zinc-800"
                >
                  <FileTextIcon className="size-5 text-brand-600 dark:text-brand-400" />
                  {candidate}
                </button>
              </li>
            ))}
          </ul>
        </Sheet>
      )}

      <AppHeader
        name={name}
        email={user?.email ?? ""}
        initials={initials}
        me={user?.uid ?? ""}
        token={idToken}
        commands={searchCommands}
        creating={creating}
        onNew={startNew}
        signingOut={signingOut}
        onSignOut={() => {
          setSigningOut(true);
          void service.signOut();
        }}
      />

      <main id="main" className="mx-auto max-w-6xl px-4 pt-8 pb-16 sm:px-6">
        <section aria-labelledby="welcome-heading" className="relative z-10 animate-enter rounded-3xl bg-gradient-to-br from-brand-600 via-indigo-600 to-violet-700 px-6 py-8 text-white shadow-xl shadow-brand-900/15 sm:px-10 sm:py-10">
          {/* Decoration: soft light and a faint grid, clipped to the panel (the upload menu is not). */}
          <div aria-hidden="true" className="pointer-events-none absolute inset-0 overflow-hidden rounded-3xl">
            <div className="absolute -top-24 -right-16 size-72 rounded-full bg-white/15 blur-3xl" />
            <div className="absolute -bottom-28 left-1/3 size-72 rounded-full bg-fuchsia-400/20 blur-3xl" />
          </div>
          <svg aria-hidden="true" className="pointer-events-none absolute inset-0 size-full rounded-3xl opacity-[0.07]">
            <defs>
              <pattern id="home-grid" width="28" height="28" patternUnits="userSpaceOnUse">
                <path d="M28 0H0V28" fill="none" stroke="white" strokeWidth="1" />
              </pattern>
            </defs>
            <rect width="100%" height="100%" fill="url(#home-grid)" />
          </svg>

          <div className="relative flex flex-col gap-8 lg:flex-row lg:items-end lg:justify-between">
            <div className="flex items-start gap-4">
              <div>
                <p className="text-sm font-medium text-brand-100">{greeting()}</p>
                <h1 id="welcome-heading" className="mt-0.5 text-3xl font-semibold tracking-tight sm:text-4xl">
                  Welcome, {name}
                </h1>
                <p className="mt-2 max-w-lg text-[15px] leading-relaxed text-brand-50/90">
                  {profile === "synced"
                    ? "You're all set. Start from a journal template, or bring a project you already have."
                    : profile === "error"
                      ? "Your account isn't fully set up yet."
                      : "Setting up your account…"}
                </p>
                <p className="mt-1 text-sm text-brand-100/80">{user?.email}</p>
              </div>
            </div>

            <div className="flex flex-col gap-2.5 sm:flex-row sm:items-center lg:flex-col lg:items-stretch xl:flex-row xl:items-center">
              <div ref={menuBox} className="relative">
                <button
                  type="button"
                  disabled={importing}
                  aria-haspopup="menu"
                  aria-expanded={uploadMenu}
                  onClick={() => setUploadMenu(!uploadMenu)}
                  title="Start a document from a LaTeX project: a .zip (such as one downloaded from Overleaf), a folder, or .tex files. Or drop them on this page."
                  className="flex h-11 w-full cursor-pointer items-center justify-center gap-2 rounded-xl bg-white px-5 text-[15px] font-semibold text-brand-700 shadow-lg shadow-brand-950/20 transition hover:-translate-y-px hover:bg-brand-50 disabled:cursor-wait disabled:opacity-80 sm:w-auto"
                >
                  {importing ? <Spinner /> : <UploadIcon className="size-[18px]" />}
                  Upload project
                </button>
                {uploadMenu && (
                  <div
                    role="menu"
                    aria-label="Upload a project"
                    className="absolute right-0 z-20 mt-2 w-80 max-w-[calc(100vw-2rem)] animate-fade rounded-2xl border border-zinc-200 bg-white p-2 text-zinc-900 shadow-2xl shadow-zinc-900/15 dark:border-zinc-700 dark:bg-zinc-900 dark:text-zinc-100"
                  >
                    {UPLOADS.map((item) => (
                      <button
                        key={item.kind}
                        type="button"
                        role="menuitem"
                        onClick={() => pickers.current[item.kind]?.click()}
                        className="flex w-full cursor-pointer items-center gap-3 rounded-xl px-3 py-2.5 text-left transition-colors hover:bg-zinc-100 dark:hover:bg-zinc-800"
                      >
                        <span aria-hidden="true" className="grid size-10 shrink-0 place-items-center rounded-lg bg-brand-50 text-brand-600 dark:bg-brand-950 dark:text-brand-300">
                          {item.kind === "zip" ? <ZipIcon /> : item.kind === "folder" ? <FolderIcon /> : <FileTextIcon />}
                        </span>
                        <span className="min-w-0">
                          <span className="block text-sm font-semibold">{item.title}</span>
                          <span className="block text-xs text-zinc-600 dark:text-zinc-400">{item.detail}</span>
                        </span>
                      </button>
                    ))}
                    <p className="mt-1 border-t border-zinc-200 px-3 pt-2.5 pb-1 text-xs text-zinc-500 dark:border-zinc-700 dark:text-zinc-400">Or drop any of these on this page.</p>
                  </div>
                )}
              </div>
              <a
                href="#templates-heading"
                className="flex h-11 items-center justify-center gap-2 rounded-xl px-5 text-[15px] font-semibold text-white ring-1 ring-white/35 transition hover:bg-white/10"
              >
                <GridIcon className="size-[18px]" />
                Browse templates
              </a>
            </div>
          </div>
          <p className="relative mt-6 hidden items-center gap-2 text-xs text-brand-100/80 sm:flex">
            <SparkIcon className="size-4" />
            Tip: drop a .zip, a folder or .tex files anywhere on this page to import a project, Overleaf style.
          </p>
        </section>

        <input
          ref={(input) => {
            pickers.current.zip = input;
          }}
          type="file"
          hidden
          accept=".zip,application/zip"
          aria-label="Choose a project .zip to upload"
          onChange={(event) => pickerChanged(event.target)}
        />
        <input
          ref={(input) => {
            pickers.current.folder = input;
            // Not in React's types; every current browser supports picking a folder.
            input?.setAttribute("webkitdirectory", "");
          }}
          type="file"
          hidden
          multiple
          aria-label="Choose a project folder to upload"
          onChange={(event) => pickerChanged(event.target)}
        />
        <input
          ref={(input) => {
            pickers.current.files = input;
          }}
          type="file"
          hidden
          multiple
          aria-label="Choose .tex files to upload"
          onChange={(event) => pickerChanged(event.target)}
        />

        {(profile === "error" || error) && (
          <div className="mt-6 max-w-2xl space-y-3">
            {profile === "error" && (
              <Alert tone="error">
                We couldn't finish setting up your account.{" "}
                <button type="button" onClick={retryProfileSync} className="cursor-pointer font-semibold underline underline-offset-4">
                  Try again
                </button>
              </Alert>
            )}
            {error && <Alert tone="error">{error}</Alert>}
          </div>
        )}

        <section aria-labelledby="documents-heading" aria-busy={documents.state === "loading"} className="mt-12">
          <div className="flex flex-wrap items-end justify-between gap-2">
            <div>
              <h2 id="documents-heading" className="text-xl font-semibold tracking-tight">
                Your documents
              </h2>
              <p className="mt-1 text-sm text-zinc-600 dark:text-zinc-400">Documents are saved to your SkoLab account and open on any device.</p>
            </div>
            {documents.state === "ready" && documents.items.length > 0 && (
              <span className="rounded-full bg-zinc-100 px-2.5 py-0.5 text-xs font-semibold text-zinc-600 tabular-nums dark:bg-zinc-800 dark:text-zinc-300">
                {documents.items.length} {documents.items.length === 1 ? "document" : "documents"}
              </span>
            )}
          </div>
          {documents.state === "loading" ? (
            <p role="status" className="mt-4 flex items-center gap-2 text-sm text-zinc-600 dark:text-zinc-400">
              <Spinner /> Loading your documents…
            </p>
          ) : documents.state === "error" ? (
            <div className="mt-4 max-w-xl">
              <Alert tone="error">
                {documents.message}{" "}
                <button type="button" onClick={retry} className="cursor-pointer font-semibold underline underline-offset-4">
                  Retry
                </button>
              </Alert>
            </div>
          ) : documents.items.length === 0 ? (
            <div className="mt-4 flex flex-col items-center rounded-2xl border-2 border-dashed border-zinc-300 bg-white/60 px-6 py-8 text-center dark:border-zinc-700 dark:bg-zinc-900/40">
              <span aria-hidden="true" className="grid size-14 place-items-center rounded-2xl bg-brand-50 text-brand-600 dark:bg-brand-950 dark:text-brand-300">
                <FileTextIcon className="size-7" />
              </span>
              <p className="mt-4 text-[15px] font-semibold">No documents yet. Start one from a template below.</p>
              <p className="mt-1 max-w-md text-sm text-zinc-600 dark:text-zinc-400">Or drop a .zip, a project folder, or .tex files right here to bring an existing project in.</p>
            </div>
          ) : (
            <ul className="mt-4 grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
              {documents.items.map((doc) => (
                <li
                  key={doc.id}
                  className="group relative flex items-start gap-3 rounded-2xl border border-zinc-200 bg-white p-4 shadow-xs transition hover:-translate-y-0.5 hover:border-brand-300 hover:shadow-md dark:border-zinc-800 dark:bg-zinc-900 dark:hover:border-brand-700"
                >
                  <span aria-hidden="true" className="grid size-11 shrink-0 place-items-center rounded-xl bg-gradient-to-br from-brand-50 to-violet-100 text-brand-600 dark:from-brand-950 dark:to-violet-950 dark:text-brand-300">
                    <FileTextIcon className="size-[22px]" />
                  </span>
                  <div className="min-w-0 flex-1">
                    {/* The whole card opens the document; the link's box stretches over it. */}
                    <Link to={`/editor/${doc.id}`} className="block truncate text-[15px] font-semibold text-zinc-900 after:absolute after:inset-0 after:rounded-2xl hover:text-brand-700 dark:text-zinc-100 dark:hover:text-brand-300">
                      {doc.title}
                    </Link>
                    <p className="mt-1 flex items-center gap-1.5 truncate text-xs text-zinc-600 dark:text-zinc-400">
                      <span className={`inline-block size-1.5 rounded-full ${doc.role === "owner" ? "bg-emerald-500" : doc.role === "editor" ? "bg-sky-500" : "bg-zinc-400"}`} aria-hidden="true" />
                      {ROLE_LABELS[doc.role]} · created {createdFormat.format(new Date(doc.created_at))}
                    </p>
                  </div>
                  {doc.role === "owner" && (
                    <button
                      type="button"
                      onClick={() => void remove(doc)}
                      aria-label={`Delete ${doc.title}`}
                      title="Delete"
                      className="relative z-10 grid size-9 shrink-0 cursor-pointer place-items-center rounded-lg text-zinc-400 transition hover:bg-red-50 hover:text-red-600 focus-visible:opacity-100 sm:opacity-0 sm:group-hover:opacity-100 dark:hover:bg-red-950 dark:hover:text-red-300"
                    >
                      <TrashIcon className="size-[18px]" />
                    </button>
                  )}
                </li>
              ))}
            </ul>
          )}
        </section>

        <section aria-labelledby="templates-heading" aria-busy={templates.state === "loading"} className="mt-14 scroll-mt-24">
          <div className="flex flex-col gap-4">
            <div>
              <h2 id="templates-heading" className="scroll-mt-24 text-xl font-semibold tracking-tight">
                Start from a journal template
              </h2>
              <p className="mt-1 max-w-xl text-sm text-zinc-600 dark:text-zinc-400">The publishers' own LaTeX templates, unchanged apart from what each file's header lists.</p>
            </div>
            <div className="flex flex-wrap gap-2" role="group" aria-label="Filter templates by field">
              {(["all", ...DOMAINS] as const).map((key) => {
                const on = domain === key;
                const look = key === "all" ? null : DOMAIN_LOOK[key];
                return (
                  <button
                    key={key}
                    type="button"
                    aria-pressed={on}
                    onClick={() => setDomain(key)}
                    className={`flex h-9 shrink-0 cursor-pointer items-center gap-1.5 rounded-full border px-3.5 text-sm font-semibold transition-colors ${
                      on
                        ? "border-transparent bg-zinc-900 text-white shadow-sm dark:bg-white dark:text-zinc-900"
                        : "border-zinc-200 bg-white text-zinc-700 hover:border-zinc-300 hover:bg-zinc-50 dark:border-zinc-700 dark:bg-zinc-900 dark:text-zinc-200 dark:hover:bg-zinc-800"
                    }`}
                  >
                    {look ? <look.Icon className={`size-4 ${on ? "" : look.text}`} /> : <GridIcon className="size-4" />}
                    {key === "all" ? "All" : DOMAIN_LABELS[key]}
                    {templates.state === "ready" && (
                      <span aria-hidden="true" className={`text-xs tabular-nums ${on ? "opacity-70" : "text-zinc-400"}`}>
                        {count(key)}
                      </span>
                    )}
                  </button>
                );
              })}
            </div>
          </div>
          {templates.state === "loading" ? (
            <p role="status" className="mt-5 flex items-center gap-2 text-sm text-zinc-600 dark:text-zinc-400">
              <Spinner /> Loading templates…
            </p>
          ) : templates.state === "error" ? (
            <div className="mt-5 max-w-xl">
              <Alert tone="error">
                {templates.message}{" "}
                <button type="button" onClick={retry} className="cursor-pointer font-semibold underline underline-offset-4">
                  Retry
                </button>
              </Alert>
            </div>
          ) : (
            <ul className="mt-5 grid gap-5 sm:grid-cols-2 lg:grid-cols-3">
              {shown.map((template) => {
                const look = DOMAIN_LOOK[template.domain];
                return (
                  <li
                    key={template.id}
                    className="group flex flex-col overflow-hidden rounded-2xl border border-zinc-200 bg-white shadow-xs transition hover:-translate-y-0.5 hover:shadow-lg dark:border-zinc-800 dark:bg-zinc-900"
                  >
                    <div aria-hidden="true" className={`h-1.5 bg-gradient-to-r ${look.bar}`} />
                    <div className="flex flex-1 flex-col p-5">
                      <div className="flex items-center gap-2.5">
                        <span aria-hidden="true" className={`grid size-9 place-items-center rounded-lg ${look.tile}`}>
                          <look.Icon className="size-5" />
                        </span>
                        <p className={`text-xs font-bold tracking-wider uppercase ${look.text}`}>{DOMAIN_LABELS[template.domain]}</p>
                        <a
                          href={template.source_url}
                          target="_blank"
                          rel="noreferrer"
                          className="ml-auto inline-flex items-center gap-1 rounded-md px-1.5 py-1 text-xs font-medium text-zinc-500 transition-colors hover:bg-zinc-100 hover:text-brand-700 dark:text-zinc-400 dark:hover:bg-zinc-800 dark:hover:text-brand-300"
                        >
                          source
                          <ExternalIcon className="size-3.5" />
                        </a>
                      </div>
                      <h3 className="mt-3 text-lg leading-snug font-semibold tracking-tight">{template.name}</h3>
                      <p className="mt-1 line-clamp-2 text-[13px] font-medium text-zinc-700 dark:text-zinc-300" title={template.journals}>
                        {template.journals}
                      </p>
                      <p className="mt-3 flex-1 text-[15px] leading-relaxed text-zinc-600 dark:text-zinc-400">{template.description}</p>
                      <div className="mt-4 flex flex-wrap items-center gap-1.5 text-xs text-zinc-600 dark:text-zinc-400">
                        <span className="truncate">{template.publisher}</span>
                        <code className="rounded-md bg-zinc-100 px-1.5 py-0.5 font-mono text-[11px] text-zinc-700 dark:bg-zinc-800 dark:text-zinc-300">{template.class}</code>
                        <span className="rounded-md border border-zinc-200 px-1.5 py-0.5 text-[11px] font-medium dark:border-zinc-700">{template.license}</span>
                      </div>
                      <button
                        type="button"
                        disabled={starting !== null}
                        onClick={() => void start(template)}
                        aria-label={`Use the ${template.name} template`}
                        className="mt-5 flex h-11 w-full cursor-pointer items-center justify-center gap-2 rounded-xl bg-brand-50 text-[15px] font-semibold text-brand-700 ring-1 ring-brand-200 transition group-hover:bg-brand-600 group-hover:text-white group-hover:ring-brand-600 hover:bg-brand-700 disabled:cursor-not-allowed disabled:opacity-60 dark:bg-brand-950 dark:text-brand-200 dark:ring-brand-800 dark:group-hover:bg-brand-500 dark:group-hover:text-white"
                      >
                        {starting === template.id ? <Spinner /> : null}
                        Use template
                        {starting !== template.id && <ArrowRightIcon className="size-4 transition-transform group-hover:translate-x-0.5" />}
                      </button>
                    </div>
                  </li>
                );
              })}
            </ul>
          )}
        </section>
      </main>
    </div>
  );
}

/** Morning, afternoon or evening, by the reader's clock. */
function greeting(): string {
  const hour = new Date().getHours();
  return hour < 5 ? "Working late" : hour < 12 ? "Good morning" : hour < 17 ? "Good afternoon" : "Good evening";
}

/** Each field's colour and icon: on its filter, its cards' accent bar and their icon tile. */
const DOMAIN_LOOK: Record<Domain, { Icon: (props: { className?: string | undefined }) => ReactElement; bar: string; tile: string; text: string }> = {
  physics: { Icon: AtomIcon, bar: "from-sky-400 to-indigo-500", tile: "bg-sky-50 text-sky-600 dark:bg-sky-950 dark:text-sky-300", text: "text-sky-700 dark:text-sky-300" },
  chemistry: { Icon: FlaskIcon, bar: "from-emerald-400 to-teal-500", tile: "bg-emerald-50 text-emerald-600 dark:bg-emerald-950 dark:text-emerald-300", text: "text-emerald-700 dark:text-emerald-300" },
  mathematics: { Icon: SigmaIcon, bar: "from-amber-400 to-orange-500", tile: "bg-amber-50 text-amber-600 dark:bg-amber-950 dark:text-amber-300", text: "text-amber-700 dark:text-amber-300" },
  biology: { Icon: DnaIcon, bar: "from-rose-400 to-pink-500", tile: "bg-rose-50 text-rose-600 dark:bg-rose-950 dark:text-rose-300", text: "text-rose-700 dark:text-rose-300" },
};

/** What "Blank document" starts from: a plain article that compiles. */
const BLANK_DOCUMENT = `\\documentclass[11pt]{article}
\\usepackage[utf8]{inputenc}
\\usepackage{amsmath,amssymb,graphicx,hyperref}

\\title{Untitled document}
\\author{}
\\date{\\today}

\\begin{document}
\\maketitle

\\section{Introduction}

\\end{document}
`;
