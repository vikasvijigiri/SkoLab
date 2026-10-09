import { lazy, Suspense, useCallback, useEffect, useRef, useState } from "react";
import { Link, useNavigate, useParams } from "react-router";
import { ApiError } from "../api/client";
import { compileLatex, MAX_SOURCE_LENGTH, pdfBytes, type CompileResult } from "../api/compile";
import {
  canEdit,
  characterCount,
  getDocument,
  getWorkspace,
  listTemplates,
  MAX_DOCUMENT_CHARACTERS,
  renameWorkspace,
  saveDocument,
  VersionConflictError,
  type Insights,
  type LatexDocument,
  type Member,
  type TemplateSummary,
  type Workspace,
} from "../api/editor";
import { listMembers } from "../api/sharing";
import { useAuth } from "../auth/AuthProvider";
import { useIdToken } from "../auth/useIdToken";
import { Alert } from "../components/Alert";
import { Button } from "../components/Button";
import { Sheet } from "../components/Sheet";
import { FullPageLoader, Spinner } from "../components/Spinner";
import { LatexEditor, type LatexEditorHandle } from "../editor/LatexEditor";
import { Overview, ProgressRing } from "../editor/Overview";
import { Avatar, Share } from "../editor/Share";
import { formatDate } from "../lib/dates";

const PdfPreview = lazy(() => import("../editor/PdfPreview").then((m) => ({ default: m.PdfPreview })));

type Compile =
  | { state: "idle" }
  | { state: "compiling" }
  | { state: "compiled" }
  | { state: "failed"; errors: string[]; log: string }
  | { state: "unavailable"; message: string };

const SAVE_DELAY_MS = 800;
const RETRY_DELAY_MS = 5_000;
const ZOOMS = [0.5, 0.75, 1, 1.25, 1.5, 2];

function stepZoom(zoom: number, step: 1 | -1): number {
  const index = Math.min(Math.max(ZOOMS.indexOf(zoom) + step, 0), ZOOMS.length - 1);
  return ZOOMS[index] ?? zoom;
}

function compileMessage(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.code === "compile_in_progress") return "A compile is already running for your account. Wait for it to finish, then try again.";
    if (error.status === 429) return "You've reached your compile limit for now. Try again in a little while.";
    if (error.code === "network") return error.message;
    if (error.status === 413) return "This document is too large to compile.";
    if (error.status === 401) return "Your session expired. Sign in again to compile.";
  }
  if (error instanceof DOMException && error.name === "TimeoutError") return "The compiler took too long to answer. Try again.";
  return "The compiler is unavailable right now. Your work is saved; try again shortly.";
}

/** Why a save failed, in words, and whether trying again later can help. */
function saveMessage(error: unknown): { message: string; retry: boolean } {
  if (error instanceof ApiError) {
    if (error.code === "network") return { message: "You're offline. We'll keep trying.", retry: true };
    if (error.status === 401) return { message: "Your session expired. Sign in again to save.", retry: false };
    if (error.status === 413) return { message: `Documents can be up to ${MAX_DOCUMENT_CHARACTERS.toLocaleString()} characters.`, retry: false };
    if (error.code === "invalid_source") return { message: "The document contains characters LaTeX source can't hold.", retry: false };
    if (error.status === 429) return { message: "Saving too often. We'll try again in a moment.", retry: true };
  }
  return { message: "SkoLab couldn't save just now. We'll keep trying.", retry: true };
}

/** Error lines from the compiler look like "line 12: Undefined control sequence." */
function lineOf(message: string): number | null {
  const match = /^line (\d+):/.exec(message);
  return match ? Number(match[1]) : null;
}

function download(name: string, data: BlobPart, type: string) {
  const url = URL.createObjectURL(new Blob([data], { type }));
  const link = document.createElement("a");
  link.href = url;
  link.download = name;
  link.click();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}

function fileName(title: string) {
  return title.trim().replace(/[^\w.-]+/g, "-").replace(/^-+|-+$/g, "") || "document";
}

interface Opened {
  workspace: Workspace;
  doc: LatexDocument;
  template: TemplateSummary | null;
}

type Page = { state: "loading" } | { state: "missing" } | { state: "error"; message: string } | { state: "ready"; opened: Opened; generation: number };

