import { useCallback, useEffect, useRef, useState, type ReactNode } from "react";
import { aiStatus, improve, originalityReport, originalityReports, startOriginality, verdict, type AIStatus, type ImproveGoal, type OriginalityReport, type Suggestion } from "../api/ai";
import { ApiError } from "../api/client";
import { Alert } from "../components/Alert";
import { CheckIcon, ExternalIcon, SearchIcon } from "../components/Icons";
import { Spinner } from "../components/Spinner";
import { formatDateTime, timeAgo } from "../lib/dates";
import type { LatexEditorHandle } from "./LatexEditor";

const MAX_PASSAGE = 4000;
const POLL_MS = 2500;

export const GOALS: { goal: ImproveGoal; title: string; detail: string }[] = [
  { goal: "clarity", title: "Clarity", detail: "Clearer sentences, same claims" },
  { goal: "concise", title: "Concise", detail: "Cut wordiness, keep every fact" },
  { goal: "academic", title: "Academic tone", detail: "Formal journal register" },
  { goal: "grammar", title: "Grammar", detail: "Fix errors, change nothing else" },
  { goal: "flow", title: "Flow", detail: "Smoother transitions" },
];

const CATEGORY_LABELS: Record<Suggestion["category"], string> = {
  clarity: "Clarity",
  concision: "Concision",
  tone: "Tone",
  grammar: "Grammar",
  flow: "Flow",
  word_choice: "Word choice",
};

/** Why an AI request was refused, in words. */
export function aiMessage(error: unknown): string {
  if (error instanceof ApiError) {
    switch (error.code) {
      case "ai_daily_limit":
        return "You've used today's allowance for this feature. It resets at midnight UTC.";
      case "ai_capacity":
        return "SkoLab has used today's AI capacity. It resets at midnight UTC.";
      case "ai_unconfigured":
        return "This AI feature isn't switched on yet.";
      case "ai_busy":
        return "The writing assistant is busy right now. Try again in a moment.";
      case "nothing_to_improve":
        return "There's no prose to improve here: math, citations and commands are left as they are. Select a sentence or paragraph.";
      case "passage_too_long":
        return "Select at most 4,000 characters at a time.";
      case "edit_forbidden":
        return "Only the owner and editors can use AI on this document.";
      case "network":
        return error.message;
    }
    if (error.status === 401) return "Your session expired. Sign in again.";
  }
  if (error instanceof DOMException && error.name === "TimeoutError") return "The writing assistant took too long. Try again.";
  return "The AI service is unavailable right now. Try again shortly.";
}

type Tab = "improve" | "originality";

/**
 * The editor's AI panel: suggestions for a passage of main.tex, applied one
 * by one onto the editor, and the document's originality check.
 */
export function AIPanel({
  workspaceId,
  editable,
  token,
  editor,
  initialTab = "improve",
  initialGoal,
  onShowInDocument,
}: {
  workspaceId: string;
  editable: boolean;
  token: () => Promise<string>;
  editor: () => LatexEditorHandle | null;
  initialTab?: Tab;
  /** Run this goal on the selection as soon as the panel opens (the selection toolbar). */
  initialGoal?: ImproveGoal | undefined;
  /** After a sentence is selected in the editor, to make it visible. */
  onShowInDocument?: () => void;
}) {
  const [tab, setTab] = useState<Tab>(initialTab);
  const [status, setStatus] = useState<AIStatus | null>(null);

  const refreshStatus = useCallback(async () => {
    try {
      setStatus(await aiStatus(await token()));
    } catch {
      // The panels say what failed when used; the counts are a nicety.
    }
  }, [token]);

  const used = useCallback(() => void refreshStatus(), [refreshStatus]);

  useEffect(() => {
    const controller = new AbortController();
    void (async () => {
      try {
        const next = await aiStatus(await token(), controller.signal);
        if (!controller.signal.aborted) setStatus(next);
      } catch {
        // As above.
      }
    })();
    return () => controller.abort();
  }, [token]);

  return (
    <div className="flex h-full flex-col">
      <div role="tablist" aria-label="AI tools" className="grid grid-cols-2 gap-1 rounded-xl bg-zinc-100 p-1 dark:bg-zinc-800/80">
        {(
          [
            ["improve", "Improve writing"],
            ["originality", "Originality"],
          ] as const
        ).map(([value, label]) => (
          <button
            key={value}
            type="button"
            role="tab"
            aria-selected={tab === value}
            onClick={() => setTab(value)}
            className={`h-9 cursor-pointer rounded-lg text-sm font-semibold transition-colors ${
              tab === value ? "bg-white text-zinc-900 shadow-sm dark:bg-zinc-900 dark:text-white" : "text-zinc-600 hover:text-zinc-900 dark:text-zinc-400 dark:hover:text-white"
            }`}
          >
            {label}
          </button>
        ))}
      </div>
      <div className="mt-4 min-h-0 flex-1">
        {tab === "improve" ? (
          <ImproveTab workspaceId={workspaceId} editable={editable} token={token} editor={editor} left={status?.improve} onUsed={used} initialGoal={initialGoal} />
        ) : (
          <OriginalityTab workspaceId={workspaceId} editable={editable} token={token} editor={editor} left={status?.originality} onUsed={used} {...(onShowInDocument ? { onShown: onShowInDocument } : {})} />
        )}
      </div>
    </div>
  );
}

