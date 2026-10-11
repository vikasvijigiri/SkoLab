import { act, fireEvent, screen, waitFor, within } from "@testing-library/react";
import { EditorView } from "@codemirror/view";
import { beforeEach, describe, expect, it, vi } from "vitest";
import type { FakeBackend, FakeCompileInput } from "../test/fakeBackend";
import { renderApp, stubBackend } from "../test/renderApp";

vi.mock("../editor/PdfPreview", () => ({
  PdfPreview: ({ pdf }: { pdf: Uint8Array }) => <p data-testid="pdf">{new TextDecoder().decode(pdf)}</p>,
}));

const ADA = "google-ada";
const SOURCE = String.raw`\documentclass{article}
\begin{document}
\section{Introduction}\label{sec:intro}
Words in the introduction, see \ref{sec:intro} and \ref{fig:missing}.
\section{Methods}
We cite \cite{knuth84} and use $x^2$.
\end{document}
`;

let backend: FakeBackend;
let compiles: FakeCompileInput[];

function mainView() {
  const host = screen.getAllByTestId("latex-editor").find((element) => element.shadowRoot?.querySelector('[aria-label="LaTeX source"]'));
  const dom = host?.shadowRoot?.querySelector<HTMLElement>(".cm-editor");
  const view = dom ? EditorView.findFromDOM(dom) : null;
  if (!view) throw new Error("no main editor");
  return view;
}

// Commands run a frame after the bar closes, and move the cursor a frame after that.
const nextFrame = () => act(() => new Promise((resolve) => requestAnimationFrame(() => requestAnimationFrame(() => resolve(undefined)))));

async function open(options: { source?: string; files?: Record<string, string | Uint8Array | null>; role?: "viewer" } = {}) {
  const id = backend.seed(options.role ? "grace" : ADA, "Paper", { source: options.source ?? SOURCE, ...(options.role ? { members: { [ADA]: options.role } } : {}) });
  for (const [path, content] of Object.entries(options.files ?? {})) backend.addFile(id, path, content);
  const rendered = await renderApp(`/editor/${id}`, async (auth) => {
    await auth.signInWithGoogle();
  });
  await screen.findByTestId("pdf", {}, { timeout: 5000 });
  await screen.findByRole("list", { name: "Project files" });
  return { id, ...rendered };
}

function press(key: string, init: KeyboardEventInit = {}, target: Element = document.body) {
  fireEvent.keyDown(target, { key, ...init });
}

async function runCommand(query: string) {
  press("k", { ctrlKey: true });
  const input = await screen.findByRole("combobox", { name: "Search commands" });
  fireEvent.change(input, { target: { value: query } });
  fireEvent.keyDown(input, { key: "Enter" });
  await waitFor(() => expect(screen.queryByRole("combobox", { name: "Search commands" })).not.toBeInTheDocument());
  await nextFrame();
}

