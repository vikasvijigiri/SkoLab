import { useCallback, useEffect, useRef, useState, type DragEvent } from "react";
import { Outlet, useNavigate } from "react-router";
import { ApiError } from "../api/client";
import {
  createWorkspace,
  deleteWorkspace,
  DOMAIN_LABELS,
  getTemplate,
  listTemplates,
  listWorkspaces,
  saveDocument,
  type TemplateSummary,
  type Workspace,
  type WorkspaceCard,
} from "../api/editor";
import { MAX_UPLOAD_BYTES } from "../api/editorTypes";
import { importProject } from "../api/files";
import { useAuth } from "../auth/AuthProvider";
import { useIdToken } from "../auth/useIdToken";
import { Alert } from "../components/Alert";
import { AppHeader, type NewKind } from "../components/AppHeader";
import { FileTextIcon, UploadIcon } from "../components/Icons";
import { Sheet } from "../components/Sheet";
import type { Command } from "../editor/CommandBar";
import { droppedFiles, packProject, pickedFiles, type PackedProject, type PickedFile } from "../lib/projectUpload";
import { fallbackName } from "../lib/validation";
import type { Load, Shell, UploadKind } from "./shell";

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

function createMessage(error: unknown): string {
  if (error instanceof ApiError && error.status === 429) return "You're creating documents too quickly. Wait a moment and try again.";
  if (error instanceof ApiError && error.code === "network") return error.message;
  return "We couldn't create the document. Try again.";
}

/** A packed upload waiting for the reader to say which .tex file is the document. */
type Choosing = PackedProject & { candidates: string[] };

/** Whether a drag carries files (not text or a link). */
const carriesFiles = (event: DragEvent<HTMLElement>) => Array.from(event.dataTransfer.types).includes("Files");

export const ROLE_LABELS: Record<Workspace["role"], string> = {
  owner: "Owner",
  editor: "Can edit",
  commenter: "Can comment",
  viewer: "Can view",
};

/**
 * The signed-in shell around the Documents and Templates pages: the header,
 * the ways to start a document (blank, template, upload or drop), and the
 * documents and templates, loaded once for both pages and for search.
 */
export function Home() {
  const { user, profile, service, retryProfileSync } = useAuth();
  const idToken = useIdToken();
  const navigate = useNavigate();
  const [signingOut, setSigningOut] = useState(false);
  const [documents, setDocuments] = useState<Load<WorkspaceCard>>({ state: "loading" });
  const [templates, setTemplates] = useState<Load<TemplateSummary>>({ state: "loading" });
  const [reloads, setReloads] = useState(0);
  const [starting, setStarting] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [importing, setImporting] = useState(false);
  const [choosing, setChoosing] = useState<Choosing | null>(null);
  const [dragging, setDragging] = useState(false);
  const pickers = useRef<Partial<Record<UploadKind, HTMLInputElement | null>>>({});
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

  const retry = useCallback(() => {
    setDocuments({ state: "loading" });
    setTemplates({ state: "loading" });
    setReloads((count) => count + 1);
  }, []);

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
      setError(createMessage(caught));
      setCreating(false);
    }
  }

  async function startTemplate(template: TemplateSummary) {
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
      setError(createMessage(caught));
      setStarting(null);
    }
  }

  function startNew(kind: NewKind) {
    if (kind === "blank") void startBlank();
    else if (kind === "template") void navigate("/templates");
    else pickers.current[kind]?.click();
  }

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

  async function remove(doc: WorkspaceCard) {
    if (!window.confirm(`Delete "${doc.title}" for everyone it's shared with? This can't be undone.`)) return;
    setError(null);
    const drop = () => setDocuments((current) => (current.state === "ready" ? { state: "ready", items: current.items.filter((item) => item.id !== doc.id) } : current));
    try {
      await deleteWorkspace(await idToken(), doc.id);
      drop();
    } catch (caught) {
      // Already gone is what the user wanted.
      if (caught instanceof ApiError && caught.status === 404) drop();
      else setError(`We couldn't delete "${doc.title}". Try again.`);
    }
  }

  const catalog = templates.state === "ready" ? templates.items : [];
  const searchCommands: Command[] = [
    ...(documents.state === "ready" ? documents.items : []).map((doc) => ({
      id: `doc-${doc.id}`,
      title: doc.title,
      group: "Documents",
      note: ROLE_LABELS[doc.role],
      keywords: `open document paper ${doc.authors.map((author) => author.display_name).join(" ")}`,
      run: () => void navigate(`/editor/${doc.id}`),
    })),
    ...catalog.map((template) => ({
      id: `template-${template.id}`,
      title: `New from ${template.name}`,
      group: "Templates",
      note: DOMAIN_LABELS[template.domain],
      keywords: `${template.journals} ${template.publisher} ${template.class} template journal`,
      run: () => void startTemplate(template),
    })),
  ];

  const shell: Shell = {
    name,
    token: idToken,
    documents,
    templates,
    retry,
    creating,
    startBlank: () => void startBlank(),
    starting,
    startTemplate: (template) => void startTemplate(template),
    importing,
    upload: (kind) => pickers.current[kind]?.click(),
    remove: (doc) => void remove(doc),
  };

  return (
    // A drop target for projects; the New menu is the keyboard and touch way in.
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
      {importing && (
        <p role="status" className="fixed bottom-5 left-1/2 z-40 -translate-x-1/2 animate-fade rounded-full bg-zinc-900 px-4 py-2 text-sm font-medium text-white shadow-lg dark:bg-white dark:text-zinc-900">
          Importing your project…
        </p>
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
        creating={creating || importing}
        onNew={startNew}
        signingOut={signingOut}
        onSignOut={() => {
          setSigningOut(true);
          void service.signOut();
        }}
      />

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

      <main id="main" className="mx-auto max-w-7xl px-4 pt-8 pb-16 sm:px-6">
        {(profile === "error" || error) && (
          <div className="mb-6 max-w-2xl space-y-3">
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
        <Outlet context={shell} />
      </main>
    </div>
  );
}

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
