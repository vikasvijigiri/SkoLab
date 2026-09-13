"use client";

import { useEffect, useRef, useState } from "react";
import { Sigma, Table2, Quote, FileStack, Sparkles, Lock, ArrowLeft } from "lucide-react";
import { useClickOutside } from "@/lib/hooks/useClickOutside";
import { useDebounce } from "@/lib/hooks/useDebounce";
import { openAlexWorks } from "@/lib/api/endpoints";
import { buildApa } from "@/lib/openalex-work";
import { cn } from "@/lib/utils";
import type { OpenAlexWork } from "@/lib/types";

const EQUATION_SNIPPET = "\n$$\n\n$$\n";
const TABLE_SNIPPET = "\n| Column A | Column B |\n| --- | --- |\n| value | value |\n";

/** Author surname (or first author + "et al." for 3+ authors), for a short
 *  inline citation marker — the full APA form is copied to the clipboard so
 *  it can be dropped into the document's References section by hand. */
function shortCiteLabel(work: OpenAlexWork): string {
  const authors = (work.authorships ?? []).map((a) => a.author.display_name.split(/\s+/).pop()).filter(Boolean);
  const year = work.publication_year ?? "n.d.";
  if (authors.length === 0) return `(Unknown, ${year})`;
  if (authors.length === 1) return `(${authors[0]}, ${year})`;
  if (authors.length === 2) return `(${authors[0]} & ${authors[1]}, ${year})`;
  return `(${authors[0]} et al., ${year})`;
}

type MenuView = "root" | "citation";

/**
 * `/` slash-command insert menu (decisions/0019): equation, figure/table,
 * citation search against OpenAlex, section-from-template, and "Ask AI"
 * clearly tagged as a paid tier rather than presented as free.
 *
 * Simplification: anchored under the editor rather than at the exact caret
 * pixel position — a plain `<textarea>` has no native caret-coordinate API,
 * and building a mirror-div measurer was judged out of scope for this pass.
 */
