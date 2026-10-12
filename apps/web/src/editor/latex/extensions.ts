import { snippet, snippetCompletion, startCompletion, type Completion, type CompletionContext, type CompletionResult } from "@codemirror/autocomplete";
import { forEachDiagnostic, linter, nextDiagnostic, type Diagnostic } from "@codemirror/lint";
import { EditorSelection, StateEffect, StateField, type EditorState, type Extension, type Text } from "@codemirror/state";
import { Decoration, EditorView, hoverTooltip, ViewPlugin, type Command, type DecorationSet, type ViewUpdate } from "@codemirror/view";
import { bibitems, describe, type BibEntry } from "./bib";
import { mathSpans, type MathSpan } from "./math";
import { checkSource, compileProblems, type ProjectFacts } from "./problems";
import { addPackage, labelsIn, packagesIn, referenceAt, type Reference } from "./scan";
import { figureFor, plainSnippet, SNIPPETS } from "./snippets";

/** What the editor knows about the rest of the project. */
export interface EditorIndex {
  facts: ProjectFacts;
  /** Entries of the project's .bib files. */
  bib: readonly BibEntry[];
  /** Labels in the project's other .tex files. */
  labels: readonly { key: string; file: string; context: string }[];
  /** Every project path. */
  files: readonly string[];
  /** The last compile's errors ("line 12: …"), until the next compile. */
  compileErrors: readonly string[];
}

export const EMPTY_INDEX: EditorIndex = {
  facts: { labels: new Set(), bibKeys: new Set(), haveBib: false, files: null, complete: true },
  bib: [],
  labels: [],
  files: [],
  compileErrors: [],
};

export const setIndex = StateEffect.define<EditorIndex>();

export const indexField = StateField.define<EditorIndex>({
  create: () => EMPTY_INDEX,
  update(value, transaction) {
    for (const effect of transaction.effects) if (effect.is(setIndex)) return effect.value;
    return value;
  },
});

// ---------------------------------------------------------------- completion

const COMMANDS = [
  "section{}", "subsection{}", "subsubsection{}", "paragraph{}", "chapter{}", "title{}", "author{}", "date{}", "maketitle",
  "begin{}", "end{}", "label{}", "ref{}", "eqref{}", "cite{}", "citep{}", "citet{}", "footnote{}", "emph{}", "textbf{}",
  "textit{}", "texttt{}", "underline{}", "item", "usepackage{}", "documentclass{}", "includegraphics{}", "caption{}",
  "centering", "frac{}{}", "sqrt{}", "sum", "int", "alpha", "beta", "gamma", "delta", "epsilon", "lambda", "mu", "pi",
  "sigma", "theta", "omega", "infty", "partial", "nabla", "cdot", "times", "leq", "geq", "neq", "approx", "mathbf{}",
  "mathrm{}", "mathcal{}", "hline", "newpage", "tableofcontents", "bibitem{}", "url{}", "href{}{}", "frametitle{}",
];

const ENVIRONMENTS = [
  "equation", "equation*", "align", "align*", "figure", "table", "tabular", "itemize", "enumerate", "description",
  "abstract", "theorem", "proof", "lemma", "frame", "thebibliography", "center", "minipage", "verbatim", "quote",
];

/** Case-insensitive: every typed word appears somewhere in the haystack. */
function matches(query: string, haystack: string): boolean {
  const words = query.toLowerCase().split(/\s+/).filter(Boolean);
  const text = haystack.toLowerCase();
  return words.every((word) => text.includes(word));
}

