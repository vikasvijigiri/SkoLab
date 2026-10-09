import { act, fireEvent, screen, waitFor } from "@testing-library/react";
import { beforeAll, describe, expect, it, vi } from "vitest";
import { EditorView } from "@codemirror/view";
import type { FakeAnswer, FakeBackend, FakeCompileInput } from "../test/fakeBackend";
import { renderApp, stubBackend } from "../test/renderApp";

// pdf.js needs a real canvas; the browser tests (e2e/editor.spec.ts) cover it.
vi.mock("../editor/PdfPreview", () => ({
  PdfPreview: ({ pdf }: { pdf: Uint8Array }) => <p data-testid="pdf">{new TextDecoder().decode(pdf)}</p>,
}));

const ADA = "google-ada";
const SOURCE = "\\documentclass{article}\\begin{document}Hi\\end{document}";

let backend: FakeBackend;

function seed(source = SOURCE, options: { templateId?: string | null; owner?: string; role?: "editor" | "viewer" | "commenter" } = {}) {
  const owner = options.owner ?? ADA;
  return {
    id: backend.seed(owner, "My paper", {
      source,
      templateId: options.templateId === undefined ? "ams-article" : options.templateId,
      ...(options.role ? { members: { [ADA]: options.role } } : {}),
    }),
    source,
  };
}

/** What the project compile answers, in turn (then a compiled PDF). */
function compileAnswers(...answers: (() => FakeAnswer)[]) {
  const calls: FakeCompileInput[] = [];
  const stub = stubBackend();
  backend = stub.backend;
  backend.setCompiler((input) => {
    calls.push(input);
    const next = answers.shift();
    return next ? next() : { status: 200, body: { status: "compiled", pdf_base64: btoa("%PDF again") } };
  });
  return { calls, fetchMock: stub.fetchMock };
}

/** Types at the end of the CodeMirror document. */
function typeInSource(text: string) {
  const dom = screen.getByTestId("latex-editor").shadowRoot?.querySelector<HTMLElement>(".cm-editor");
  const view = dom ? EditorView.findFromDOM(dom) : null;
  if (!view) throw new Error("no editor");
  act(() => view.dispatch({ changes: { from: view.state.doc.length, insert: text } }));
}

async function openEditor(id: string) {
  return renderApp(`/editor/${id}`, async (auth) => {
    await auth.signInWithGoogle();
  });
}

