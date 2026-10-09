import { autocompletion, closeBrackets, closeBracketsKeymap, completionKeymap, type CompletionContext } from "@codemirror/autocomplete";
import { defaultKeymap, history, historyKeymap, indentWithTab } from "@codemirror/commands";
import { bracketMatching, foldGutter, foldKeymap, HighlightStyle, indentOnInput, StreamLanguage, syntaxHighlighting } from "@codemirror/language";
import { stex } from "@codemirror/legacy-modes/mode/stex";
import { highlightSelectionMatches, searchKeymap } from "@codemirror/search";
import { Compartment, EditorState } from "@codemirror/state";
import {
  crosshairCursor,
  drawSelection,
  EditorView,
  highlightActiveLine,
  highlightActiveLineGutter,
  highlightSpecialChars,
  keymap,
  lineNumbers,
  rectangularSelection,
} from "@codemirror/view";
import { tags } from "@lezer/highlight";
import { forwardRef, useEffect, useImperativeHandle, useRef } from "react";

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

function latexCompletions(context: CompletionContext) {
  const env = context.matchBefore(/\\begin\{[\w*]*$/);
  if (env) {
    const from = env.from + "\\begin{".length;
    return {
      from,
      options: ENVIRONMENTS.map((name) => ({ label: name, type: "type", apply: `${name}}\n  \n\\end{${name}}` })),
    };
  }
  const word = context.matchBefore(/\\[a-zA-Z]*$/);
  if (!word || (word.from === word.to && !context.explicit)) return null;
  return {
    from: word.from + 1,
    options: COMMANDS.map((command) => ({ label: command.replace(/\{\}/g, ""), type: "keyword", apply: command.replace(/\{\}.*$/, "{}") })),
    validFor: /^[a-zA-Z]*$/,
  };
}

// Colors chosen for 4.5:1 contrast on both the light and the dark editor background.
const highlight = HighlightStyle.define([
  { tag: tags.keyword, color: "var(--cm-keyword)" },
  { tag: [tags.tagName, tags.typeName], color: "var(--cm-env)" },
  { tag: tags.comment, color: "var(--cm-comment)", fontStyle: "italic" },
  { tag: [tags.bracket, tags.squareBracket], color: "var(--cm-bracket)" },
  { tag: tags.string, color: "var(--cm-math)" },
  { tag: [tags.atom, tags.number], color: "var(--cm-atom)" },
  { tag: tags.meta, color: "var(--cm-keyword)" },
]);

const theme = EditorView.theme({
  "&": { height: "100%", fontSize: "14px", backgroundColor: "var(--cm-bg)", color: "var(--cm-fg)" },
  ".cm-scroller": { fontFamily: "ui-monospace, SFMono-Regular, Menlo, Consolas, monospace", lineHeight: "1.6" },
  ".cm-content": { caretColor: "var(--cm-fg)" },
  ".cm-gutters": { backgroundColor: "var(--cm-gutter-bg)", color: "var(--cm-gutter-fg)", border: "none" },
  ".cm-activeLine": { backgroundColor: "var(--cm-active)" },
  ".cm-activeLineGutter": { backgroundColor: "var(--cm-active)" },
  "&.cm-focused .cm-selectionBackground, .cm-selectionBackground, ::selection": { backgroundColor: "var(--cm-selection)" },
  "&.cm-focused": { outline: "none" },
});

export interface LatexEditorHandle {
  /** Moves the cursor to a 1-based line and scrolls it into view. */
  goToLine(line: number): void;
  focus(): void;
}

interface LatexEditorProps {
  initialValue: string;
  onChange: (value: string) => void;
  /** Ctrl/Cmd+Enter and Ctrl/Cmd+S, with the current text. */
  onCompile: (value: string) => void;
  label: string;
  /** Viewers and commenters can read, select and copy, but not change the text. */
  readOnly?: boolean;
}

function labelAttributes(label: string, readOnly: boolean) {
  return EditorView.contentAttributes.of(readOnly ? { "aria-label": label, "aria-readonly": "true" } : { "aria-label": label });
}

/**
 * A LaTeX source editor (CodeMirror 6). Uncontrolled: initialValue and
 * readOnly are read once (remount with a key to change them); the label
 * follows its prop (a file that is renamed while open).
 */
export const LatexEditor = forwardRef<LatexEditorHandle, LatexEditorProps>(function LatexEditor({ initialValue, onChange, onCompile, label, readOnly = false }, ref) {
  const host = useRef<HTMLDivElement>(null);
  const view = useRef<EditorView | null>(null);
  const callbacks = useRef({ onChange, onCompile });
  const labelling = useRef(new Compartment());
  useEffect(() => {
    callbacks.current = { onChange, onCompile };
  });

  useEffect(() => {
    if (!host.current) return;
    // CodeMirror styles itself at runtime. In a shadow root it uses
    // constructed stylesheets, which the site's CSP (style-src 'self', no
    // inline <style>) allows; in the main document it would need a <style> tag.
    const shadow = host.current.shadowRoot ?? host.current.attachShadow({ mode: "open" });
    const mount = document.createElement("div");
    mount.style.height = "100%";
    shadow.replaceChildren(mount);
    const compile = (target: EditorView) => {
      callbacks.current.onCompile(target.state.doc.toString());
      return true;
    };
    const editor = new EditorView({
      parent: mount,
      root: shadow,
      state: EditorState.create({
        doc: initialValue,
        extensions: [
          lineNumbers(),
          highlightActiveLineGutter(),
          highlightSpecialChars(),
          history(),
          foldGutter(),
          drawSelection(),
          EditorState.allowMultipleSelections.of(true),
          indentOnInput(),
          bracketMatching(),
          closeBrackets(),
          autocompletion({ override: [latexCompletions] }),
          rectangularSelection(),
          crosshairCursor(),
          highlightActiveLine(),
          highlightSelectionMatches(),
          StreamLanguage.define(stex),
          syntaxHighlighting(highlight),
          EditorView.lineWrapping,
          theme,
          EditorState.readOnly.of(readOnly),
          labelling.current.of(labelAttributes(label, readOnly)),
          keymap.of([
            { key: "Mod-Enter", run: compile, preventDefault: true },
            { key: "Mod-s", run: compile, preventDefault: true },
            ...closeBracketsKeymap,
            ...defaultKeymap,
            ...searchKeymap,
            ...historyKeymap,
            ...foldKeymap,
            ...completionKeymap,
            indentWithTab,
          ]),
          EditorView.updateListener.of((update) => {
            if (update.docChanged) callbacks.current.onChange(update.state.doc.toString());
          }),
        ],
      }),
    });
    // Long lines and documents scroll; keyboard users must reach the scroller (WCAG 2.1.1).
    editor.scrollDOM.tabIndex = 0;
    view.current = editor;
    return () => {
      editor.destroy();
      view.current = null;
    };
    // initialValue and readOnly are read once by design; the editor owns the text afterwards.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  useEffect(() => {
    view.current?.dispatch({ effects: labelling.current.reconfigure(labelAttributes(label, readOnly)) });
  }, [label, readOnly]);

  useImperativeHandle(ref, () => ({
    goToLine(line: number) {
      const editor = view.current;
      if (!editor) return;
      const target = editor.state.doc.line(Math.min(Math.max(line, 1), editor.state.doc.lines));
      editor.dispatch({ selection: { anchor: target.from }, effects: EditorView.scrollIntoView(target.from, { y: "center" }) });
      editor.focus();
    },
    focus() {
      view.current?.focus();
    },
  }));

  return <div ref={host} className="h-full min-h-0 overflow-hidden" data-testid="latex-editor" />;
});