function slashCompletions(context: CompletionContext): CompletionResult | null {
  const typed = context.matchBefore(/(?:^|[\s({])\/[a-zA-Z]*$/);
  if (!typed) return null;
  const slash = typed.from + typed.text.indexOf("/");
  const query = typed.text.slice(typed.text.indexOf("/") + 1).toLowerCase();
  const options = SNIPPETS.filter((item) => !query || item.name.startsWith(query) || item.aliases.some((alias) => alias.startsWith(query)) || item.title.toLowerCase().includes(query)).map(
    (item, index): Completion =>
      snippetCompletion(item.template, { label: `/${item.name}`, displayLabel: item.title, detail: `/${item.name}`, info: item.detail, type: "text", boost: -index }),
  );
  return options.length ? { from: slash, options, filter: false } : null;
}

const CITE_BEFORE = /\\(?:cite[a-z]*|parencite|textcite|autocite|footcite|supercite|Cite[a-z]*)\*?\s*(?:\[[^\]]*\]\s*){0,2}\{[^}]*$/;

export function citationOptions(state: EditorState, query: string): Completion[] {
  const index = state.field(indexField, false) ?? EMPTY_INDEX;
  const seen = new Set<string>();
  const entries = [...bibitems(state.doc.toString()), ...index.bib].filter((entry) => !seen.has(entry.key) && seen.add(entry.key));
  return entries
    .filter((entry) => !query || matches(query, `${entry.key} ${entry.authors} ${entry.year} ${entry.title}`))
    .slice(0, 200)
    .map((entry) => ({ label: entry.key, detail: [entry.authors, entry.year].filter(Boolean).join(" ") || entry.type, ...(entry.title ? { info: entry.title } : {}), type: "variable" }));
}

function citeCompletions(context: CompletionContext): CompletionResult | null {
  const before = context.matchBefore(CITE_BEFORE);
  if (!before) return null;
  const inside = before.text.slice(before.text.lastIndexOf("{") + 1);
  const part = inside.slice(inside.lastIndexOf(",") + 1);
  const from = context.pos - part.trimStart().length;
  return { from, options: citationOptions(context.state, part.trim()), filter: false };
}

function refCompletions(context: CompletionContext): CompletionResult | null {
  const before = context.matchBefore(/\\(?:ref|eqref|autoref|cref|Cref|pageref|nameref)\*?\{[^}]*$/);
  if (!before) return null;
  const typed = before.text.slice(before.text.lastIndexOf("{") + 1);
  const part = typed.slice(typed.lastIndexOf(",") + 1).trimStart();
  const index = context.state.field(indexField, false) ?? EMPTY_INDEX;
  const options: Completion[] = [
    ...labelsIn(context.state.doc.toString()).map((label) => ({ label: label.key, detail: `line ${label.line}`, ...(label.context ? { info: label.context } : {}), type: "constant" })),
    ...index.labels.map((label) => ({ label: label.key, detail: label.file, ...(label.context ? { info: label.context } : {}), type: "constant" })),
  ];
  return { from: context.pos - part.length, options, validFor: /^[^},]*$/ };
}

function fileCompletions(context: CompletionContext): CompletionResult | null {
  const before = context.matchBefore(/\\(input|include|includegraphics|bibliography|addbibresource)\s*(?:\[[^\]]*\])?\{[^}]*$/);
  if (!before) return null;
  const command = /\\(\w+)/.exec(before.text)?.[1] ?? "";
  const typed = before.text.slice(before.text.lastIndexOf("{") + 1);
  const index = context.state.field(indexField, false) ?? EMPTY_INDEX;
  const pattern = command === "includegraphics" ? /\.(?:png|jpe?g|pdf|eps|svg)$/i : command === "addbibresource" || command === "bibliography" ? /\.bib$/i : /\.tex$/i;
  const strip = command === "addbibresource" ? null : command === "includegraphics" ? null : /\.(?:tex|bib)$/i;
  const options = index.files.filter((path) => pattern.test(path)).map((path) => ({ label: strip ? path.replace(strip, "") : path, type: "text" }));
  return options.length ? { from: context.pos - typed.length, options, validFor: /^[^}]*$/ } : null;
}