describe("editor tools", () => {
  beforeEach(() => {
    backend = stubBackend().backend;
    compiles = [];
    backend.setCompiler((input) => {
      compiles.push(input);
      return { status: 200, body: { status: "compiled", pdf_base64: btoa("%PDF tools"), log: "" } };
    });
  });

  it("opens the command bar with Ctrl+K or its button, and switches the layout", async () => {
    const { id } = await open();
    expect(screen.getByRole("button", { name: "Source and PDF" })).toHaveAttribute("aria-pressed", "true");
    await runCommand("pdf only");
    expect(screen.getByRole("button", { name: "PDF only" })).toHaveAttribute("aria-pressed", "true");
    expect(localStorage.getItem(`skolab.layout.${id}`)).toBe('"pdf"');

    fireEvent.click(screen.getByRole("button", { name: "Search or run a command" }));
    expect(await screen.findByRole("dialog", { name: "Command bar" })).toBeInTheDocument();
    fireEvent.keyDown(screen.getByRole("combobox"), { key: "Escape" });
    expect(screen.queryByRole("dialog", { name: "Command bar" })).not.toBeInTheDocument();

    press("2", { altKey: true, code: "Digit2" });
    expect(screen.getByRole("button", { name: "Source only" })).toHaveAttribute("aria-pressed", "true");
    fireEvent.click(screen.getByRole("button", { name: "Source and PDF" }));
    expect(screen.getByRole("button", { name: "Source and PDF" })).toHaveAttribute("aria-pressed", "true");
  });

  it("jumps to sections and inserts blocks from the command bar", async () => {
    await open();
    await runCommand("Methods");
    expect(mainView().state.doc.lineAt(mainView().state.selection.main.head).number).toBe(5);
    await runCommand("insert table");
    expect(mainView().state.doc.toString()).toContain("\\begin{tabular}{lcc}");
    await runCommand("bold");
    expect(mainView().state.doc.toString()).toMatch(/\\textbf\{[^}]*\}/);
  });

  it("shows the shortcut list with ?, except while typing", async () => {
    await open();
    press("?", {}, mainView().contentDOM);
    expect(screen.queryByRole("dialog", { name: "Keyboard shortcuts" })).not.toBeInTheDocument();
    press("?");
    const sheet = await screen.findByRole("dialog", { name: "Keyboard shortcuts" });
    expect(within(sheet).getByText("Open the command bar")).toBeInTheDocument();
    fireEvent.change(within(sheet).getByRole("searchbox", { name: "Search shortcuts" }), { target: { value: "focus" } });
    expect(within(sheet).getAllByRole("listitem")).toHaveLength(1);
    fireEvent.change(within(sheet).getByRole("searchbox"), { target: { value: "nothing like this" } });
    expect(within(sheet).getByText(/No shortcut matches/)).toBeInTheDocument();
  });

  it("enters and leaves focus mode", async () => {
    await open();
    press(".", { ctrlKey: true });
    expect(await screen.findByRole("button", { name: /Leave focus/ })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Search or run a command" })).not.toBeInTheDocument();
    expect(screen.queryByRole("contentinfo", { name: "Status" })).not.toBeInTheDocument();
    press("Escape");
    expect(screen.queryByRole("button", { name: /Leave focus/ })).not.toBeInTheDocument();
    await runCommand("focus mode");
    fireEvent.click(await screen.findByRole("button", { name: /Leave focus/ }));
    expect(screen.getByRole("contentinfo", { name: "Status" })).toBeInTheDocument();
  });

  it("outlines the paper as you type, and jumps to a section", async () => {
    await open();
    press("o", { ctrlKey: true, shiftKey: true });
    const outline = await screen.findByRole("navigation", { name: "Outline" });
    expect(within(outline).getAllByRole("button").map((button) => button.textContent)).toEqual([expect.stringMatching(/^Introduction/) as string, expect.stringMatching(/^Methods/) as string]);
    act(() => mainView().dispatch({ changes: { from: mainView().state.doc.length, insert: "\\section{Results}\n" } }));
    expect(await within(outline).findByRole("button", { name: /^Results/ })).toBeInTheDocument();
    fireEvent.click(within(outline).getByRole("button", { name: /^Methods/ }));
    await nextFrame();
    expect(mainView().state.doc.lineAt(mainView().state.selection.main.head).number).toBe(5);
    fireEvent.click(screen.getByRole("tab", { name: "Project" }));
    expect(screen.getByRole("list", { name: "Project files" })).toBeInTheDocument();
  });

  it("counts problems in the status bar and steps to them", async () => {
    await open({ files: { "refs.bib": "@book{knuth84, title={The TeXbook}, author={Knuth}, year=1984}" } });
    const status = screen.getByRole("contentinfo", { name: "Status" });
    const problems = await within(status).findByRole("button", { name: "1 warning" }, { timeout: 3000 });
    mainView().dispatch({ selection: { anchor: 0 } });
    fireEvent.click(problems);
    await nextFrame();
    const { from, to } = mainView().state.selection.main;
    expect(mainView().state.sliceDoc(from, to)).toBe("fig:missing");
    expect(within(status).getByText(/Ln \d+, Col \d+/)).toBeInTheDocument();
    expect(within(status).getByTitle("Current section")).toHaveTextContent("Introduction");
  });

  it("opens the .bib file holding a citation with F12, and says when a file is missing", async () => {
    await open({ files: { "refs.bib": "@book{knuth84, title={The TeXbook}}" } });
    const view = mainView();
    // Wait for the project's files to be read.
    await waitFor(() => {
      view.dispatch({ selection: { anchor: view.state.doc.toString().indexOf("knuth84") + 2 } });
      fireEvent.keyDown(view.contentDOM, { key: "F12" });
      expect(screen.getByText("Opened refs.bib")).toBeInTheDocument();
    });
    expect(screen.getByText("refs.bib", { selector: "[title='Open file']" })).toBeInTheDocument();

    await runCommand("main.tex");
    act(() => view.dispatch({ changes: { from: view.state.doc.length, insert: "\\input{chapters/none}" } }));
    view.dispatch({ selection: { anchor: view.state.doc.length - 3 } });
    fireEvent.keyDown(view.contentDOM, { key: "F12" });
    expect(await screen.findByText("There is no chapters/none in the project.")).toBeInTheDocument();
  });

  it("uploads a pasted image to figures/ and inserts a figure", async () => {
    const { id } = await open({ files: { "figures/image.png": new Uint8Array([1]) } });
    const view = mainView();
    view.dispatch({ selection: { anchor: view.state.doc.toString().indexOf("\\end{document}") } });
    const paste = new Event("paste", { bubbles: true, cancelable: true });
    Object.defineProperty(paste, "clipboardData", { value: { files: [new File([new Uint8Array([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 1])], "plot.png", { type: "image/png" })], getData: () => "" } });
    view.contentDOM.dispatchEvent(paste);
    expect(paste.defaultPrevented).toBe(true);
    await waitFor(() => expect(view.state.doc.toString()).toContain("\\includegraphics[width=0.8\\linewidth]{figures/plot}"));
    expect(view.state.doc.toString()).toContain("\\usepackage{graphicx}");
    expect(Object.keys(backend.files(id))).toContain("figures/plot.png");
    expect(await screen.findByText("Added figures/plot.png as a figure")).toBeInTheDocument();
  });

  it("drops images onto the source under fresh names, and says when an upload is refused", async () => {
    const { id } = await open();
    const view = mainView();
    const png = new File([new Uint8Array([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 1])], "image.png", { type: "image/png" });
    const over = new Event("dragover", { bubbles: true, cancelable: true });
    Object.defineProperty(over, "dataTransfer", { value: { types: ["Files"], files: [] } });
    view.contentDOM.dispatchEvent(over);
    expect(view.dom.classList.contains("cm-drop-target")).toBe(true);
    view.contentDOM.dispatchEvent(new Event("dragleave", { bubbles: true }));
    expect(view.dom.classList.contains("cm-drop-target")).toBe(false);

    const drop = new Event("drop", { bubbles: true, cancelable: true });
    Object.assign(drop, { clientX: 0, clientY: 0 });
    Object.defineProperty(drop, "dataTransfer", { value: { types: ["Files"], files: [png, png] } });
    view.contentDOM.dispatchEvent(drop);
    await waitFor(() => expect(Object.keys(backend.files(id)).filter((path) => path.startsWith("figures/pasted-"))).toHaveLength(2));
    expect(await screen.findByText("Added 2 figures")).toBeInTheDocument();
    await waitFor(() => expect(view.state.doc.toString().match(/\\begin\{figure\}/g)).toHaveLength(2));

    const bad = new Event("paste", { bubbles: true, cancelable: true });
    Object.defineProperty(bad, "clipboardData", { value: { files: [new File(["not an image"], "fake.png", { type: "image/png" })], getData: () => "" } });
    view.contentDOM.dispatchEvent(bad);
    expect(await screen.findByText(/Projects take text files/)).toBeInTheDocument();
  });

  it("resizes the panes from the keyboard and remembers it", async () => {
    const { id } = await open();
    const divider = screen.getByRole("separator", { name: "Resize the source and PDF panes" });
    expect(divider).toHaveAttribute("aria-valuenow", "50");
    fireEvent.keyDown(divider, { key: "ArrowRight" });
    expect(divider).toHaveAttribute("aria-valuenow", "55");
    fireEvent.keyDown(divider, { key: "End" });
    expect(divider).toHaveAttribute("aria-valuenow", "80");
    fireEvent.keyDown(divider, { key: "ArrowLeft" });
    fireEvent.keyDown(divider, { key: "x" });
    expect(localStorage.getItem(`skolab.split.${id}`)).toBe("0.75");
    fireEvent.doubleClick(divider);
    expect(divider).toHaveAttribute("aria-valuenow", "50");
  });

  it("changes the theme from the command bar", async () => {
    await open();
    await runCommand("theme dark");
    expect(document.documentElement.dataset.theme).toBe("dark");
    await runCommand("match the device");
    expect(document.documentElement.dataset.theme).toBeUndefined();
  });

  it("compiles on its own after a pause when auto-compile is on", async () => {
    await open();
    expect(compiles).toHaveLength(1);
    fireEvent.click(screen.getByRole("checkbox", { name: "Auto-compile" }));
    act(() => mainView().dispatch({ changes: { from: 0, insert: "% auto\n" } }));
    await waitFor(() => expect(compiles).toHaveLength(2), { timeout: 6000 });
    expect(compiles[1]?.latex_source.startsWith("% auto")).toBe(true);
    expect(localStorage.getItem("skolab.autoCompile")).toBe("true");
  });

  it("offers no editing commands to viewers", async () => {
    await open({ role: "viewer" });
    press("k", { ctrlKey: true });
    const input = await screen.findByRole("combobox");
    fireEvent.change(input, { target: { value: "insert" } });
    expect(screen.queryByRole("option", { name: /Insert table/ })).not.toBeInTheDocument();
    expect(screen.queryByRole("checkbox", { name: "Auto-compile" })).not.toBeInTheDocument();
  });
});