describe("editor", () => {
  beforeAll(() => {
    // jsdom has no layout; CodeMirror measures text ranges.
    Range.prototype.getClientRects = () => [] as unknown as DOMRectList;
    Range.prototype.getBoundingClientRect = () => new DOMRect();
  });
  it("compiles on open and shows the PDF", async () => {
    const { calls } = compileAnswers(() => ({ status: 200, body: { status: "compiled", pdf_base64: btoa("%PDF-1.7 first"), log: "" } }));
    const doc = seed();
    await openEditor(doc.id);
    // The first test also loads the editor's code.
    expect(await screen.findByTestId("pdf", {}, { timeout: 5000 })).toHaveTextContent("%PDF-1.7 first");
    expect(calls).toEqual([{ latex_source: doc.source, engine: "pdflatex", files: [] }]);
    expect(screen.getByDisplayValue("My paper")).toBeInTheDocument();
    expect(screen.getByText("Template: AMS Journal Article (amsart) (LPPL 1.3c)")).toBeInTheDocument();
    expect(screen.getByText("All changes saved")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "PDF" })).toBeEnabled();
  });

  it("lists compile errors, keeps the last good PDF and shows the log", async () => {
    compileAnswers(
      () => ({ status: 200, body: { status: "compiled", pdf_base64: btoa("%PDF good") } }),
      () => ({ status: 200, body: { status: "error", errors: ["line 3: Undefined control sequence.", "Something else"], log: "full log" } }),
    );
    const doc = seed();
    const { user } = await openEditor(doc.id);
    await screen.findByTestId("pdf");
    await user.click(screen.getByRole("button", { name: "Compile" }));
    expect(await screen.findByText(/Compilation failed; showing the last good PDF/)).toBeInTheDocument();
    expect(screen.getByTestId("pdf")).toHaveTextContent("%PDF good");
    expect(screen.getByText("Something else")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "line 3: Undefined control sequence." }));
    expect(screen.getByText("Compiler log")).toBeInTheDocument();
  });

  it("explains a compile that could not run", async () => {
    compileAnswers(
      () => ({ status: 429, body: { code: "compile_in_progress", error: "busy" } }),
      () => ({ status: 429, body: { code: "quota_exceeded", error: "spent" } }),
      () => ({ status: 503, body: { code: "compile_unavailable", error: "down" } }),
      () => ({ status: 200, body: { status: "timeout", errors: ["Compilation exceeded the 20 second limit."], log: "" } }),
    );
    const doc = seed();
    const { user } = await openEditor(doc.id);
    expect(await screen.findByRole("alert")).toHaveTextContent("A compile is already running");
    await user.click(screen.getByRole("button", { name: "Compile" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("compile limit");
    await user.click(screen.getByRole("button", { name: "Compile" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("compiler is unavailable");
    await user.click(screen.getByRole("button", { name: "Compile" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("20 second limit");
    expect(screen.getByRole("button", { name: "PDF" })).toBeDisabled();
  });

  it("refuses a source over the compile limit without calling the API", async () => {
    const { calls } = compileAnswers();
    const doc = seed("x".repeat(100_001));
    await openEditor(doc.id);
    expect(await screen.findByRole("alert")).toHaveTextContent("up to 100,000 characters");
    expect(calls).toEqual([]);
  });

  it("saves edits to the account, and the owner's title", async () => {
    compileAnswers();
    const doc = seed();
    const { user, unmount } = await openEditor(doc.id);
    await screen.findByTestId("pdf");
    typeInSource("\n% first");
    expect(screen.getByText("Saving…")).toBeInTheDocument();
    await waitFor(() => expect(backend.document(doc.id)?.source).toBe(`${SOURCE}\n% first`), { timeout: 3000 });
    expect(backend.document(doc.id)?.version).toBe(2);
    expect(screen.getByText("All changes saved")).toBeInTheDocument();

    const title = screen.getByLabelText("Document title");
    await user.clear(title);
    await user.type(title, "Renamed");
    await waitFor(() => expect(backend.titles()).toEqual(["Renamed"]), { timeout: 3000 });

    // Leaving right after typing still saves.
    typeInSource("\n% second");
    unmount();
    await waitFor(() => expect(backend.document(doc.id)?.source).toBe(`${SOURCE}\n% first\n% second`));
    expect(backend.document(doc.id)?.version).toBe(3);
  });

  it("stops on a conflicting save and lets the user load theirs or keep their own", async () => {
    compileAnswers();
    const doc = seed();
    const { user } = await openEditor(doc.id);
    await screen.findByTestId("pdf");
    backend.saveAs("grace", doc.id, "grace's text");
    typeInSource("\n% mine");
    expect(await screen.findByText("Someone else saved this document while you were editing.", {}, { timeout: 3000 })).toBeInTheDocument();
    expect(backend.document(doc.id)?.source).toBe("grace's text");

    await user.click(screen.getByRole("button", { name: "Keep mine" }));
    await waitFor(() => expect(backend.document(doc.id)?.source).toBe(`${SOURCE}\n% mine`));
    expect(backend.document(doc.id)?.version).toBe(3);
    expect(screen.queryByText(/Someone else saved/)).not.toBeInTheDocument();

    backend.saveAs("grace", doc.id, "grace again");
    typeInSource("!");
    await user.click(await screen.findByRole("button", { name: "Load their version" }, { timeout: 3000 }));
    await waitFor(() => expect(screen.getByTestId("latex-editor").shadowRoot?.textContent).toContain("grace again"));
    expect(screen.queryByText(/Someone else saved/)).not.toBeInTheDocument();
    expect(backend.document(doc.id)?.source).toBe("grace again");
  });

  it("is read-only for viewers, without a title field or saves", async () => {
    compileAnswers();
    const doc = seed(SOURCE, { owner: "grace", role: "viewer" });
    await openEditor(doc.id);
    await screen.findByTestId("pdf");
    expect(screen.getByText(/Ask its owner for edit access/)).toBeInTheDocument();
    expect(screen.getByText("View only")).toBeInTheDocument();
    expect(screen.queryByLabelText("Document title")).not.toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "My paper" })).toBeInTheDocument();
    const content = screen.getByTestId("latex-editor").shadowRoot?.querySelector(".cm-content");
    expect(content).toHaveAttribute("aria-readonly", "true");
    expect(backend.requests.filter((request) => request.method === "PUT")).toEqual([]);
  });

  it("lets editors save but not rename, and stops when edit access is taken away", async () => {
    compileAnswers();
    const doc = seed(SOURCE, { owner: "grace", role: "editor" });
    await openEditor(doc.id);
    await screen.findByTestId("pdf");
    expect(screen.queryByLabelText("Document title")).not.toBeInTheDocument();
    typeInSource("\n% editor");
    await waitFor(() => expect(backend.document(doc.id)?.version).toBe(2), { timeout: 3000 });

    backend.setRole(doc.id, ADA, "viewer");
    typeInSource("!");
    expect(await screen.findByText(/You can no longer edit this document/, {}, { timeout: 3000 })).toBeInTheDocument();
    expect(screen.getByText("Not saved")).toBeInTheDocument();
  });

  it("keeps the edit and retries when a save fails for a moment", async () => {
    compileAnswers();
    const doc = seed();
    await openEditor(doc.id);
    await screen.findByTestId("pdf");
    backend.failNext("PUT", /document$/, { status: 503, body: { code: "unavailable", error: "down" } });
    typeInSource("\n% retried");
    expect(await screen.findByText(/Not saved: SkoLab couldn't save just now/, {}, { timeout: 3000 })).toBeInTheDocument();
    typeInSource("!");
    await waitFor(() => expect(backend.document(doc.id)?.source).toBe(`${SOURCE}\n% retried!`), { timeout: 3000 });
    expect(screen.getByText("All changes saved")).toBeInTheDocument();
  });

  it("refuses to save a document over the limit", async () => {
    compileAnswers();
    const doc = seed("x".repeat(99_999));
    await openEditor(doc.id);
    await screen.findByTestId("pdf");
    typeInSource("yy");
    expect(await screen.findByText(/Not saved: documents can be up to 100,000 characters/, {}, { timeout: 3000 })).toBeInTheDocument();
    expect(backend.document(doc.id)?.version).toBe(1);
  });

  it("switches between source and PDF on phones, zooms, and downloads", async () => {
    compileAnswers();
    const created: Blob[] = [];
    vi.spyOn(URL, "createObjectURL").mockImplementation((blob) => {
      created.push(blob as Blob);
      return "blob:x";
    });
    vi.spyOn(URL, "revokeObjectURL").mockImplementation(() => undefined);
    const click = vi.spyOn(HTMLAnchorElement.prototype, "click").mockImplementation(() => undefined);
    const doc = seed();
    const { user } = await openEditor(doc.id);
    await screen.findByTestId("pdf");
    expect(screen.getByRole("tab", { name: "PDF" })).toHaveAttribute("aria-selected", "true");
    await user.click(screen.getByRole("tab", { name: "Source" }));
    expect(screen.getByRole("tab", { name: "Source" })).toHaveAttribute("aria-selected", "true");

    await user.click(screen.getByRole("button", { name: "Zoom in" }));
    expect(screen.getByText("125%")).toBeInTheDocument();
    for (let i = 0; i < 6; i += 1) fireEvent.click(screen.getByRole("button", { name: "Zoom out" }));
    expect(screen.getByText("50%")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Zoom out" })).toBeDisabled();

    await user.click(screen.getByRole("button", { name: ".tex" }));
    await user.click(screen.getByRole("button", { name: "PDF" }));
    expect(click).toHaveBeenCalledTimes(2);
    expect(created.map((blob) => blob.type)).toEqual(["application/x-tex", "application/pdf"]);
    await act(() => new Promise((resolve) => setTimeout(resolve, 1100)));
  });

  it("says when a document does not exist or isn't shared with you", async () => {
    compileAnswers();
    const doc = seed(SOURCE, { owner: "grace" });
    await openEditor(doc.id);
    expect(await screen.findByRole("heading", { name: "Document not found" })).toBeInTheDocument();
    expect(screen.getByText(/isn't shared with you/)).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Back to your documents" })).toHaveAttribute("href", "/");
  });

  it("offers a retry when the document can't be loaded", async () => {
    compileAnswers();
    const doc = seed();
    backend.failNext("GET", /document$/, { status: 503, body: { code: "unavailable", error: "down" } });
    const { user } = await openEditor(doc.id);
    expect(await screen.findByRole("heading", { name: "Document unavailable" })).toBeInTheDocument();
    expect(screen.getByText("We couldn't open this document.")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Try again" }));
    expect(await screen.findByDisplayValue("My paper")).toBeInTheDocument();
  });
});