function commandCompletions(context: CompletionContext): CompletionResult | null {
  const env = context.matchBefore(/\\begin\{[\w*]*$/);
  if (env) {
    const from = env.from + "\\begin{".length;
    return { from, options: ENVIRONMENTS.map((name) => ({ label: name, type: "type", apply: `${name}}\n  \n\\end{${name}}` })) };
  }
  const word = context.matchBefore(/\\[a-zA-Z]*$/);
  if (!word || (word.from === word.to && !context.explicit)) return null;
  return {
    from: word.from + 1,
    options: COMMANDS.map((command) => ({ label: command.replace(/\{\}/g, ""), type: "keyword", apply: command.replace(/\{\}.*$/, "{}") })),
    validFor: /^[a-zA-Z]*$/,
  };
}

/** Every LaTeX completion: slash commands, citations, labels, files, environments and commands. */
export function latexCompletions(context: CompletionContext): CompletionResult | null {
  return citeCompletions(context) ?? refCompletions(context) ?? fileCompletions(context) ?? slashCompletions(context) ?? commandCompletions(context);
}

// ---------------------------------------------------------------- formatting

/** Wraps each selection in before/after, or unwraps it when it already is. An empty selection gets the cursor inside. */
export function wrap(before: string, after: string): Command {
  return (view) => {
    if (view.state.readOnly) return false;
    const { state } = view;
    view.dispatch(
      state.changeByRange((range) => {
        const outside = state.sliceDoc(range.from - before.length, range.from) === before && state.sliceDoc(range.to, range.to + after.length) === after;
        if (outside) {
          return {
            changes: [
              { from: range.from - before.length, to: range.from, insert: "" },
              { from: range.to, to: range.to + after.length, insert: "" },
            ],
            range: EditorSelection.range(range.from - before.length, range.to - before.length),
          };
        }
        return {
          changes: [
            { from: range.from, insert: before },
            { from: range.to, insert: after },
          ],
          range: EditorSelection.range(range.from + before.length, range.to + before.length),
        };
      }),
      { scrollIntoView: true, userEvent: "input.format" },
    );
    return true;
  };
}

/** Comments out the selected lines with %, or uncomments them when they all are. */
export const toggleComment: Command = (view) => {
  if (view.state.readOnly) return false;
  const { state } = view;
  const lines = new Map<number, { from: number; text: string }>();
  for (const range of state.selection.ranges) {
    for (let at = state.doc.lineAt(range.from).number; at <= state.doc.lineAt(range.to).number; at += 1) {
      const line = state.doc.line(at);
      lines.set(at, { from: line.from, text: line.text });
    }
  }
  const filled = [...lines.values()].filter((line) => line.text.trim());
  const commented = filled.length > 0 && filled.every((line) => /^\s*%/.test(line.text));
  const changes = (filled.length ? filled : [...lines.values()]).map((line) => {
    if (!commented) return { from: line.from, insert: "% " };
    const at = line.text.indexOf("%");
    return { from: line.from + at, to: line.from + at + (line.text[at + 1] === " " ? 2 : 1), insert: "" };
  });
  view.dispatch({ changes, userEvent: "input.format" });
  return true;
};

/** Opens the citation picker: inside \cite{…} it just opens, elsewhere it inserts \cite{} first. */
export const insertCitation: Command = (view) => {
  const { state } = view;
  const head = state.selection.main.head;
  const line = state.doc.lineAt(head);
  if (!CITE_BEFORE.test(line.text.slice(0, head - line.from))) {
    if (state.readOnly) return false;
    view.dispatch({ changes: { from: head, insert: "\\cite{}" }, selection: { anchor: head + "\\cite{".length }, userEvent: "input" });
  }
  startCompletion(view);
  return true;
};

/** Inserts a ready-made block by its slash-command name, with Tab stops. */
export function insertSnippet(view: EditorView, name: string): boolean {
  const item = SNIPPETS.find((entry) => entry.name === name);
  if (!item || view.state.readOnly) return false;
  const range = view.state.selection.main;
  snippet(item.template)(view, null, range.from, range.to);
  if (name === "cite" || name === "ref") startCompletion(view);
  return true;
}

// ---------------------------------------------------------------- problems

export interface ProblemCounts {
  errors: number;
  warnings: number;
}

/** Diagnostics for the main document: live checks plus the last compile's errors. */
export function latexChecks(): Extension {
  return [
    linter(
      (view) => {
        const source = view.state.doc.toString();
        const index = view.state.field(indexField);
        return [...compileProblems(source, index.compileErrors), ...checkSource(source, index.facts)].map(
          (problem): Diagnostic => ({
            from: problem.from,
            to: problem.to,
            severity: problem.severity,
            message: problem.message,
            source: problem.source === "compile" ? "Compiler" : "Check",
            actions: problem.fixes.map((fix) => ({
              name: fix.label,
              apply(target) {
                target.dispatch({ changes: fix.changes.map((change) => ({ ...change, to: change.to ?? change.from })), userEvent: "input.fix" });
              },
            })),
          }),
        );
      },
      { delay: 400, needsRefresh: (update) => update.startState.field(indexField) !== update.state.field(indexField) },
    ),
  ];
}

export function problemCounts(state: EditorState): ProblemCounts {
  const counts = { errors: 0, warnings: 0 };
  forEachDiagnostic(state, (diagnostic) => {
    if (diagnostic.severity === "error") counts.errors += 1;
    else if (diagnostic.severity === "warning") counts.warnings += 1;
  });
  return counts;
}

export { nextDiagnostic };

// ---------------------------------------------------------------- navigation

export type Destination = { kind: "label"; key: string } | { kind: "cite"; key: string } | { kind: "file"; path: string; reference: Reference["kind"] };

/**
 * Follows the reference under a position: a \ref to a label in this
 * document moves the cursor there; anything elsewhere goes to `away`.
 */
export function followReference(view: EditorView, pos: number, away: (destination: Destination) => boolean): boolean {
  const reference = referenceAt(view.state.doc.toString(), pos);
  if (!reference) return false;
  if (reference.kind === "ref") {
    const label = labelsIn(view.state.doc.toString()).find((item) => item.key === reference.key);
    if (label) {
      view.dispatch({ selection: { anchor: label.from, head: label.from + label.key.length }, effects: EditorView.scrollIntoView(label.from, { y: "center" }) });
      return true;
    }
    return away({ kind: "label", key: reference.key });
  }
  if (reference.kind === "cite") return away({ kind: "cite", key: reference.key });
  return away({ kind: "file", path: reference.key, reference: reference.kind });
}

/** What a reference points at, in a line, for hover previews. */
export function describeReference(state: EditorState, reference: Reference): string {
  const index = state.field(indexField, false) ?? EMPTY_INDEX;
  if (reference.kind === "ref") {
    const here = labelsIn(state.doc.toString()).find((label) => label.key === reference.key);
    if (here) return `Line ${here.line}: ${here.context || reference.key}`;
    const there = index.labels.find((label) => label.key === reference.key);
    return there ? `${there.file}: ${there.context || reference.key}` : `No \\label{${reference.key}} yet`;
  }
  if (reference.kind === "cite") {
    const entry = [...bibitems(state.doc.toString()), ...index.bib].find((item) => item.key === reference.key);
    return entry ? describe(entry) : `No entry “${reference.key}” in your bibliography`;
  }
  return `Open ${reference.key}`;
}

/** Ctrl/Cmd-click and hover on \ref, \cite, \input, \includegraphics and \bibliography keys. */
export function definitions(away: (destination: Destination) => boolean): Extension {
  return [
    EditorView.domEventHandlers({
      mousedown(event, view) {
        if (!(event.ctrlKey || event.metaKey) || event.button !== 0) return false;
        const pos = view.posAtCoords({ x: event.clientX, y: event.clientY });
        if (pos === null || !followReference(view, pos, away)) return false;
        event.preventDefault();
        return true;
      },
    }),
    hoverTooltip((view, pos) => {
      const reference = referenceAt(view.state.doc.toString(), pos);
      if (!reference) return null;
      return {
        pos: reference.from,
        end: reference.to,
        above: true,
        create() {
          const dom = document.createElement("div");
          dom.className = "cm-reference-tip";
          const text = document.createElement("div");
          text.textContent = describeReference(view.state, reference);
          const hint = document.createElement("div");
          hint.className = "cm-reference-hint";
          hint.textContent = "Ctrl-click or F12 to open";
          dom.append(text, hint);
          return { dom };
        },
      };
    }),
  ];
}

// ---------------------------------------------------------------- math

const spansCache = new WeakMap<Text, MathSpan[]>();

/** The math span at the main cursor, if any (scans once per document version). */
export function mathAtCursor(state: EditorState): MathSpan | null {
  let spans = spansCache.get(state.doc);
  if (!spans) {
    spans = mathSpans(state.doc.toString());
    spansCache.set(state.doc, spans);
  }
  const head = state.selection.main.head;
  return spans.find((span) => span.from <= head && head <= span.to) ?? null;
}

// ---------------------------------------------------------------- focus mode

const dimmed = Decoration.line({ class: "cm-dimmed" });

function paragraphDecorations(view: EditorView): DecorationSet {
  const { doc } = view.state;
  const head = doc.lineAt(view.state.selection.main.head).number;
  let first = head;
  let last = head;
  while (first > 1 && doc.line(first - 1).text.trim()) first -= 1;
  while (last < doc.lines && doc.line(last + 1).text.trim()) last += 1;
  const ranges = [];
  for (const { from, to } of view.visibleRanges) {
    for (let pos = from; pos <= to; ) {
      const line = doc.lineAt(pos);
      if (line.number < first || line.number > last) ranges.push(dimmed.range(line.from));
      pos = line.to + 1;
    }
  }
  return Decoration.set(ranges);
}

/** Focus mode: everything but the paragraph you're in fades, and the cursor stays near the middle. */
export const focusMode: Extension = [
  ViewPlugin.fromClass(
    class {
      decorations: DecorationSet;
      constructor(view: EditorView) {
        this.decorations = paragraphDecorations(view);
      }
      update(update: ViewUpdate) {
        if (update.docChanged || update.selectionSet || update.viewportChanged) this.decorations = paragraphDecorations(update.view);
      }
    },
    { decorations: (plugin) => plugin.decorations },
  ),
  EditorView.scrollMargins.of((view) => {
    const margin = view.scrollDOM.clientHeight * 0.4;
    return { top: margin, bottom: margin };
  }),
  EditorView.theme({
    ".cm-content": { maxWidth: "46rem", margin: "0 auto", padding: "4rem 1.5rem" },
    ".cm-line": { transition: "opacity 160ms ease" },
    ".cm-dimmed": { opacity: "0.35" },
    ".cm-gutters": { display: "none" },
  }),
];

// ---------------------------------------------------------------- figures

// What pdfLaTeX's \includegraphics can draw.
const IMAGE = /^image\/(?:png|jpeg)$|^application\/pdf$/;

export function imageFiles(list: FileList | null | undefined): File[] {
  return [...(list ?? [])].filter((file) => IMAGE.test(file.type) || /\.(?:png|jpe?g|pdf)$/i.test(file.name));
}

/**
 * Inserts a figure block for each uploaded image on its own line after
 * `pos`, loading graphicx first when the document doesn't.
 */
export function insertFigures(view: EditorView, pos: number, paths: readonly string[]): void {
  if (paths.length === 0) return;
  let at = Math.min(pos, view.state.doc.length);
  const source = view.state.doc.toString();
  if (/\\documentclass/.test(source) && !packagesIn(source).has("graphicx")) {
    const change = addPackage(source, "graphicx");
    const transaction = view.state.update({ changes: change, userEvent: "input" });
    view.dispatch(transaction);
    at = transaction.changes.mapPos(at, 1);
  }
  const line = view.state.doc.lineAt(at);
  const insertAt = line.text.trim() ? line.to : line.from;
  const lead = line.text.trim() ? "\n" : "";
  const blocks = paths.map((path) => figureFor(path));
  if (blocks.length === 1) {
    snippet(lead + (blocks[0] ?? ""))(view, null, insertAt, insertAt);
  } else {
    const text = lead + blocks.map((block) => plainSnippet(block)).join("\n");
    view.dispatch({ changes: { from: insertAt, insert: text }, selection: { anchor: insertAt + text.length }, userEvent: "input" });
  }
}