function Allowance({ feature, noun }: { feature: AIStatus["improve"] | undefined; noun: string }) {
  if (!feature) return null;
  if (!feature.available) return <span className="text-xs text-zinc-500 dark:text-zinc-400">Not available yet</span>;
  return (
    <span className="rounded-full bg-zinc-100 px-2 py-0.5 text-xs font-medium text-zinc-600 tabular-nums dark:bg-zinc-800 dark:text-zinc-300">
      {feature.left_today} {noun}
      {feature.left_today === 1 ? "" : "s"} left today
    </span>
  );
}

// ── Improve ────────────────────────────────────────────────────────────────

type Passage = { from: number; to: number; text: string; trimmed: boolean };
type Decision = "open" | "applied" | "dismissed" | "stale";
/** A suggestion and the passage it was made for. */
type Found = Suggestion & { key: string; part: number };
type Scope = { kind: "passage"; passages: Passage[] } | { kind: "document"; passages: Passage[]; total: number } | { kind: "none" };

const BATCH = 3500;
const MAX_BATCHES = 8;

function capture(handle: LatexEditorHandle | null): Passage | null {
  const passage = handle?.passage();
  if (!passage || !passage.text.trim()) return null;
  if (passage.text.length <= MAX_PASSAGE) return { ...passage, trimmed: false };
  // Too long: keep whole sentences up to the limit.
  const head = passage.text.slice(0, MAX_PASSAGE);
  const cut = Math.max(head.lastIndexOf(". "), head.lastIndexOf(".\n"));
  const text = cut > 200 ? head.slice(0, cut + 1) : head;
  return { from: passage.from, to: passage.from + text.length, text, trimmed: true };
}

const STRUCTURE_LINE = /^\s*(?:%|\\(?:begin|end|(?:sub)*section|chapter|paragraph|part|title|author|date|maketitle|documentclass|usepackage|label|caption|item|bibliography|bibliographystyle|input|include|newcommand|renewcommand)\b)/;

/**
 * The document body's prose, as passages of whole paragraphs of at most
 * BATCH characters each (math, citations and commands inside them are
 * protected by the server).
 */
export function documentPassages(text: string): Passage[] {
  const begin = text.indexOf("\\begin{document}");
  const end = text.lastIndexOf("\\end{document}");
  const bodyFrom = begin >= 0 ? begin + "\\begin{document}".length : 0;
  const bodyTo = end > bodyFrom ? end : text.length;
  const paragraphs: { from: number; to: number }[] = [];
  let at = bodyFrom;
  let start = -1;
  let last = -1;
  while (at < bodyTo) {
    const lineEnd = text.indexOf("\n", at);
    const to = lineEnd < 0 || lineEnd > bodyTo ? bodyTo : lineEnd;
    const line = text.slice(at, to);
    const prose = line.trim() !== "" && !STRUCTURE_LINE.test(line);
    if (prose) {
      if (start < 0) start = at;
      last = to;
    } else if (start >= 0) {
      paragraphs.push({ from: start, to: last });
      start = -1;
    }
    at = to + 1;
  }
  if (start >= 0) paragraphs.push({ from: start, to: last });
  const passages: Passage[] = [];
  for (const p of paragraphs) {
    const words = (text.slice(p.from, p.to).match(/\p{L}{2,}/gu) ?? []).length;
    if (words < 6) continue;
    const current = passages.at(-1);
    if (current && p.to - current.from <= BATCH) {
      current.to = p.to;
      current.text = text.slice(current.from, p.to);
    } else if (p.to - p.from <= BATCH) {
      passages.push({ from: p.from, to: p.to, text: text.slice(p.from, p.to), trimmed: false });
    } else {
      const head = text.slice(p.from, p.from + BATCH);
      const cut = Math.max(head.lastIndexOf(". "), head.lastIndexOf(".\n"));
      const take = cut > 200 ? cut + 1 : BATCH;
      passages.push({ from: p.from, to: p.from + take, text: text.slice(p.from, p.from + take), trimmed: true });
    }
  }
  return passages;
}