export function SlashMenu({
  onInsertText,
  onOpenTemplates,
  onClose,
}: {
  onInsertText: (text: string) => void;
  onOpenTemplates: () => void;
  onClose: () => void;
}) {
  const ref = useRef<HTMLDivElement>(null);
  // Also closes on Escape — useClickOutside listens for both.
  useClickOutside(ref, onClose, true);

  const [view, setView] = useState<MenuView>("root");
  const [query, setQuery] = useState("");
  const debouncedQuery = useDebounce(query, 300);
  const queryTooShort = debouncedQuery.trim().length < 3;
  const [fetchedResults, setFetchedResults] = useState<OpenAlexWork[]>([]);
  const [searching, setSearching] = useState(false);
  const [insertedNote, setInsertedNote] = useState<string | null>(null);

  // Derived rather than reset via effect — stale fetched results just stay
  // hidden once the query drops below the search threshold (same pattern as
  // CommandPalette.tsx), no extra setState in the effect body below.
  const results = view !== "citation" || queryTooShort ? [] : fetchedResults;

  useEffect(() => {
    if (view !== "citation" || queryTooShort) return;
    let cancelled = false;
    (async () => {
      setSearching(true);
      try {
        const works = await openAlexWorks({ q: debouncedQuery.trim() });
        if (!cancelled) setFetchedResults(works.slice(0, 6));
      } catch {
        if (!cancelled) setFetchedResults([]);
      } finally {
        if (!cancelled) setSearching(false);
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [debouncedQuery, queryTooShort, view]);

  async function chooseCitation(work: OpenAlexWork) {
    onInsertText(shortCiteLabel(work));
    try {
      await navigator.clipboard.writeText(buildApa(work));
      setInsertedNote("Inline citation added · full APA reference copied — paste it into References.");
    } catch {
      setInsertedNote("Inline citation added.");
    }
    setTimeout(onClose, 900);
  }

  return (
    <div
      ref={ref}
      role="menu"
      aria-label="Insert menu"
      className="absolute bottom-full left-4 z-30 mb-1.5 w-72 overflow-hidden rounded-md border border-border bg-surface shadow-elevated"
    >
      {view === "root" ? (
        <div className="py-1">
          <p className="px-3 pb-1 pt-2 font-mono text-[10px] font-semibold uppercase tracking-wide text-text-muted">
            Insert
          </p>
          <button
            type="button"
            role="menuitem"
            onClick={() => {
              onInsertText(EQUATION_SNIPPET);
              onClose();
            }}
            className="flex w-full items-center gap-2.5 px-3 py-2 text-left font-body text-[13px] text-text-secondary transition-colors hover:bg-surface-subtle hover:text-text-primary"
          >
            <Sigma size={14} className="shrink-0 text-text-muted" />
            Equation
          </button>
          <button
            type="button"
            role="menuitem"
            onClick={() => {
              onInsertText(TABLE_SNIPPET);
              onClose();
            }}
            className="flex w-full items-center gap-2.5 px-3 py-2 text-left font-body text-[13px] text-text-secondary transition-colors hover:bg-surface-subtle hover:text-text-primary"
          >
            <Table2 size={14} className="shrink-0 text-text-muted" />
            Figure / table
          </button>
          <button
            type="button"
            role="menuitem"
            onClick={() => setView("citation")}
            className="flex w-full items-center gap-2.5 px-3 py-2 text-left font-body text-[13px] text-text-secondary transition-colors hover:bg-surface-subtle hover:text-text-primary"
          >
            <Quote size={14} className="shrink-0 text-text-muted" />
            Citation search
            <span className="ml-auto font-mono text-[9.5px] uppercase tracking-wide text-text-muted">OpenAlex</span>
          </button>
          <button
            type="button"
            role="menuitem"
            onClick={() => {
              onOpenTemplates();
              onClose();
            }}
            className="flex w-full items-center gap-2.5 px-3 py-2 text-left font-body text-[13px] text-text-secondary transition-colors hover:bg-surface-subtle hover:text-text-primary"
          >
            <FileStack size={14} className="shrink-0 text-text-muted" />
            Section from template
          </button>
          <div className="mx-3 my-1 h-px bg-border" />
          <button
            type="button"
            role="menuitem"
            onClick={() => setInsertedNote("Ask AI is a SkoLab Pro feature — upgrade to draft with AI assistance.")}
            className="flex w-full items-center gap-2.5 px-3 py-2 text-left font-body text-[13px] text-text-muted transition-colors hover:bg-surface-subtle"
          >
            <Sparkles size={14} className="shrink-0 text-accent-violet" />
            Ask AI
            <span className="ml-auto inline-flex items-center gap-1 rounded-full bg-accent-violet/15 px-1.5 py-0.5 font-mono text-[9px] font-bold text-accent-violet">
              <Lock size={9} /> PRO
            </span>
          </button>
          {insertedNote && (
            <p className="px-3 pb-2 pt-1 font-body text-[11px] text-text-secondary">{insertedNote}</p>
          )}
        </div>
      ) : (
        <div className="flex flex-col">
          <div className="flex items-center gap-1.5 border-b border-border px-2 py-1.5">
            <button
              type="button"
              onClick={() => setView("root")}
              aria-label="Back"
              className="flex h-6 w-6 shrink-0 items-center justify-center rounded text-text-muted hover:text-text-primary"
            >
              <ArrowLeft size={13} />
            </button>
            <input
              autoFocus
              value={query}
              onChange={(e) => setQuery(e.target.value)}
              placeholder="Search OpenAlex by title…"
              className="min-w-0 flex-1 bg-transparent font-body text-[12.5px] text-text-primary outline-none placeholder:text-text-muted"
            />
          </div>
          <div className="max-h-64 overflow-y-auto">
            {query.trim().length < 3 && (
              <p className="px-3 py-3 font-body text-[11.5px] text-text-muted">Type at least 3 characters.</p>
            )}
            {searching && <p className="px-3 py-3 font-body text-[11.5px] text-text-muted">Searching…</p>}
            {!searching &&
              results.map((work) => (
                <button
                  key={work.id}
                  type="button"
                  onClick={() => chooseCitation(work)}
                  className="flex w-full flex-col items-start gap-0.5 border-b border-border px-3 py-2 text-left transition-colors last:border-b-0 hover:bg-surface-subtle"
                >
                  <span className="line-clamp-2 font-body text-[12.5px] font-medium text-text-primary">
                    {work.display_name}
                  </span>
                  <span className="font-body text-[11px] text-text-muted">
                    {(work.authorships ?? []).map((a) => a.author.display_name).slice(0, 2).join(", ") || "Unknown authors"}
                    {work.publication_year ? ` · ${work.publication_year}` : ""}
                  </span>
                </button>
              ))}
            {!searching && query.trim().length >= 3 && results.length === 0 && (
              <p className="px-3 py-3 font-body text-[11.5px] text-text-muted">No matches.</p>
            )}
          </div>
          {insertedNote && (
            <p className={cn("px-3 py-2 font-body text-[11px] text-accent-emerald")}>{insertedNote}</p>
          )}
        </div>
      )}
    </div>
  );
}
