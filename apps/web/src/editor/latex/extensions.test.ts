import { autocompletion, CompletionContext, currentCompletions, type CompletionResult } from "@codemirror/autocomplete";
import { forceLinting } from "@codemirror/lint";
import { EditorSelection, EditorState, type Extension } from "@codemirror/state";
import { EditorView } from "@codemirror/view";
import { afterEach, describe, expect, it, vi } from "vitest";
import {
  citationOptions,
  definitions,
  describeReference,
  EMPTY_INDEX,
  followReference,
  focusMode,
  imageFiles,
  indexField,
  insertCitation,
  insertFigures,
  insertSnippet,
  latexChecks,
  latexCompletions,
  mathAtCursor,
  problemCounts,
  setIndex,
  toggleComment,
  wrap,
  type Destination,
  type EditorIndex,
} from "./extensions";

const INDEX: EditorIndex = {
  ...EMPTY_INDEX,
  bib: [
    { key: "curie1898", type: "article", title: "Sur une substance nouvelle", authors: "Curie et al.", year: "1898", file: "refs.bib", line: 1 },
    { key: "knuth84", type: "book", title: "The TeXbook", authors: "Knuth", year: "1984", file: "refs.bib", line: 9 },
  ],
  labels: [{ key: "sec:methods", file: "sections/methods.tex", context: "Methods" }],
  files: ["sections/methods.tex", "figures/plot.png", "refs.bib"],
};

let views: EditorView[] = [];
afterEach(() => {
  for (const view of views) view.destroy();
  views = [];
});

function editor(doc: string, cursor = doc.length, extensions: Extension = []) {
  const view = new EditorView({
    parent: document.body,
    state: EditorState.create({ doc, selection: { anchor: cursor }, extensions: [indexField.init(() => INDEX), extensions] }),
  });
  views.push(view);
  return view;
}

function complete(doc: string, explicit = false): CompletionResult | null {
  const state = EditorState.create({ doc, extensions: [indexField.init(() => INDEX)] });
  return latexCompletions(new CompletionContext(state, doc.length, explicit));
}

const labels = (result: CompletionResult | null) => result?.options.map((option) => option.label) ?? [];

describe("completion", () => {
  it("offers slash commands by name, alias and title, replacing the slash", () => {
    const result = complete("Some text\n/fig");
    expect(result?.from).toBe(10);
    expect(labels(result)).toEqual(["/figure", "/subfig"]);
    expect(labels(complete("/image"))).toEqual(["/figure"]);
    expect(labels(complete("(/eq"))).toContain("/eq");
    expect(complete("km/h")).toBeNull();
    expect(complete("/zzz")).toBeNull();
  });

  it("offers citations by key, author, year or title, after the last comma", () => {
    expect(labels(complete("\\cite{"))).toEqual(["curie1898", "knuth84"]);
    expect(labels(complete("\\citep[p.~3]{curie1898, tex"))).toEqual(["knuth84"]);
    expect(complete("\\cite{a, 1898")?.from).toBe("\\cite{a, ".length);
    const options = citationOptions(EditorState.create({ doc: "\\bibitem{local} Local paper.", extensions: indexField.init(() => INDEX) }), "local");
    expect(options.map((option) => [option.label, option.detail, option.info])).toEqual([["local", "bibitem", "Local paper."]]);
  });

  it("offers labels from this file and the others for \\ref", () => {
    const result = complete("\\label{fig:a}\\ref{");
    expect(result?.options.map((option) => [option.label, option.detail])).toEqual([
      ["fig:a", "line 1"],
      ["sec:methods", "sections/methods.tex"],
    ]);
  });

  it("offers project files for \\input, \\includegraphics and \\bibliography", () => {
    expect(labels(complete("\\input{"))).toEqual(["sections/methods"]);
    expect(labels(complete("\\includegraphics[width=3cm]{fig"))).toEqual(["figures/plot.png"]);
    expect(labels(complete("\\bibliography{"))).toEqual(["refs"]);
    expect(labels(complete("\\addbibresource{"))).toEqual(["refs.bib"]);
  });

  it("offers environments and commands", () => {
    expect(labels(complete("\\begin{eq"))).toContain("equation");
    expect(labels(complete("\\sec"))).toContain("section");
    expect(complete("plain words")).toBeNull();
    expect(labels(complete("\\", true))).toContain("alpha");
  });
});