function ImproveTab({
  workspaceId,
  editable,
  token,
  editor,
  left,
  onUsed,
  initialGoal,
}: {
  workspaceId: string;
  editable: boolean;
  token: () => Promise<string>;
  editor: () => LatexEditorHandle | null;
  left: AIStatus["improve"] | undefined;
  onUsed: () => void;
  initialGoal?: ImproveGoal | undefined;
}) {
  const [scope, setScope] = useState<Scope>(() => {
    const passage = capture(editor());
    return passage ? { kind: "passage", passages: [passage] } : { kind: "none" };
  });
  const [goal, setGoal] = useState<ImproveGoal | null>(null);
  const [state, setState] = useState<
    { kind: "idle" } | { kind: "loading"; done: number; of: number } | { kind: "error"; message: string } | { kind: "done"; suggestions: Found[]; model: string; failed: number; message?: string }
  >({ kind: "idle" });
  const [decisions, setDecisions] = useState<Record<string, Decision>>({});
  // Characters each applied suggestion added (or removed), by its document position.
  const shifts = useRef<{ at: number; delta: number }[]>([]);
  const running = useRef<AbortController | null>(null);
  const started = useRef(false);

  useEffect(() => () => running.current?.abort(), []);

  const run = useCallback(
    async (next: ImproveGoal, passages: Passage[]) => {
      if (passages.length === 0) return;
      running.current?.abort();
      const controller = new AbortController();
      running.current = controller;
      setGoal(next);
      setDecisions({});
      shifts.current = [];
      const found: Found[] = [];
      let model = "";
      let failed = 0;
      let lastError: unknown = null;
      for (const [part, passage] of passages.entries()) {
        setState({ kind: "loading", done: part, of: passages.length });
        try {
          const result = await improve(await token(), workspaceId, passage.text, next, controller.signal);
          if (controller.signal.aborted) return;
          model = result.model;
          found.push(...result.suggestions.map((s) => ({ ...s, key: `${part}:${s.id}`, part })));
        } catch (error) {
          if (controller.signal.aborted) return;
          failed += 1;
          lastError = error;
          // Out of allowance or capacity: the rest would fail the same way.
          if (error instanceof ApiError && error.status === 429) break;
        }
      }
      onUsed();
      if (failed === passages.length) {
        setState({ kind: "error", message: aiMessage(lastError) });
        return;
      }
      setState({ kind: "done", suggestions: found, model, failed, ...(failed ? { message: aiMessage(lastError) } : {}) });
    },
    [token, workspaceId, onUsed],
  );

  // Opened from the selection toolbar with a goal: run it straight away.
  useEffect(() => {
    if (started.current || !initialGoal || scope.kind !== "passage") return;
    started.current = true;
    void run(initialGoal, scope.passages);
  }, [initialGoal, scope, run]);

  function apply(s: Found): boolean {
    const handle = editor();
    const passage = scope.kind === "none" ? undefined : scope.passages[s.part];
    if (!passage || !handle) return false;
    const origin = passage.from + s.start;
    const shift = shifts.current.filter((applied) => applied.at < origin).reduce((sum, applied) => sum + applied.delta, 0);
    let from = origin + shift;
    let ok = handle.replace(from, from + s.original.length, s.original, s.replacement);
    if (!ok) {
      // Edited since: find it again near where it was, by its surroundings
      // (up to 20 characters either side, as sent), else by itself if unique.
      const text = handle.text();
      const windowFrom = Math.max(0, passage.from - 500);
      const area = text.slice(windowFrom, Math.min(text.length, passage.to + 2000));
      const unique = (needle: string) => {
        const first = area.indexOf(needle);
        return first >= 0 && area.indexOf(needle, first + 1) < 0 ? first : -1;
      };
      const before = s.start - Math.max(0, s.start - 20);
      const context = passage.text.slice(s.start - before, Math.min(passage.text.length, s.end + 20));
      const withContext = unique(context);
      const alone = withContext < 0 ? unique(s.original) : -1;
      const found = withContext >= 0 ? withContext + before : alone;
      if (found >= 0) {
        from = windowFrom + found;
        ok = handle.replace(from, from + s.original.length, s.original, s.replacement);
      }
    }
    if (ok) shifts.current.push({ at: origin, delta: s.replacement.length - s.original.length });
    setDecisions((current) => ({ ...current, [s.key]: ok ? "applied" : "stale" }));
    return ok;
  }

  function applyAll(list: Found[]) {
    for (const s of list) if ((decisions[s.key] ?? "open") === "open") apply(s);
  }

  function useSelection() {
    const passage = capture(editor());
    setScope(passage ? { kind: "passage", passages: [passage] } : { kind: "none" });
    setState({ kind: "idle" });
    setGoal(null);
  }

  function useDocument() {
    const all = documentPassages(editor()?.text() ?? "");
    setScope(all.length ? { kind: "document", passages: all.slice(0, MAX_BATCHES), total: all.length } : { kind: "none" });
    setState({ kind: "idle" });
    setGoal(null);
  }

  if (!editable) return <Alert tone="info">Only the owner and editors can use the writing assistant on this document.</Alert>;

  const passages = scope.kind === "none" ? [] : scope.passages;
  const characters = passages.reduce((sum, p) => sum + p.text.length, 0);
  const uses = passages.length;
  const leftToday = left?.left_today ?? 0;
  const tooMany = left !== undefined && left.available && uses > leftToday;

  return (
    <div className="flex h-full flex-col gap-4">
      <section aria-label="Passage" className="rounded-xl border border-zinc-200 bg-zinc-50 p-3 dark:border-zinc-800 dark:bg-zinc-900/60">
        <div className="flex flex-wrap items-center justify-between gap-2">
          <div role="group" aria-label="What to improve" className="flex gap-1 rounded-lg bg-white p-0.5 ring-1 ring-zinc-200 dark:bg-zinc-900 dark:ring-zinc-700">
            <button
              type="button"
              aria-pressed={scope.kind !== "document"}
              onClick={useSelection}
              className={`cursor-pointer rounded-md px-2.5 py-1 text-xs font-semibold ${scope.kind !== "document" ? "bg-brand-600 text-white" : "text-zinc-600 hover:text-zinc-900 dark:text-zinc-300"}`}
            >
              Use current selection
            </button>
            <button
              type="button"
              aria-pressed={scope.kind === "document"}
              onClick={useDocument}
              className={`cursor-pointer rounded-md px-2.5 py-1 text-xs font-semibold ${scope.kind === "document" ? "bg-brand-600 text-white" : "text-zinc-600 hover:text-zinc-900 dark:text-zinc-300"}`}
            >
              Whole document
            </button>
          </div>
        </div>
        {scope.kind === "passage" && passages[0] && (
          <>
            <p className="mt-2 line-clamp-4 font-mono text-xs leading-relaxed whitespace-pre-wrap text-zinc-700 dark:text-zinc-300">{passages[0].text}</p>
            <p className="mt-1.5 text-xs text-zinc-500 dark:text-zinc-400">
              {characters.toLocaleString()} characters{passages[0].trimmed ? " (trimmed to whole sentences within 4,000)" : ""}. Math, citations and commands are protected.
            </p>
          </>
        )}
        {scope.kind === "document" && (
          <p className="mt-2 text-sm text-zinc-700 dark:text-zinc-300">
            {scope.total > passages.length
              ? `The first ${passages.length} of ${scope.total} parts of main.tex (${characters.toLocaleString()} characters); run again from a later paragraph for the rest.`
              : `All of main.tex's prose, in ${passages.length} part${passages.length === 1 ? "" : "s"} (${characters.toLocaleString()} characters).`}{" "}
            Uses {uses} of today's allowance. Math, citations and commands are protected.
          </p>
        )}
        {scope.kind === "none" && (
          <p className="mt-2 text-sm text-zinc-600 dark:text-zinc-400">Select text in main.tex with the mouse, or click inside a paragraph, then choose “Use current selection”. Or improve the whole document.</p>
        )}
      </section>

      <section aria-label="Goal">
        <div className="flex items-center justify-between gap-2">
          <p className="text-xs font-semibold tracking-wider text-zinc-500 uppercase dark:text-zinc-400">Improve for</p>
          <Allowance feature={left} noun="use" />
        </div>
        {tooMany && (
          <p className="mt-1 text-xs text-amber-700 dark:text-amber-400">
            {leftToday === 0
              ? "You've used today's allowance. It resets at midnight UTC."
              : `That needs ${uses} uses; you have ${leftToday} left today. Choose a selection instead.`}
          </p>
        )}
        <div className="mt-2 grid grid-cols-2 gap-2 sm:grid-cols-3">
          {GOALS.map((item) => (
            <button
              key={item.goal}
              type="button"
              disabled={passages.length === 0 || state.kind === "loading" || left?.available === false || tooMany}
              aria-pressed={goal === item.goal}
              onClick={() => void run(item.goal, passages)}
              className={`cursor-pointer rounded-xl border px-3 py-2 text-left transition disabled:cursor-not-allowed disabled:opacity-50 ${
                goal === item.goal ? "border-brand-500 bg-brand-50 ring-1 ring-brand-500 dark:bg-brand-950" : "border-zinc-200 bg-white hover:border-brand-300 hover:bg-brand-50/40 dark:border-zinc-700 dark:bg-zinc-900 dark:hover:bg-zinc-800"
              }`}
            >
              <span className="block text-sm font-semibold">{item.title}</span>
              <span className="block text-xs text-zinc-500 dark:text-zinc-400">{item.detail}</span>
            </button>
          ))}
        </div>
      </section>

      <section aria-label="Suggestions" aria-live="polite" className="min-h-0 flex-1 overflow-y-auto">
        {state.kind === "loading" && (
          <div role="status" className="space-y-2">
            <p className="flex items-center gap-2 text-sm text-zinc-600 dark:text-zinc-400">
              <Spinner /> {state.of > 1 ? `Reading part ${state.done + 1} of ${state.of}…` : "Reading the passage…"}
            </p>
            {state.of > 1 && (
              <div className="h-1.5 overflow-hidden rounded-full bg-zinc-100 dark:bg-zinc-800">
                <div className="h-full rounded-full bg-brand-500 transition-all" style={{ width: `${Math.round((100 * state.done) / state.of)}%` }} />
              </div>
            )}
            {[0, 1, 2].map((n) => (
              <div key={n} className="h-16 animate-pulse rounded-xl bg-zinc-100 dark:bg-zinc-800" />
            ))}
          </div>
        )}
        {state.kind === "error" && <Alert tone="error">{state.message}</Alert>}
        {state.kind === "done" && state.failed > 0 && state.message && (
          <div className="mb-2.5">
            <Alert tone="info">
              {state.failed} part{state.failed === 1 ? "" : "s"} could not be improved: {state.message}
            </Alert>
          </div>
        )}
        {state.kind === "done" && state.suggestions.length === 0 && (
          <div className="rounded-xl border border-emerald-200 bg-emerald-50 p-4 text-sm text-emerald-900 dark:border-emerald-900 dark:bg-emerald-950/50 dark:text-emerald-100">
            <p className="flex items-center gap-2 font-semibold">
              <CheckIcon className="size-4" /> Nothing to change
            </p>
            <p className="mt-1">This already reads well for that goal.</p>
          </div>
        )}
        {state.kind === "done" && state.suggestions.length > 0 && (
          <div className="space-y-2.5">
            <div className="flex items-center justify-between gap-2">
              <p className="text-sm font-semibold">
                {state.suggestions.length} suggestion{state.suggestions.length === 1 ? "" : "s"}
              </p>
              <button
                type="button"
                onClick={() => applyAll(state.suggestions)}
                disabled={state.suggestions.every((s) => (decisions[s.key] ?? "open") !== "open")}
                className="cursor-pointer rounded-lg bg-brand-600 px-3 py-1.5 text-xs font-semibold text-white hover:bg-brand-700 disabled:cursor-not-allowed disabled:opacity-50"
              >
                Accept all
              </button>
            </div>
            <ul className="space-y-2.5">
              {state.suggestions.map((s) => (
                <SuggestionCard key={s.key} suggestion={s} decision={decisions[s.key] ?? "open"} onAccept={() => apply(s)} onDismiss={() => setDecisions((current) => ({ ...current, [s.key]: "dismissed" }))} />
              ))}
            </ul>
            <p className="pt-1 text-[11px] text-zinc-500 dark:text-zinc-400">Every suggestion was checked to keep your math, citations and commands unchanged. Model: {state.model}.</p>
          </div>
        )}
      </section>
    </div>
  );
}

