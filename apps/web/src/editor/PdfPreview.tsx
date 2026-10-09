import { useEffect, useRef, useState } from "react";
import { Spinner } from "../components/Spinner";

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
 * Renders every page of a PDF to canvases (pdf.js), the way Overleaf does, so
 * the preview works the same on phones and under a strict CSP (no plugin, no
 * frames).
 */
export function PdfPreview({ pdf, zoom }: PdfPreviewProps) {
  const container = useRef<HTMLDivElement>(null);
  const [state, setState] = useState<"rendering" | "done" | "failed">("rendering");
  const [pages, setPages] = useState(0);

  useEffect(() => {
    const host = container.current;
    if (!host) return;
    let cancelled = false;
    // A function, so each check reads the current value after an await.
    const stale = () => cancelled;
    let destroy: (() => Promise<void>) | null = null;
    setState("rendering");

    (async () => {
      const lib = await loadPdfJs();
      // pdf.js transfers the buffer to its worker: give it a copy.
      const task = lib.getDocument({ data: pdf.slice(), enableXfa: false });
      destroy = () => task.destroy();
      const doc = await task.promise;
      if (stale()) return;
      const width = host.clientWidth || 600;
      const ratio = window.devicePixelRatio || 1;
      const canvases: HTMLCanvasElement[] = [];
      for (let number = 1; number <= doc.numPages; number += 1) {
        const page = await doc.getPage(number);
        if (stale()) return;
        const base = page.getViewport({ scale: 1 });
        const scale = ((width - 32) / base.width) * zoom;
        const viewport = page.getViewport({ scale: scale * ratio });
        const canvas = document.createElement("canvas");
        canvas.width = Math.floor(viewport.width);
        canvas.height = Math.floor(viewport.height);
        canvas.style.width = `${Math.floor(viewport.width / ratio)}px`;
        canvas.style.height = `${Math.floor(viewport.height / ratio)}px`;
        canvas.className = "mx-auto mb-4 block bg-white shadow-md ring-1 ring-zinc-900/10";
        canvas.setAttribute("role", "img");
        canvas.setAttribute("aria-label", `Page ${number} of ${doc.numPages}`);
        await page.render({ canvas, viewport }).promise;
        canvases.push(canvas);
      }
      if (stale()) return;
      host.replaceChildren(...canvases);
      setPages(doc.numPages);
      setState("done");
    })().catch((error: unknown) => {
      if (stale()) return;
      console.warn("PDF preview failed", error);
      setState("failed");
    });

    return () => {
      cancelled = true;
      void destroy?.();
    };
  }, [pdf, zoom]);

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
      <div ref={container} data-testid="pdf-pages" data-pages={pages} className="px-4 py-4" />
    </div>
  );
}
