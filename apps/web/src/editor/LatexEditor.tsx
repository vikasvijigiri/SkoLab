import { autocompletion, closeBrackets, closeBracketsKeymap, completionKeymap } from "@codemirror/autocomplete";
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
import {
  definitions,
  EMPTY_INDEX,
  focusMode as focusExtension,
  followReference,
  imageFiles,
  indexField,
  insertCitation,
  insertFigures,
  insertSnippet,
  latexChecks,
  latexCompletions,
  mathAtCursor,
  nextDiagnostic,
  problemCounts,
  setIndex,
  toggleComment,
  wrap,
  type Destination,
  type EditorIndex,
  type ProblemCounts,
} from "./latex/extensions";
import type { MathSpan } from "./latex/math";

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
  ".cm-tooltip": {
    backgroundColor: "var(--cm-tooltip-bg)",
    color: "var(--cm-fg)",
    border: "1px solid var(--cm-tooltip-border)",
    borderRadius: "10px",
    boxShadow: "0 12px 32px -12px rgb(0 0 0 / 0.35)",
    overflow: "hidden",
    fontFamily: "var(--font-sans, ui-sans-serif, system-ui, sans-serif)",
  },
  ".cm-tooltip.cm-tooltip-autocomplete > ul": { fontFamily: "inherit", maxHeight: "18rem" },
  ".cm-tooltip.cm-tooltip-autocomplete > ul > li": { padding: "4px 10px", lineHeight: "1.5" },
  ".cm-tooltip-autocomplete ul li[aria-selected]": { backgroundColor: "var(--cm-accent)", color: "var(--cm-accent-fg)" },
  ".cm-completionDetail": { opacity: "0.75", marginLeft: "0.75em", fontStyle: "normal" },
  ".cm-completionInfo": { padding: "6px 10px", maxWidth: "22rem" },
  ".cm-tooltip-lint": { padding: "0" },
  ".cm-diagnostic": { padding: "8px 12px", borderLeftWidth: "4px", maxWidth: "28rem", whiteSpace: "normal" },
  ".cm-diagnosticAction": { backgroundColor: "var(--cm-accent)", color: "var(--cm-accent-fg)", borderRadius: "6px", padding: "2px 8px", marginTop: "6px", marginRight: "6px", font: "inherit", fontWeight: "600", cursor: "pointer" },
  ".cm-diagnosticSource": { display: "none" },
  ".cm-reference-tip": { padding: "8px 12px", maxWidth: "26rem", fontSize: "13px" },
  ".cm-reference-hint": { marginTop: "2px", fontSize: "11px", opacity: "0.7" },
  "&.cm-drop-target": { outline: "2px dashed var(--cm-accent)", outlineOffset: "-6px" },
});

/** Where the cursor is, and the math it sits in, for the status strip and the math preview. */
export interface CursorInfo {
  line: number;
  column: number;
  focused: boolean;
  math: MathSpan | null;
  /** Viewport coordinates under the math (or the cursor), for placing a preview. */
  anchor: { left: number; top: number; bottom: number } | null;
}

/** Actions the page runs on the editor: from the command bar, menus and the outline. */
export type EditorAction = "bold" | "italic" | "math" | "display-math" | "comment" | "cite" | "next-problem" | "follow";

export interface LatexEditorHandle {
  /** Moves the cursor to a 1-based line and scrolls it into view. */
  goToLine(line: number): void;
  focus(): void;
  /** Runs an action as its shortcut would; false when it can't (read-only, nothing there). */
  run(action: EditorAction): boolean;
  /** Inserts a slash-command block (/figure, /table, …) at the cursor. */
  insertSnippet(name: string): boolean;
  /** Inserts figure blocks for images already in the project, at the cursor. */
  insertFigures(paths: readonly string[]): void;
}

interface LatexEditorProps {
  initialValue: string;
  onChange: (value: string) => void;
  /** Ctrl/Cmd+Enter and Ctrl/Cmd+S, with the current text. */
  onCompile: (value: string) => void;
  label: string;
  /** Viewers and commenters can read, select and copy, but not change the text. */
  readOnly?: boolean;
  /** What the rest of the project holds; turns on the live checks (main.tex). */
  index?: EditorIndex;
  /** Fades all but the current paragraph and centres the text. */
  focusMode?: boolean;
  onCursor?: (cursor: CursorInfo) => void;
  onProblems?: (counts: ProblemCounts) => void;
  /** A reference that points outside this document (a label in another file, a .bib entry, a file). */
  onNavigate?: (destination: Destination) => boolean;
  /** Uploads dropped or pasted images; answers their project paths. */
  onImages?: (files: File[]) => Promise<string[]>;
}

function labelAttributes(label: string, readOnly: boolean) {
  return EditorView.contentAttributes.of(readOnly ? { "aria-label": label, "aria-readonly": "true" } : { "aria-label": label });
}

/**
 * A LaTeX source editor (CodeMirror 6). Uncontrolled: initialValue and
 * readOnly are read once (remount with a key to change them); the label
 * follows its prop (a file that is renamed while open).
 */