function SuggestionCard({ suggestion, decision, onAccept, onDismiss }: { suggestion: Suggestion; decision: Decision; onAccept: () => void; onDismiss: () => void }) {
  const settled = decision !== "open";
  return (
    <li className={`rounded-xl border p-3 transition ${settled ? "border-zinc-200 bg-zinc-50 opacity-70 dark:border-zinc-800 dark:bg-zinc-900/40" : "border-zinc-200 bg-white shadow-xs dark:border-zinc-700 dark:bg-zinc-900"}`}>
      <div className="flex items-center justify-between gap-2">
        <span className="rounded-md bg-brand-50 px-1.5 py-0.5 text-[11px] font-semibold text-brand-700 dark:bg-brand-950 dark:text-brand-300">{CATEGORY_LABELS[suggestion.category]}</span>
        {decision === "applied" && <span className="flex items-center gap-1 text-xs font-semibold text-emerald-700 dark:text-emerald-400"><CheckIcon className="size-3.5" /> Applied</span>}
        {decision === "dismissed" && <span className="text-xs text-zinc-500">Dismissed</span>}
        {decision === "stale" && <span className="text-xs font-medium text-amber-700 dark:text-amber-400">Text changed; not applied</span>}
      </div>
      <p className="mt-2 text-sm leading-relaxed">
        <del className="rounded bg-red-50 px-0.5 text-red-800 decoration-red-400 dark:bg-red-950/60 dark:text-red-200">{suggestion.original}</del>
      </p>
      <p className="mt-1 text-sm leading-relaxed">
        <ins className="rounded bg-emerald-50 px-0.5 text-emerald-900 no-underline dark:bg-emerald-950/60 dark:text-emerald-100">{suggestion.replacement || "(remove)"}</ins>
      </p>
      {suggestion.reason && <p className="mt-1.5 text-xs text-zinc-500 dark:text-zinc-400">{suggestion.reason}</p>}
      {!settled && (
        <div className="mt-2.5 flex gap-2">
          <button type="button" onClick={onAccept} className="cursor-pointer rounded-lg bg-zinc-900 px-3 py-1.5 text-xs font-semibold text-white hover:bg-zinc-700 dark:bg-white dark:text-zinc-900 dark:hover:bg-zinc-200">
            Accept
          </button>
          <button type="button" onClick={onDismiss} className="cursor-pointer rounded-lg px-3 py-1.5 text-xs font-semibold text-zinc-600 hover:bg-zinc-100 dark:text-zinc-300 dark:hover:bg-zinc-800">
            Dismiss
          </button>
        </div>
      )}
    </li>
  );
}