describe("formatting", () => {
  it("wraps a selection, and unwraps it again", () => {
    const view = editor("make this bold", 0);
    view.dispatch({ selection: EditorSelection.range(5, 9) });
    wrap("\\textbf{", "}")(view);
    expect(view.state.doc.toString()).toBe("make \\textbf{this} bold");
    expect(view.state.sliceDoc(view.state.selection.main.from, view.state.selection.main.to)).toBe("this");
    wrap("\\textbf{", "}")(view);
    expect(view.state.doc.toString()).toBe("make this bold");
  });

  it("puts the cursor inside an empty wrap, and does nothing when read-only", () => {
    const view = editor("x", 1);
    wrap("$", "$")(view);
    expect(view.state.doc.toString()).toBe("x$$");
    expect(view.state.selection.main.head).toBe(2);
    const locked = editor("x", 1, EditorState.readOnly.of(true));
    expect(wrap("$", "$")(locked)).toBe(false);
    expect(toggleComment(locked)).toBe(false);
  });

  it("comments and uncomments the selected lines", () => {
    const view = editor("one\n\ntwo", 0);
    view.dispatch({ selection: EditorSelection.range(0, 8) });
    toggleComment(view);
    expect(view.state.doc.toString()).toBe("% one\n\n% two");
    view.dispatch({ selection: EditorSelection.range(0, view.state.doc.length) });
    toggleComment(view);
    expect(view.state.doc.toString()).toBe("one\n\ntwo");
    const blank = editor("", 0);
    toggleComment(blank);
    expect(blank.state.doc.toString()).toBe("% ");
  });

  it("inserts \\cite{} and opens the picker, or just opens it inside one", () => {
    const view = editor("See ", 4, autocompletion());
    insertCitation(view);
    expect(view.state.doc.toString()).toBe("See \\cite{}");
    expect(view.state.selection.main.head).toBe(10);
    const inside = editor("\\cite{a,", 8, autocompletion());
    insertCitation(inside);
    expect(inside.state.doc.toString()).toBe("\\cite{a,");
    expect(insertCitation(editor("x", 1, EditorState.readOnly.of(true)))).toBe(false);
  });

  it("inserts slash-command blocks by name", () => {
    const view = editor("", 0);
    expect(insertSnippet(view, "eq")).toBe(true);
    expect(view.state.doc.toString()).toBe("\\begin{equation}\n  E = mc^2\n  \\label{eq:name}\n\\end{equation}\n");
    expect(view.state.sliceDoc(view.state.selection.main.from, view.state.selection.main.to)).toBe("E = mc^2");
    expect(insertSnippet(view, "nope")).toBe(false);
    const cite = editor("", 0, autocompletion());
    insertSnippet(cite, "cite");
    expect(cite.state.doc.toString()).toBe("\\cite{}");
  });
});

describe("references", () => {
  it("jumps to a label in the same document", () => {
    const view = editor("\\section{A}\\label{sec:a}\nsee \\ref{sec:a}", 0);
    const away = vi.fn(() => true);
    expect(followReference(view, view.state.doc.length - 3, away)).toBe(true);
    expect(view.state.sliceDoc(view.state.selection.main.from, view.state.selection.main.to)).toBe("sec:a");
    expect(away).not.toHaveBeenCalled();
    expect(followReference(view, 0, away)).toBe(false);
  });

  it("hands anything elsewhere to the page", () => {
    const view = editor("\\ref{sec:methods} \\cite{knuth84} \\input{sections/methods} \\includegraphics{figures/plot}", 0);
    const away = vi.fn<(destination: Destination) => boolean>(() => true);
    const doc = view.state.doc.toString();
    for (const key of ["sec:methods}", "knuth84", "sections/methods", "figures/plot"]) followReference(view, doc.indexOf(key) + 1, away);
    expect(away.mock.calls.map(([destination]) => destination)).toEqual([
      { kind: "label", key: "sec:methods" },
      { kind: "cite", key: "knuth84" },
      { kind: "file", path: "sections/methods", reference: "input" },
      { kind: "file", path: "figures/plot", reference: "graphics" },
    ]);
  });

  it("describes what a reference points at", () => {
    const state = EditorState.create({ doc: "\\section{Intro}\\label{sec:i}", extensions: indexField.init(() => INDEX) });
    const at = (kind: "ref" | "cite" | "input", key: string) => describeReference(state, { kind, key, from: 0, to: 0 });
    expect(at("ref", "sec:i")).toBe("Line 1: Intro");
    expect(at("ref", "sec:methods")).toBe("sections/methods.tex: Methods");
    expect(at("ref", "nope")).toBe("No \\label{nope} yet");
    expect(at("cite", "knuth84")).toBe("Knuth (1984) · The TeXbook");
    expect(at("cite", "nope")).toBe("No entry “nope” in your bibliography");
    expect(at("input", "a.tex")).toBe("Open a.tex");
  });

  it("follows Ctrl-click on a reference", () => {
    const away = vi.fn(() => true);
    const view = editor("\\cite{knuth84}", 0, definitions(away));
    view.posAtCoords = () => 8;
    const click = new MouseEvent("mousedown", { ctrlKey: true, button: 0, bubbles: true, cancelable: true });
    view.contentDOM.dispatchEvent(click);
    expect(away).toHaveBeenCalledWith({ kind: "cite", key: "knuth84" });
    expect(click.defaultPrevented).toBe(true);
    const plain = new MouseEvent("mousedown", { button: 0, bubbles: true, cancelable: true });
    view.contentDOM.dispatchEvent(plain);
    expect(away).toHaveBeenCalledTimes(1);
  });
});