/** Loads a workspace's document, then hands it to the editor. */
export function Editor() {
  const { id = "" } = useParams();
  const idToken = useIdToken();
  const [page, setPage] = useState<Page>({ state: "loading" });
  const [loads, setLoads] = useState(0);

  useEffect(() => {
    const controller = new AbortController();
    void (async () => {
      try {
        const token = await idToken();
        const [workspace, doc] = await Promise.all([getWorkspace(token, id, controller.signal), getDocument(token, id, controller.signal)]);
        // The catalog only labels the document; a failure to load it isn't worth blocking on.
        const catalog = doc.template_id ? await listTemplates(token).catch(() => []) : [];
        if (controller.signal.aborted) return;
        const template = catalog.find((entry) => entry.id === doc.template_id) ?? null;
        setPage({ state: "ready", opened: { workspace, doc, template }, generation: loads });
      } catch (error) {
        if (controller.signal.aborted) return;
        // 400 is a malformed id in the address bar; to the reader that's the same as missing.
        if (error instanceof ApiError && (error.status === 404 || error.status === 400)) setPage({ state: "missing" });
        else setPage({ state: "error", message: error instanceof ApiError && error.code === "network" ? error.message : "We couldn't open this document." });
      }
    })();
    return () => controller.abort();
  }, [id, idToken, loads]);

  const reload = useCallback(() => setLoads((count) => count + 1), []);

  if (page.state === "loading") return <FullPageLoader label="Opening the document" />;
  if (page.state === "missing" || page.state === "error") {
    return (
      <main id="main" className="mx-auto max-w-xl px-4 py-16 text-center">
        <title>{page.state === "missing" ? "Document not found · SkoLab" : "Document unavailable · SkoLab"}</title>
        <h1 className="text-xl font-semibold">{page.state === "missing" ? "Document not found" : "Document unavailable"}</h1>
        <p className="mt-2 text-sm text-zinc-600 dark:text-zinc-400">
          {page.state === "missing" ? "It may have been deleted, or it isn't shared with you." : page.message}
        </p>
        {page.state === "error" && (
          <div className="mx-auto mt-6 w-32">
            <Button
              onClick={() => {
                setPage({ state: "loading" });
                reload();
              }}
            >
              Try again
            </Button>
          </div>
        )}
        <Link to="/" className="mt-6 inline-block font-semibold text-brand-700 underline underline-offset-4 dark:text-brand-300">
          Back to your documents
        </Link>
      </main>
    );
  }
  return <DocumentEditor key={page.generation} opened={page.opened} onReload={reload} />;
}

type SaveState =
  | { state: "saved" }
  | { state: "pending" }
  | { state: "saving" }
  | { state: "failed"; message: string }
  | { state: "conflict"; currentVersion: number }
  | { state: "locked"; message: string };

function saveLabel(save: SaveState): string {
  switch (save.state) {
    case "saved":
      return "All changes saved";
    case "pending":
    case "saving":
      return "Saving…";
    case "failed":
      return `Not saved: ${save.message}`;
    case "conflict":
      return "Not saved: someone else changed this document";
    case "locked":
      return "Not saved";
  }
}