// ── Originality ───────────────────────────────────────────────────────────

/** Where a sentence of prose sits in the LaTeX: its first words, allowing markup between them. */
export function locate(latex: string, sentence: string): [number, number] | null {
  const words = sentence.match(/[\p{L}\p{N}]+/gu) ?? [];
  for (const size of [8, 6, 4]) {
    if (words.length < Math.min(size, 3)) continue;
    const head = words.slice(0, size).map((w) => w.replace(/[.*+?^${}()|[\]\\]/g, "\\$&"));
    // Between words: spaces and punctuation, commands, or inline math ($..$).
    const pattern = new RegExp(head.join("(?:\\$[^$]*\\$|\\\\[a-zA-Z]+|[^\\p{L}\\p{N}])+"), "iu");
    const found = pattern.exec(latex);
    if (found) return [found.index, found.index + found[0].length];
  }
  return null;
}

function ScoreRing({ score }: { score: number }) {
  const band = verdict(score).band;
  const color = band === "low" ? "text-emerald-500" : band === "moderate" ? "text-amber-500" : "text-rose-500";
  const radius = 34;
  const circumference = 2 * Math.PI * radius;
  return (
    <svg viewBox="0 0 80 80" aria-hidden="true" className="size-24 shrink-0 -rotate-90">
      <circle cx="40" cy="40" r={radius} className="fill-none stroke-zinc-200 dark:stroke-zinc-800" strokeWidth="8" />
      <circle cx="40" cy="40" r={radius} className={`fill-none stroke-current ${color}`} strokeWidth="8" strokeLinecap="round" strokeDasharray={circumference} strokeDashoffset={circumference * (1 - Math.min(score, 100) / 100)} />
    </svg>
  );
}

