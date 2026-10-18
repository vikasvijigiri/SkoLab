import { useEffect, useRef, useState } from "react";
import { Spinner } from "../components/Spinner";
import { PdfViewer } from "./pdfViewer";

type PdfJs = typeof import("pdfjs-dist/legacy/build/pdf.mjs");

let pdfjs: Promise<PdfJs> | null = null;

/**
 * pdf.js is large: load it, and its worker, on first preview only. The
 * "legacy" build, because the modern one needs JavaScript features that
 * browsers released before 2026 lack.
 */
function loadPdfJs(): Promise<PdfJs> {
  pdfjs ??= Promise.all([import("pdfjs-dist/legacy/build/pdf.mjs"), import("pdfjs-dist/legacy/build/pdf.worker.min.mjs?url")]).then(([lib, worker]) => {
    lib.GlobalWorkerOptions.workerSrc = worker.default;
    return lib;
  });
  return pdfjs;
}

interface PdfPreviewProps {
  pdf: Uint8Array;
  /** CSS pixels for 100% zoom; pages fit this width. */
  zoom: number;
}

/**
 * Renders a PDF to canvases with pdf.js, the way Overleaf does, so the
 * preview works the same on phones and under a strict CSP (no plugin, no
 * frames). Zoom is answered at once (see PdfViewer); a new PDF replaces the
 * old one only when the pages in view are drawn, at the same scroll position.
 */
export function PdfPreview({ pdf, zoom }: PdfPreviewProps) {
  const container = useRef<HTMLDivElement>(null);
  const viewer = useRef<PdfViewer | null>(null);
  // Read by the PDF effect without making zoom one of its dependencies.
  const zoomNow = useRef(zoom);
  const [state, setState] = useState<"rendering" | "done" | "failed">("rendering");
  const [pages, setPages] = useState(0);

  useEffect(() => {
    const host = container.current;
    if (!host) return;
    let cancelled = false;
    // A function, so each check reads the current value after an await.
    const stale = () => cancelled;
    let opened: PdfViewer | null = null;
    let destroy: (() => Promise<void>) | null = null;
    // A recompile keeps the reader where they were.
    const previous = viewer.current;
    const scroller = host.closest<HTMLElement>("[data-pdf-scroll]");
    const keep = previous && scroller ? scroller.scrollTop : null;
    if (!previous) setState("rendering");

    (async () => {
      const lib = await loadPdfJs();
      // pdf.js transfers the buffer to its worker: give it a copy.
      const task = lib.getDocument({ data: pdf.slice(), enableXfa: false });
      destroy = () => task.destroy();
      const doc = await task.promise;
      if (stale()) return;
      opened = new PdfViewer(host, doc, () => task.destroy());
      await opened.open(zoomNow.current, keep);
      if (stale()) return;
      viewer.current = opened;
      setPages(opened.numPages);
      setState("done");
    })().catch((error: unknown) => {
      if (stale()) return;
      console.warn("PDF preview failed", error);
      setState("failed");
    });

    return () => {
      cancelled = true;
      // The old pages stay on screen until the next PDF's replace them.
      if (opened) opened.close();
      else void destroy?.();
      if (viewer.current === opened) viewer.current = null;
    };
  }, [pdf]);

  // Instant: page boxes resize now, sharp drawing follows.
  useEffect(() => {
    zoomNow.current = zoom;
    viewer.current?.setZoom(zoom);
  }, [zoom]);

  return (
    <div className="relative">
      {state === "rendering" && (
        <div role="status" className="absolute inset-x-0 top-4 flex items-center justify-center gap-2 text-sm text-zinc-600 dark:text-zinc-300">
          <Spinner /> Rendering preview…
        </div>
      )}
      {state === "failed" && (
        <p role="alert" className="p-6 text-center text-sm text-red-700 dark:text-red-300">
          The PDF compiled, but this browser couldn't display it. Use Download PDF instead.
        </p>
      )}
      <div ref={container} data-testid="pdf-pages" data-pages={pages} className="min-w-fit px-4 py-4" />
    </div>
  );
}
