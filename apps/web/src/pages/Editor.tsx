import { lazy, Suspense, useCallback, useEffect, useMemo, useRef, useState } from "react";
import { Link, useParams } from "react-router";
import { ApiError } from "../api/client";
import { compileLatex, MAX_SOURCE_LENGTH, pdfBytes, type CompileResult } from "../api/compile";
import { useAuth } from "../auth/AuthProvider";
import { Alert } from "../components/Alert";
import { Button } from "../components/Button";
import { Spinner } from "../components/Spinner";
import { useDocumentStore } from "../editor/useDocumentStore";
import { LatexEditor, type LatexEditorHandle } from "../editor/LatexEditor";
import { templateById } from "../editor/templates";

const PdfPreview = lazy(() => import("../editor/PdfPreview").then((m) => ({ default: m.PdfPreview })));

type Compile =
  | { state: "idle" }
  | { state: "compiling" }
  | { state: "compiled" }
  | { state: "failed"; errors: string[]; log: string }
  | { state: "unavailable"; message: string };

const SAVE_DELAY_MS = 600;
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

export function Editor() {
  const { id = "" } = useParams();
  const { service } = useAuth();
  const store = useDocumentStore();
  const initial = useMemo(() => store.get(id), [store, id]);
  const [title, setTitle] = useState(initial?.title ?? "");
  const [source, setSource] = useState(initial?.source ?? "");
  const [saved, setSaved] = useState<"saved" | "saving" | "failed">("saved");
  const [compile, setCompile] = useState<Compile>({ state: "idle" });
  // The last PDF that compiled; kept on screen through later compiles and errors.
  const [pdf, setPdf] = useState<Uint8Array | null>(null);
  const [pane, setPane] = useState<"source" | "pdf">("source");
  const [zoom, setZoom] = useState(1);
  const editor = useRef<LatexEditorHandle>(null);
  const latest = useRef({ title, source });
  useEffect(() => {
    latest.current = { title, source };
  });
  const running = useRef<AbortController | null>(null);

  const persist = useCallback(() => {
    const { title: currentTitle, source: currentSource } = latest.current;
    try {
      store.save(id, { title: currentTitle.trim() || "Untitled", source: currentSource });
      setSaved("saved");
    } catch {
      setSaved("failed");
    }
  }, [store, id]);

  // Autosave a short pause after the last change, and on leaving the page.
  const firstRender = useRef(true);
  useEffect(() => {
    if (firstRender.current) {
      firstRender.current = false;
      return;
    }
    setSaved("saving");
    const timer = setTimeout(persist, SAVE_DELAY_MS);
    return () => clearTimeout(timer);
  }, [title, source, persist]);

  useEffect(
    () => () => {
      if (store.get(id)) persist();
    },
    [store, id, persist],
  );

  const runCompile = useCallback(async (text: string) => {
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
      const token = await service.getIdToken();
      if (!token) throw new ApiError(401, "unauthenticated", "Signed out");
      result = await compileLatex(token, text, controller.signal);
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
  }, [service]);

  // Open with a fresh preview, the way Overleaf does.
  const openedSource = initial?.source ?? null;
  useEffect(() => {
    if (openedSource === null) return;
    const timer = setTimeout(() => void runCompile(openedSource), 0);
    return () => {
      clearTimeout(timer);
      running.current?.abort();
    };
  }, [openedSource, runCompile]);

  if (!initial) {
    return (
      <main id="main" className="mx-auto max-w-xl px-4 py-16 text-center">
        <title>Document not found · SkoLab</title>
        <h1 className="text-xl font-semibold">Document not found</h1>
        <p className="mt-2 text-sm text-zinc-600 dark:text-zinc-400">It may have been deleted, or it was created in another browser.</p>
        <Link to="/" className="mt-6 inline-block font-semibold text-brand-700 underline underline-offset-4 dark:text-brand-300">
          Back to your documents
        </Link>
      </main>
    );
  }

  const template = initial.templateId ? templateById(initial.templateId) : undefined;
  const compiling = compile.state === "compiling";

  return (
    <div className="flex h-dvh flex-col">
      <title>{`${title || "Untitled"} · SkoLab`}</title>
      <header className="flex flex-wrap items-center gap-x-3 gap-y-2 border-b border-zinc-200 bg-white px-3 py-2 dark:border-zinc-800 dark:bg-zinc-950">
        <Link
          to="/"
          className="rounded-md px-2 py-1.5 text-sm font-semibold text-brand-700 hover:bg-brand-50 dark:text-brand-300 dark:hover:bg-brand-950"
          aria-label="Back to your documents"
        >
          ← Documents
        </Link>
        <label className="min-w-0 flex-1">
          <span className="sr-only">Document title</span>
          <input
            value={title}
            onChange={(event) => setTitle(event.target.value)}
            maxLength={120}
            className="w-full min-w-0 truncate rounded-md border border-transparent bg-transparent px-2 py-1.5 text-sm font-semibold hover:border-zinc-300 focus:border-brand-500 dark:hover:border-zinc-700"
          />
        </label>
        <span className="hidden text-xs text-zinc-600 sm:inline dark:text-zinc-400" role="status">
          {saved === "saving" ? "Saving…" : saved === "failed" ? "Not saved: browser storage is full" : "Saved in this browser"}
        </span>
        <div className="flex items-center gap-2">
          <Button variant="secondary" className="!h-9 !w-auto" onClick={() => download(`${fileName(title)}.tex`, source, "application/x-tex")}>
            .tex
          </Button>
          <Button
            variant="secondary"
            className="!h-9 !w-auto"
            disabled={!pdf}
            onClick={() => pdf && download(`${fileName(title)}.pdf`, pdf.slice().buffer, "application/pdf")}
          >
            PDF
          </Button>
          <Button className="!h-9 !w-auto" loading={compiling} onClick={() => void runCompile(source)} title="Compile (Ctrl+Enter)">
            {compiling ? "Compiling" : "Compile"}
          </Button>
        </div>
      </header>

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
          <LatexEditor ref={editor} initialValue={initial.source} onChange={setSource} onCompile={(text) => void runCompile(text)} label="LaTeX source" />
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
    </div>
  );
}