function OriginalityTab({
  workspaceId,
  editable,
  token,
  editor,
  left,
  onUsed,
  onShown,
}: {
  workspaceId: string;
  editable: boolean;
  token: () => Promise<string>;
  editor: () => LatexEditorHandle | null;
  left: AIStatus["originality"] | undefined;
  onUsed: () => void;
  onShown?: () => void;
}) {
  const [reports, setReports] = useState<OriginalityReport[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [starting, setStarting] = useState(false);
  const [shown, setShown] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  useEffect(() => {
    const controller = new AbortController();
    void (async () => {
      try {
        const list = await originalityReports(await token(), workspaceId, controller.signal);
        if (!controller.signal.aborted) setReports(list);
      } catch (caught) {
        if (!controller.signal.aborted) setError(aiMessage(caught));
      }
    })();
    return () => controller.abort();
  }, [token, workspaceId]);

  const current = reports?.find((r) => r.id === shown) ?? reports?.[0] ?? null;
  const pending = current && (current.status === "queued" || current.status === "running") ? current.id : null;

  // While a check runs, ask how it is going.
  useEffect(() => {
    if (!pending) return;
    const timer = setInterval(() => {
      void (async () => {
        try {
          const next = await originalityReport(await token(), workspaceId, pending);
          setReports((list) => (list ?? []).map((r) => (r.id === next.id ? next : r)));
          if (next.status === "done" || next.status === "failed") onUsed();
        } catch {
          // The next poll tries again.
        }
      })();
    }, POLL_MS);
    return () => clearInterval(timer);
  }, [pending, token, workspaceId, onUsed]);

  async function start() {
    setStarting(true);
    setError(null);
    try {
      const { report } = await startOriginality(await token(), workspaceId);
      setReports((list) => [report, ...(list ?? []).filter((r) => r.id !== report.id)]);
      setShown(report.id);
      onUsed();
    } catch (caught) {
      setError(aiMessage(caught));
    } finally {
      setStarting(false);
    }
  }

  function show(sentence: string) {
    const handle = editor();
    const at = handle ? locate(handle.text(), sentence) : null;
    if (handle && at) {
      handle.select(at[0], at[1]);
      setNotice(null);
      onShown?.();
    } else {
      setNotice("That sentence isn't in main.tex as it stands (it may be in another file, or edited since).");
    }
  }

  return (
    <div className="flex h-full flex-col gap-4 overflow-y-auto">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <Allowance feature={left} noun="check" />
        {editable && (
          <button
            type="button"
            onClick={() => void start()}
            disabled={starting || !!pending || left?.available === false || left?.left_today === 0}
            className="flex cursor-pointer items-center gap-2 rounded-xl bg-brand-600 px-4 py-2 text-sm font-semibold text-white shadow-sm hover:bg-brand-700 disabled:cursor-not-allowed disabled:opacity-60"
          >
            {starting || pending ? <Spinner /> : <SearchIcon className="size-4" />}
            {pending ? "Checking…" : current ? "Check again" : "Run originality check"}
          </button>
        )}
      </div>
      {error && <Alert tone="error">{error}</Alert>}
      {notice && <Alert tone="info">{notice}</Alert>}

      {reports === null && !error ? (
        <p role="status" className="flex items-center gap-2 text-sm text-zinc-600 dark:text-zinc-400">
          <Spinner /> Loading reports…
        </p>
      ) : !current ? (
        <div className="rounded-2xl border-2 border-dashed border-zinc-200 p-6 text-center dark:border-zinc-700">
          <span aria-hidden="true" className="mx-auto grid size-12 place-items-center rounded-2xl bg-brand-50 text-brand-600 dark:bg-brand-950 dark:text-brand-300">
            <SearchIcon className="size-6" />
          </span>
          <p className="mt-3 text-sm font-semibold">No checks yet</p>
          <p className="mt-1 text-sm text-zinc-600 dark:text-zinc-400">Compare your manuscript's wording with the titles and abstracts of 250M+ published works.</p>
        </div>
      ) : pending ? (
        <div role="status" className="rounded-2xl border border-zinc-200 p-5 dark:border-zinc-800">
          <p className="flex items-center gap-2 text-sm font-semibold">
            <Spinner /> {current.status === "queued" ? "Starting the check…" : "Searching the scholarly record…"}
          </p>
          <p className="mt-1 text-sm text-zinc-600 dark:text-zinc-400">This usually takes under a minute. You can keep writing; the report stays here.</p>
          <div className="mt-3 h-1.5 overflow-hidden rounded-full bg-zinc-100 dark:bg-zinc-800">
            <div className="h-full w-1/3 animate-pulse rounded-full bg-brand-500" />
          </div>
        </div>
      ) : current.status === "failed" ? (
        <Alert tone="error">{current.error ?? "The check could not be completed."}</Alert>
      ) : current.result ? (
        <Report report={current} onShow={show} />
      ) : null}

      {reports && reports.length > 1 && (
        <section aria-label="Earlier checks">
          <p className="text-xs font-semibold tracking-wider text-zinc-500 uppercase dark:text-zinc-400">Earlier checks</p>
          <ul className="mt-2 divide-y divide-zinc-100 rounded-xl border border-zinc-200 dark:divide-zinc-800 dark:border-zinc-800">
            {reports.map((r) => (
              <li key={r.id}>
                <button
                  type="button"
                  aria-current={r.id === current?.id ? "true" : undefined}
                  onClick={() => setShown(r.id)}
                  className={`flex w-full cursor-pointer items-center justify-between gap-2 px-3 py-2 text-left text-sm hover:bg-zinc-50 dark:hover:bg-zinc-800/60 ${r.id === current?.id ? "bg-zinc-50 dark:bg-zinc-800/60" : ""}`}
                >
                  <span>{formatDateTime(r.created_at)}</span>
                  <span className="font-semibold tabular-nums">{r.status === "done" && r.score !== null ? `${r.score}%` : r.status === "failed" ? "Failed" : "…"}</span>
                </button>
              </li>
            ))}
          </ul>
        </section>
      )}
      <p className="text-[11px] leading-relaxed text-zinc-500 dark:text-zinc-400">
        Compares wording with the titles and abstracts of 250M+ works in OpenAlex. It finds text copied word for word and text lightly reworded (words swapped, phrases shuffled). It cannot see full texts, web pages or unpublished work, and does not judge ideas rewritten from scratch, so a low score is not a guarantee.
      </p>
    </div>
  );
}

function Report({ report, onShow }: { report: OriginalityReport; onShow: (sentence: string) => void }) {
  const result = report.result;
  if (!result) return null;
  const v = verdict(result.score);
  const tone = v.band === "low" ? "text-emerald-700 dark:text-emerald-400" : v.band === "moderate" ? "text-amber-700 dark:text-amber-400" : "text-rose-700 dark:text-rose-400";
  return (
    <div className="space-y-4">
      <div className="flex items-center gap-4 rounded-2xl border border-zinc-200 p-4 dark:border-zinc-800">
        <div className="relative grid place-items-center">
          <ScoreRing score={result.score} />
          <span className="absolute text-xl font-bold tabular-nums">{result.score}%</span>
        </div>
        <div className="min-w-0">
          <p className={`text-base font-semibold ${tone}`}>{v.label}</p>
          <p className="mt-0.5 text-sm text-zinc-600 dark:text-zinc-400">
            {result.matched_words.toLocaleString()} of {result.words.toLocaleString()} words in sentences found in published abstracts.
          </p>
          <p className="mt-1 text-xs text-zinc-500 dark:text-zinc-400">
            {result.sources_checked} works compared · {timeAgo(report.finished_at ?? report.created_at)}
          </p>
        </div>
      </div>
      {result.partial && <Alert tone="info">SkoLab's daily search capacity ran out during this check, so fewer works were compared than usual.</Alert>}
      {result.sources.length === 0 ? (
        <p className="flex items-center gap-2 text-sm text-emerald-800 dark:text-emerald-300">
          <CheckIcon className="size-4" /> No sentence matched a published title or abstract.
        </p>
      ) : (
        <ul className="space-y-3">
          {result.sources.map((source) => (
            <SourceCard key={source.id} title={source.title} year={source.year} url={source.url} doi={source.doi}>
              {result.matches
                .filter((m) => m.source_id === source.id)
                .map((m, n) => (
                  <li key={n} className="rounded-lg bg-zinc-50 p-2.5 dark:bg-zinc-800/60">
                    <span className={`inline-block rounded-md px-1.5 py-0.5 text-[11px] font-semibold ${m.kind === "edited" ? "bg-amber-50 text-amber-800 dark:bg-amber-950 dark:text-amber-200" : "bg-rose-50 text-rose-800 dark:bg-rose-950 dark:text-rose-200"}`}>
                      {m.kind === "edited" ? "Lightly edited" : "Copied"}
                    </span>
                    <p className="mt-1 text-sm leading-relaxed">{m.sentence}</p>
                    <p className="mt-1 text-xs text-zinc-500 dark:text-zinc-400">
                      <span className="font-semibold text-rose-700 tabular-nums dark:text-rose-300">
                        {Math.round(m.overlap * 100)}% of its {m.kind === "edited" ? "key words" : "wording"}
                      </span>{" "}
                      {m.kind === "edited" ? "appear, reworded, in the source" : "appears word for word in the source"}: “{m.excerpt}”
                    </p>
                    <button type="button" onClick={() => onShow(m.sentence)} className="mt-1.5 cursor-pointer text-xs font-semibold text-brand-700 hover:underline dark:text-brand-300">
                      Show in document
                    </button>
                  </li>
                ))}
            </SourceCard>
          ))}
        </ul>
      )}
    </div>
  );
}

function SourceCard({ title, year, url, doi, children }: { title: string; year?: number | undefined; url: string; doi?: string | undefined; children: ReactNode }) {
  return (
    <li className="rounded-xl border border-zinc-200 p-3 dark:border-zinc-800">
      <a href={url} target="_blank" rel="noreferrer" className="group flex items-start gap-2 text-sm font-semibold hover:text-brand-700 dark:hover:text-brand-300">
        <span className="min-w-0 flex-1">
          {title || "Untitled work"}
          {year ? <span className="font-normal text-zinc-500"> ({year})</span> : null}
        </span>
        <ExternalIcon className="mt-0.5 size-3.5 opacity-60 group-hover:opacity-100" />
      </a>
      {doi && <p className="mt-0.5 font-mono text-[11px] text-zinc-500">doi:{doi}</p>}
      <ul className="mt-2 space-y-2">{children}</ul>
    </li>
  );
}

