import { useEffect, useRef, useState } from "react";
import type { CursorInfo } from "./LatexEditor";

type Katex = typeof import("katex").default;

let loading: Promise<Katex> | null = null;

/** KaTeX, its styles and fonts, and mhchem for \ce: loaded the first time the cursor enters math. */
function loadKatex(): Promise<Katex> {
  loading ??= Promise.all([import("katex"), import("katex/dist/katex.min.css")]).then(async ([module]) => {
    // mhchem registers \ce and \pu on the KaTeX instance it imports.
    await import("katex/contrib/mhchem");
    return module.default;
  });
  return loading;
}

const WIDTH = 420;

/**
 * A rendered preview of the math under the cursor, floating just below it,
 * so equations can be checked without compiling. The document's own simple
 * macros (\newcommand, \DeclareMathOperator) are applied.
 */
export function MathPreview({ cursor, macros }: { cursor: CursorInfo | null; macros: Record<string, string> }) {
  const [katex, setKatex] = useState<Katex | null>(null);
  const target = useRef<HTMLDivElement>(null);
  const math = cursor?.focused ? cursor.math : null;
  const tex = math?.tex.trim() ?? "";

  useEffect(() => {
    if (!tex || katex) return;
    let current = true;
    loadKatex().then(
      (loaded) => current && setKatex(() => loaded),
      () => undefined, // no preview; compiling still shows the math
    );
    return () => {
      current = false;
    };
  }, [tex, katex]);

  useEffect(() => {
    const box = target.current;
    if (!katex || !box || !math || !tex) return;
    try {
      // Renders through DOM APIs, which the page's style policy allows.
      katex.render(tex, box, { displayMode: math.display, throwOnError: true, strict: "ignore", trust: false, maxSize: 20, maxExpand: 500, macros: { ...macros } });
      box.dataset.failed = "false";
    } catch (error) {
      const reason = document.createElement("p");
      reason.className = "text-xs text-red-700 dark:text-red-300";
      reason.textContent = error instanceof Error ? error.message.replace(/^KaTeX parse error:\s*/, "") : "This math can't be previewed.";
      box.replaceChildren(reason);
      box.dataset.failed = "true";
    }
  }, [katex, math, tex, macros]);

  if (!math || !tex || !cursor?.anchor) return null;
  const { anchor } = cursor;
  const left = Math.max(8, Math.min(anchor.left, window.innerWidth - WIDTH - 8));
  const below = anchor.bottom + 8;
  const placeAbove = below > window.innerHeight - 160;
  return (
    <div
      role="note"
      aria-label="Math preview"
      className="pointer-events-none fixed z-30 max-w-[420px] animate-fade rounded-xl border border-zinc-200 bg-white/95 px-4 py-3 text-zinc-900 shadow-xl backdrop-blur dark:border-zinc-700 dark:bg-zinc-900/95 dark:text-zinc-100"
      // Follows the cursor; the position is only known at runtime.
      style={placeAbove ? { left, bottom: window.innerHeight - anchor.top + 8 } : { left, top: below }}
    >
      <div className="mb-1 text-[10px] font-semibold tracking-wide text-zinc-500 uppercase dark:text-zinc-400">Preview</div>
      {katex ? <div ref={target} data-testid="math-preview" className="max-h-48 overflow-hidden text-[15px]" /> : <p className="text-xs text-zinc-500">Loading preview…</p>}
    </div>
  );
}