function DocumentEditor({ opened, onReload }: { opened: Opened; onReload: () => void }) {
  const { workspace, doc, template } = opened;
  const idToken = useIdToken();
  const editable = canEdit(doc.role);
  const isOwner = workspace.role === "owner";
  const [title, setTitle] = useState(workspace.title);
  const [titleSave, setTitleSave] = useState<"saved" | "saving" | "failed">("saved");
  const [source, setSource] = useState(doc.source);
  const [save, setSave] = useState<SaveState>({ state: "saved" });
  const [compile, setCompile] = useState<Compile>({ state: "idle" });
  // The last PDF that compiled; kept on screen through later compiles and errors.
  const [pdf, setPdf] = useState<Uint8Array | null>(null);
  const [pane, setPane] = useState<"source" | "pdf">("source");
  const [zoom, setZoom] = useState(1);
  const editor = useRef<LatexEditorHandle>(null);
  const running = useRef<AbortController | null>(null);
  const navigate = useNavigate();
  const me = useAuth().user?.uid ?? "";
  // What the gateway read from the last saved source, and who saved it when.
  const [insights, setInsights] = useState<Insights>(doc.insights);
  const [lastSaved, setLastSaved] = useState({ at: doc.updated_at, by: doc.updated_by, version: doc.version });
  const [members, setMembers] = useState<Member[]>([]);
  const [sheet, setSheet] = useState<"share" | "overview" | null>(null);

  // People with access, for the avatars and to name who saved last.
  useEffect(() => {
    let current = true;
    void idToken()
      .then((token) => listMembers(token, workspace.id))
      .then(
        (list) => current && setMembers(list),
        () => undefined, // only decoration; the Share panel reports its own errors
      );
    return () => {
      current = false;
    };
  }, [idToken, workspace.id]);

  // What the server holds: the version this copy is based on, and its text
  // (null when the server's text is not ours, after a conflict).
  const version = useRef(doc.version);
  const savedSource = useRef<string | null>(doc.source);
  const latestSource = useRef(source);
  useEffect(() => {
    latestSource.current = source;
  });
  const inFlight = useRef(false);
  // Set once saving must stop: a conflict, or the user lost edit rights.
  const halted = useRef(false);
  const retryTimer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);
  // flush calls itself again (typing during a save, retries) through this ref.
  const flushAgain = useRef<() => void>(() => undefined);

  const flush = useCallback(async (): Promise<void> => {
    clearTimeout(retryTimer.current);
    if (inFlight.current || halted.current) return;
    const text = latestSource.current;
    if (text === savedSource.current) {
      setSave({ state: "saved" });
      return;
    }
    if (characterCount(text) > MAX_DOCUMENT_CHARACTERS) {
      setSave({ state: "failed", message: `documents can be up to ${MAX_DOCUMENT_CHARACTERS.toLocaleString()} characters` });
      return;
    }
    inFlight.current = true;
    setSave({ state: "saving" });
    try {
      const saved = await saveDocument(await idToken(), workspace.id, { source: text, base_version: version.current });
      version.current = saved.version;
      savedSource.current = text;
      inFlight.current = false;
      setInsights(saved.insights);
      setLastSaved({ at: saved.updated_at, by: saved.updated_by, version: saved.version });
      // Typing during the save: save again; otherwise this is the latest.
      if (latestSource.current !== text) flushAgain.current();
      else setSave({ state: "saved" });
    } catch (error) {
      inFlight.current = false;
      if (error instanceof VersionConflictError) {
        halted.current = true;
        setSave({ state: "conflict", currentVersion: error.currentVersion });
      } else if (error instanceof ApiError && (error.status === 403 || error.status === 404)) {
        halted.current = true;
        setSave({
          state: "locked",
          message: error.status === 403 ? "You can no longer edit this document. Download your copy as .tex to keep your changes." : "This document was deleted. Download your copy as .tex to keep your changes.",
        });
      } else {
        const { message, retry } = saveMessage(error);
        setSave({ state: "failed", message });
        if (retry) retryTimer.current = setTimeout(() => flushAgain.current(), RETRY_DELAY_MS);
      }
    }
  }, [idToken, workspace.id]);
  useEffect(() => {
    flushAgain.current = () => void flush();
  }, [flush]);

  // Save a short pause after the last change.
  const firstRender = useRef(true);
  useEffect(() => {
    if (firstRender.current) {
      firstRender.current = false;
      return;
    }
    if (!editable || halted.current) return;
    setSave({ state: "pending" });
    const timer = setTimeout(() => void flush(), SAVE_DELAY_MS);
    return () => clearTimeout(timer);
  }, [source, editable, flush]);

  // Leaving the page: save what's left, and warn before a reload or tab close would lose it.
  useEffect(() => {
    const warn = (event: BeforeUnloadEvent) => {
      if (editable && !halted.current && latestSource.current !== savedSource.current) event.preventDefault();
    };
    window.addEventListener("beforeunload", warn);
    return () => {
      window.removeEventListener("beforeunload", warn);
      clearTimeout(retryTimer.current);
      if (editable) void flush();
    };
  }, [editable, flush]);

  function keepMine(currentVersion: number) {
    version.current = currentVersion;
    savedSource.current = null;
    halted.current = false;
    void flush();
  }

  // Rename (owners only), a pause after the last keystroke.
  const savedTitle = useRef(workspace.title);
  useEffect(() => {
    const wanted = title.trim();
    if (!isOwner || !wanted || wanted === savedTitle.current) return;
    const timer = setTimeout(() => {
      setTitleSave("saving");
      void idToken()
        .then((token) => renameWorkspace(token, workspace.id, wanted))
        .then(
          () => {
            savedTitle.current = wanted;
            setTitleSave("saved");
          },
          () => setTitleSave("failed"),
        );
    }, SAVE_DELAY_MS);
    return () => clearTimeout(timer);
  }, [title, isOwner, idToken, workspace.id]);

  const runCompile = useCallback(
    async (text: string) => {
      if (text.length > MAX_SOURCE_LENGTH) {
        setCompile({ state: "unavailable", message: `Documents can be up to ${MAX_SOURCE_LENGTH.toLocaleString()} characters to compile.` });
        return;
      }
      running.current?.abort();
      const controller = new AbortController();
      running.current = controller;
      setCompile({ state: "compiling" });
      let result: CompileResult;
      try {
        result = await compileLatex(await idToken(), text, controller.signal);
      } catch (error) {
        if (controller.signal.aborted) return;
        setCompile({ state: "unavailable", message: compileMessage(error) });
        return;
      }
      if (controller.signal.aborted) return;
      if (result.status === "compiled" && result.pdf_base64) {
        setPdf(pdfBytes(result.pdf_base64));
        setCompile({ state: "compiled" });
        setPane("pdf");
      } else {
        const errors = result.errors?.length ? result.errors : ["LaTeX compilation failed."];
        setCompile({ state: "failed", errors, log: result.log ?? "" });
      }
    },
    [idToken],
  );

  // Open with a fresh preview, the way Overleaf does.
  const openedSource = doc.source;
  useEffect(() => {
    if (!openedSource.trim()) return;
    const timer = setTimeout(() => void runCompile(openedSource), 0);
    return () => {
      clearTimeout(timer);
      running.current?.abort();
    };
  }, [openedSource, runCompile]);

  const compiling = compile.state === "compiling";
  const titleStatus = titleSave === "failed" ? "Title not saved" : titleSave === "saving" ? "Saving title…" : null;

  return (
    <div className="flex h-dvh flex-col">
      <title>{`${title.trim() || "Untitled"} · SkoLab`}</title>
      <header className="flex flex-wrap items-center gap-x-2 gap-y-2 border-b border-zinc-200 bg-white px-2 py-2 sm:px-3 dark:border-zinc-800 dark:bg-zinc-950">
        <Link
          to="/"
          className="grid size-10 shrink-0 place-items-center rounded-lg text-zinc-600 hover:bg-zinc-100 hover:text-zinc-900 dark:text-zinc-400 dark:hover:bg-zinc-900 dark:hover:text-zinc-100"
          aria-label="Back to your documents"
          title="Your documents"
        >
          <svg viewBox="0 0 24 24" aria-hidden="true" className="size-5" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
            <path d="M15 18l-6-6 6-6" />
          </svg>
        </Link>
        <div className="min-w-32 flex-1">
          {isOwner ? (
            <label className="block">
              <span className="sr-only">Document title</span>
              <input
                value={title}
                onChange={(event) => setTitle(event.target.value)}
                maxLength={255}
                className="w-full min-w-0 truncate rounded-md border border-transparent bg-transparent px-1.5 py-0.5 text-[15px] font-semibold hover:border-zinc-300 focus:border-brand-500 dark:hover:border-zinc-700"
              />
            </label>
          ) : (
            <h1 className="truncate px-1.5 py-0.5 text-[15px] font-semibold">{title}</h1>
          )}
          <p className="flex min-w-0 items-center gap-1.5 px-1.5 text-xs text-zinc-500 dark:text-zinc-400">
            <span role="status" className="truncate">
              {editable ? (titleStatus ?? saveLabel(save)) : "View only"}
            </span>
            <span aria-hidden="true" className="hidden sm:inline">
              ·
            </span>
            <span className="hidden truncate sm:inline">Created {formatDate(workspace.created_at)}</span>
          </p>
        </div>

        <button
          type="button"
          onClick={() => setSheet("overview")}
          aria-label={`Progress ${insights.progress.percent}%. Open the overview`}
          title="Progress, contents and details"
          className="flex h-10 cursor-pointer items-center gap-2 rounded-lg px-2.5 text-sm font-semibold tabular-nums hover:bg-zinc-100 dark:hover:bg-zinc-900"
        >
          <ProgressRing percent={insights.progress.percent} size={22} stroke={3} />
          {insights.progress.percent}%
        </button>

        <button
          type="button"
          onClick={() => setSheet("share")}
          title="Invite co-authors and manage access"
          className="flex h-10 cursor-pointer items-center gap-2 rounded-lg border border-zinc-300 bg-white pr-3 pl-1.5 text-sm font-semibold shadow-sm hover:bg-zinc-50 dark:border-zinc-700 dark:bg-zinc-900 dark:hover:bg-zinc-800"
        >
          {members.length > 0 ? (
            <span className="flex -space-x-2">
              {members.slice(0, 3).map((member) => (
                <Avatar key={member.user_id} id={member.user_id} name={member.display_name || "?"} className="size-7 text-[11px] ring-2 ring-white dark:ring-zinc-900" />
              ))}
            </span>
          ) : (
            <svg viewBox="0 0 24 24" aria-hidden="true" className="ml-1 size-5" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
              <path d="M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2M9 11a4 4 0 1 0 0-8 4 4 0 0 0 0 8zM19 8v6M22 11h-6" />
            </svg>
          )}
          Share
          {members.length > 3 && <span className="text-xs font-medium text-zinc-500">+{members.length - 3}</span>}
        </button>

        <div className="flex items-center gap-2">
          <Button variant="secondary" className="!h-10 !w-auto" onClick={() => download(`${fileName(title)}.tex`, source, "application/x-tex")}>
            .tex
          </Button>
          <Button
            variant="secondary"
            className="!h-10 !w-auto"
            disabled={!pdf}
            onClick={() => pdf && download(`${fileName(title)}.pdf`, pdf.slice().buffer, "application/pdf")}
          >
            PDF
          </Button>
          <Button className="!h-10 !w-auto" loading={compiling} onClick={() => void runCompile(source)} title="Compile (Ctrl+Enter)">
            {compiling ? "Compiling" : "Compile"}
          </Button>
        </div>
      </header>

      {!editable && (
        <div className="border-b border-zinc-200 px-3 py-2 dark:border-zinc-800">
          <Alert tone="info">You can read and compile this document. Ask its owner for edit access to change it.</Alert>
        </div>
      )}
      {save.state === "conflict" && (
        <div className="border-b border-zinc-200 px-3 py-2 dark:border-zinc-800">
          <Alert tone="error">
            <p className="font-semibold">Someone else saved this document while you were editing.</p>
            <p>Your latest changes are not saved. Load their version, or replace it with yours.</p>
            <div className="mt-2 flex flex-wrap gap-2">
              <Button variant="secondary" className="!h-8 !w-auto" onClick={onReload}>
                Load their version
              </Button>
              <Button variant="secondary" className="!h-8 !w-auto" onClick={() => keepMine(save.currentVersion)}>
                Keep mine
              </Button>
            </div>
          </Alert>
        </div>
      )}
      {save.state === "locked" && (
        <div className="border-b border-zinc-200 px-3 py-2 dark:border-zinc-800">
          <Alert tone="error">{save.message}</Alert>
        </div>
      )}

      <div className="flex border-b border-zinc-200 md:hidden dark:border-zinc-800" role="tablist" aria-label="Editor view">
        {(["source", "pdf"] as const).map((name) => (
          <button
            key={name}
            type="button"
            role="tab"
            aria-selected={pane === name}
            onClick={() => setPane(name)}
            className={`flex-1 cursor-pointer py-2.5 text-sm font-semibold ${pane === name ? "border-b-2 border-brand-600 text-brand-700 dark:text-brand-300" : "text-zinc-600 dark:text-zinc-400"}`}
          >
            {name === "source" ? "Source" : "PDF"}
          </button>
        ))}
      </div>

      <div className="grid min-h-0 flex-1 md:grid-cols-2">
        <section aria-label="LaTeX source" className={`latex-editor min-h-0 border-zinc-200 md:block md:border-r dark:border-zinc-800 ${pane === "source" ? "block" : "hidden"}`}>
          <LatexEditor
            ref={editor}
            initialValue={doc.source}
            onChange={setSource}
            onCompile={(text) => void runCompile(text)}
            label="LaTeX source"
            readOnly={!editable}
          />
        </section>

        <section aria-label="PDF preview" className={`min-h-0 flex-col bg-zinc-100 md:flex dark:bg-zinc-900 ${pane === "pdf" ? "flex" : "hidden"}`}>
          <div className="flex items-center justify-between gap-2 border-b border-zinc-200 px-3 py-1.5 text-xs text-zinc-600 dark:border-zinc-800 dark:text-zinc-400">
            <span className="truncate">
              {template ? (
                <>
                  Template: {template.name} ({template.license})
                </>
              ) : (
                "Blank document"
              )}
            </span>
            <div className="flex items-center gap-1">
              <button
                type="button"
                aria-label="Zoom out"
                disabled={zoom === stepZoom(zoom, -1)}
                onClick={() => setZoom(stepZoom(zoom, -1))}
                className="cursor-pointer rounded px-2 py-1 font-semibold hover:bg-zinc-200 disabled:opacity-40 dark:hover:bg-zinc-800"
              >
                −
              </button>
              <span aria-live="polite">{Math.round(zoom * 100)}%</span>
              <button
                type="button"
                aria-label="Zoom in"
                disabled={zoom === stepZoom(zoom, 1)}
                onClick={() => setZoom(stepZoom(zoom, 1))}
                className="cursor-pointer rounded px-2 py-1 font-semibold hover:bg-zinc-200 disabled:opacity-40 dark:hover:bg-zinc-800"
              >
                +
              </button>
            </div>
          </div>

          {/* Scrollable, so keyboard users must be able to reach it (WCAG 2.1.1). */}
          {/* eslint-disable-next-line jsx-a11y/no-noninteractive-tabindex */}
          <div className="min-h-0 flex-1 overflow-auto" role="region" aria-label="PDF pages" tabIndex={0}>
            {compile.state === "failed" && (
              <div className="space-y-2 p-3">
                <Alert tone="error">
                  <p className="font-semibold">Compilation failed{pdf ? "; showing the last good PDF" : ""}.</p>
                  <ul className="mt-1 space-y-1">
                    {compile.errors.map((message, index) => {
                      const line = lineOf(message);
                      return (
                        <li key={index}>
                          {line ? (
                            <button
                              type="button"
                              className="cursor-pointer text-left underline underline-offset-2"
                              onClick={() => {
                                setPane("source");
                                // On phones the source pane is hidden until this render lands.
                                requestAnimationFrame(() => editor.current?.goToLine(line));
                              }}
                            >
                              {message}
                            </button>
                          ) : (
                            message
                          )}
                        </li>
                      );
                    })}
                  </ul>
                </Alert>
                {compile.log && (
                  <details className="rounded-lg border border-zinc-200 bg-white text-xs dark:border-zinc-800 dark:bg-zinc-950">
                    <summary className="cursor-pointer px-3 py-2 font-semibold">Compiler log</summary>
                    <pre className="max-h-72 overflow-auto px-3 pb-3 whitespace-pre-wrap">{compile.log}</pre>
                  </details>
                )}
              </div>
            )}
            {compile.state === "unavailable" && (
              <div className="p-3">
                <Alert tone="error">{compile.message}</Alert>
              </div>
            )}
            {compiling && !pdf && (
              <div role="status" className="flex items-center justify-center gap-2 p-10 text-sm text-zinc-600 dark:text-zinc-300">
                <Spinner /> Compiling with pdfLaTeX…
              </div>
            )}
            {pdf && (
              <Suspense fallback={<Spinner className="mx-auto mt-10 size-6" />}>
                <PdfPreview pdf={pdf} zoom={zoom} />
              </Suspense>
            )}
          </div>
        </section>
      </div>

      {sheet === "overview" && (
        <Sheet title="Overview" description="How complete the manuscript is, what it holds, and its history." onClose={() => setSheet(null)}>
          <Overview
            insights={insights}
            details={{
              createdAt: workspace.created_at,
              updatedAt: lastSaved.at,
              updatedBy: lastSaved.by ? (lastSaved.by === me ? "you" : (members.find((member) => member.user_id === lastSaved.by)?.display_name ?? "a co-author")) : null,
              version: lastSaved.version,
              template: template?.name ?? null,
              role: doc.role,
            }}
          />
        </Sheet>
      )}
      {sheet === "share" && (
        <Sheet title="Share" description={`Invite co-authors to “${title.trim() || "Untitled"}” and choose what they can do.`} onClose={() => setSheet(null)}>
          <Share workspaceId={workspace.id} role={doc.role} me={me} onMembers={setMembers} onLeft={() => void navigate("/", { replace: true })} />
        </Sheet>
      )}
    </div>
  );
}