export const LatexEditor = forwardRef<LatexEditorHandle, LatexEditorProps>(function LatexEditor(
  { initialValue, onChange, onCompile, label, readOnly = false, index, focusMode = false, onCursor, onProblems, onNavigate, onImages },
  ref,
) {
  const host = useRef<HTMLDivElement>(null);
  const view = useRef<EditorView | null>(null);
  const callbacks = useRef({ onChange, onCompile, onCursor, onProblems, onNavigate, onImages });
  const labelling = useRef(new Compartment());
  const focusing = useRef(new Compartment());
  const checking = index !== undefined;
  useEffect(() => {
    callbacks.current = { onChange, onCompile, onCursor, onProblems, onNavigate, onImages };
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
    const away = (destination: Destination) => callbacks.current.onNavigate?.(destination) ?? false;
    const follow = (target: EditorView) => followReference(target, target.state.selection.main.head, away);
    let counts: ProblemCounts = { errors: 0, warnings: 0 };
    const report = (target: EditorView) => {
      const math = mathAtCursor(target.state);
      const head = target.state.selection.main.head;
      const line = target.state.doc.lineAt(head);
      target.requestMeasure({
        key: "skolab-cursor",
        read: (measured) => {
          const start = measured.coordsAtPos(math ? math.from : head);
          const end = math ? (measured.coordsAtPos(math.to, -1) ?? start) : start;
          return start && end ? { left: start.left, top: Math.min(start.top, end.top), bottom: Math.max(start.bottom, end.bottom) } : null;
        },
        write: (anchor) => callbacks.current.onCursor?.({ line: line.number, column: head - line.from + 1, focused: target.hasFocus, math, anchor }),
      });
    };
    const images = async (target: EditorView, files: File[], pos: number) => {
      const upload = callbacks.current.onImages;
      if (!upload || files.length === 0) return;
      const paths = await upload(files);
      if (view.current === target) insertFigures(target, pos, paths);
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
          autocompletion({ override: [latexCompletions], icons: false }),
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
          focusing.current.of(focusMode ? focusExtension : []),
          indexField.init(() => index ?? EMPTY_INDEX),
          checking ? latexChecks() : [],
          definitions(away),
          keymap.of([
            { key: "Mod-Enter", run: compile, preventDefault: true },
            { key: "Mod-s", run: compile, preventDefault: true },
            { key: "Mod-b", run: wrap("\\textbf{", "}"), preventDefault: true },
            { key: "Mod-i", run: wrap("\\textit{", "}"), preventDefault: true },
            { key: "Mod-m", run: wrap("$", "$"), preventDefault: true },
            { key: "Mod-Shift-m", run: wrap("\\[ ", " \\]"), preventDefault: true },
            { key: "Mod-/", run: toggleComment, preventDefault: true },
            { key: "Mod-Shift-l", run: insertCitation, preventDefault: true },
            { key: "F12", run: follow, preventDefault: true },
            { key: "F8", run: nextDiagnostic, preventDefault: true },
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
            if (update.docChanged || update.selectionSet || update.focusChanged || update.geometryChanged) report(update.view);
            const next = problemCounts(update.state);
            if (next.errors !== counts.errors || next.warnings !== counts.warnings) {
              counts = next;
              callbacks.current.onProblems?.(next);
            }
          }),
          EditorView.domEventHandlers({
            dragover(event, target) {
              if (readOnly || !callbacks.current.onImages || !event.dataTransfer?.types.includes("Files")) return false;
              event.preventDefault();
              target.dom.classList.add("cm-drop-target");
              return true;
            },
            dragleave(_event, target) {
              target.dom.classList.remove("cm-drop-target");
              return false;
            },
            drop(event, target) {
              target.dom.classList.remove("cm-drop-target");
              const files = imageFiles(event.dataTransfer?.files);
              if (readOnly || !callbacks.current.onImages || files.length === 0) return false;
              event.preventDefault();
              const pos = target.posAtCoords({ x: event.clientX, y: event.clientY }) ?? target.state.selection.main.head;
              void images(target, files, pos);
              return true;
            },
            paste(event, target) {
              const files = imageFiles(event.clipboardData?.files);
              if (readOnly || !callbacks.current.onImages || files.length === 0) return false;
              event.preventDefault();
              void images(target, files, target.state.selection.main.head);
              return true;
            },
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

  useEffect(() => {
    view.current?.dispatch({ effects: focusing.current.reconfigure(focusMode ? focusExtension : []) });
  }, [focusMode]);

  useEffect(() => {
    if (index) view.current?.dispatch({ effects: setIndex.of(index) });
  }, [index]);

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
    run(action) {
      const editor = view.current;
      if (!editor) return false;
      const commands = {
        bold: wrap("\\textbf{", "}"),
        italic: wrap("\\textit{", "}"),
        math: wrap("$", "$"),
        "display-math": wrap("\\[ ", " \\]"),
        comment: toggleComment,
        cite: insertCitation,
        "next-problem": nextDiagnostic,
        follow: (target: EditorView) => followReference(target, target.state.selection.main.head, (destination) => callbacks.current.onNavigate?.(destination) ?? false),
      } satisfies Record<EditorAction, (target: EditorView) => boolean>;
      editor.focus();
      return commands[action](editor);
    },
    insertSnippet(name) {
      const editor = view.current;
      if (!editor) return false;
      editor.focus();
      return insertSnippet(editor, name);
    },
    insertFigures(paths) {
      const editor = view.current;
      if (!editor) return;
      editor.focus();
      insertFigures(editor, editor.state.selection.main.head, paths);
    },
  }));

  return <div ref={host} className="h-full min-h-0 overflow-hidden" data-testid="latex-editor" />;
});