describe("checks", () => {
  it("lints the source and the last compile's errors, and refreshes when the index changes", async () => {
    const view = editor("\\ref{gone}\n\\begin{x}", 0, latexChecks());
    forceLinting(view);
    await vi.waitFor(() => expect(problemCounts(view.state)).toEqual({ errors: 1, warnings: 1 }));
    view.dispatch({ effects: setIndex.of({ ...INDEX, compileErrors: ["line 1: Undefined control sequence."] }) });
    await vi.waitFor(() => expect(problemCounts(view.state)).toEqual({ errors: 2, warnings: 1 }));
  });
});

describe("math, focus and figures", () => {
  it("knows the math under the cursor", () => {
    const view = editor("a $x$ b", 3);
    expect(mathAtCursor(view.state)?.tex).toBe("x");
    view.dispatch({ selection: { anchor: 7 } });
    expect(mathAtCursor(view.state)).toBeNull();
  });

  it("fades lines outside the current paragraph in focus mode", () => {
    const view = editor("first\nparagraph\n\nsecond\nparagraph", 2, focusMode);
    const dimmed = () => [...view.contentDOM.querySelectorAll(".cm-line")].map((line) => line.classList.contains("cm-dimmed"));
    expect(dimmed()).toEqual([false, false, true, true, true]);
    view.dispatch({ selection: { anchor: view.state.doc.length } });
    expect(dimmed()).toEqual([true, true, true, false, false]);
  });

  it("keeps only images pdfLaTeX can draw", () => {
    const list = [new File(["x"], "a.png", { type: "image/png" }), new File(["x"], "b.gif", { type: "image/gif" }), new File(["x"], "c.PDF")];
    expect(imageFiles(list as unknown as FileList).map((file) => file.name)).toEqual(["a.png", "c.PDF"]);
    expect(imageFiles(null)).toEqual([]);
  });

  it("inserts a figure on its own line and loads graphicx when needed", () => {
    const view = editor("\\documentclass{article}\n\\begin{document}\nText here.\n\\end{document}", 0);
    insertFigures(view, view.state.doc.toString().indexOf("here"), ["figures/plot.png"]);
    const doc = view.state.doc.toString();
    expect(doc).toContain("\\documentclass{article}\n\\usepackage{graphicx}\n\\begin{document}");
    expect(doc).toContain("Text here.\n\\begin{figure}[htbp]");
    expect(doc).toContain("\\includegraphics[width=0.8\\linewidth]{figures/plot}");
    expect(view.state.sliceDoc(view.state.selection.main.from, view.state.selection.main.to)).toBe("Caption");
  });

  it("inserts several figures as plain blocks, and nothing for none", () => {
    const view = editor("\\usepackage{graphicx}\n\n", 22);
    insertFigures(view, 22, []);
    expect(view.state.doc.toString()).toBe("\\usepackage{graphicx}\n\n");
    insertFigures(view, 22, ["a.png", "b.jpg"]);
    const doc = view.state.doc.toString();
    expect(doc.match(/\\begin\{figure\}/g)).toHaveLength(2);
    expect(doc).toContain("\\caption{Caption}");
    expect(doc.match(/usepackage/g)).toHaveLength(1);
  });
});

describe("index state", () => {
  it("replaces the index with setIndex and keeps it otherwise", () => {
    const view = editor("x", 0);
    expect(view.state.field(indexField)).toBe(INDEX);
    view.dispatch({ changes: { from: 0, insert: "y" } });
    expect(view.state.field(indexField)).toBe(INDEX);
    view.dispatch({ effects: setIndex.of(EMPTY_INDEX) });
    expect(view.state.field(indexField)).toBe(EMPTY_INDEX);
    expect(currentCompletions(view.state)).toEqual([]);
  });
});
