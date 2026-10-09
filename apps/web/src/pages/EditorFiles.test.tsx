import { act, fireEvent, screen, waitFor, within } from "@testing-library/react";
import { beforeAll, beforeEach, describe, expect, it, vi } from "vitest";
import { EditorView } from "@codemirror/view";
import type { FakeBackend, FakeCompileInput } from "../test/fakeBackend";
import { renderApp, stubBackend } from "../test/renderApp";
import { readZip } from "../test/zip";

vi.mock("../editor/PdfPreview", () => ({
  PdfPreview: ({ pdf }: { pdf: Uint8Array }) => <p data-testid="pdf">{new TextDecoder().decode(pdf)}</p>,
}));

const ADA = "google-ada";
const SOURCE = "\\documentclass{article}\n\\begin{document}\n\\input{chapters/intro}\n\\end{document}\n";
const PNG = new Uint8Array([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 1, 2, 3]);

let backend: FakeBackend;
let compiles: FakeCompileInput[];

function editorView(label: string) {
  const host = screen.getAllByTestId("latex-editor").find((element) => element.shadowRoot?.querySelector(`[aria-label="${label}"]`));
  const dom = host?.shadowRoot?.querySelector<HTMLElement>(".cm-editor");
  const view = dom ? EditorView.findFromDOM(dom) : null;
  if (!view) throw new Error(`no editor ${label}`);
  return view;
}

/** Waits for the code editor with this label (it lives in a shadow root, out of reach of label queries). */
async function findEditor(label: string) {
  return waitFor(() => editorView(label), { timeout: 3000 });
}

function typeIn(label: string, text: string) {
  const view = editorView(label);
  act(() => view.dispatch({ changes: { from: view.state.doc.length, insert: text } }));
}

function tree() {
  return screen.getByRole("list", { name: "Project files" });
}

async function open(options: { role?: "editor" | "viewer"; files?: Record<string, string | Uint8Array | null> } = {}) {
  const owner = options.role ? "grace" : ADA;
  const id = backend.seed(owner, "Thesis", { source: SOURCE, ...(options.role ? { members: { [ADA]: options.role } } : {}) });
  for (const [path, content] of Object.entries(options.files ?? {})) backend.addFile(id, path, content);
  const view = await renderApp(`/editor/${id}`, async (auth) => {
    await auth.signInWithGoogle();
  });
  await screen.findByTestId("pdf", {}, { timeout: 5000 });
  await screen.findByRole("list", { name: "Project files" });
  return { id, ...view };
}

function captureDownloads() {
  const created: Blob[] = [];
  vi.spyOn(URL, "createObjectURL").mockImplementation((blob) => {
    created.push(blob as Blob);
    return "blob:x";
  });
  vi.spyOn(URL, "revokeObjectURL").mockImplementation(() => undefined);
  const names: string[] = [];
  vi.spyOn(HTMLAnchorElement.prototype, "click").mockImplementation(function (this: HTMLAnchorElement) {
    names.push(this.download);
  });
  return { created, names };
}

describe("project files", () => {
  beforeAll(() => {
    Range.prototype.getClientRects = () => [] as unknown as DOMRectList;
    Range.prototype.getBoundingClientRect = () => new DOMRect();
  });
  beforeEach(() => {
    backend = stubBackend().backend;
    compiles = [];
    backend.setCompiler((input) => {
      compiles.push(input);
      return { status: 200, body: { status: "compiled", pdf_base64: btoa("%PDF-1.5 project"), log: "" } };
    });
  });

  it("lists main.tex first, then folders and files, the compiled PDF and the space used", async () => {
    await open({ files: { "figures/plot.png": PNG, "refs.bib": "@book{a,title={A}}", "chapters/intro.tex": "Hello" } });
    const items = within(tree())
      .getAllByRole("button")
      .map((button) => button.textContent)
      .filter((text) => text && !text.startsWith("Actions"));
    expect(items).toEqual(["main.tex (main document)", "chapters", "intro.tex", "figures", "plot.png", "refs.bib", expect.stringMatching(/^output\.pdf Compiled/) as string]);
    expect(screen.getByText(/of 10 MB used · 5 of 200 items/)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /^main\.tex/ })).toHaveAttribute("aria-current", "true");

    // Folders fold away.
    await userClick(screen.getByRole("button", { name: "chapters" }));
    expect(screen.getByRole("button", { name: "chapters" })).toHaveAttribute("aria-expanded", "false");
    expect(within(tree()).queryByRole("button", { name: "intro.tex" })).not.toBeInTheDocument();
    await userClick(screen.getByRole("button", { name: "chapters" }));
    expect(within(tree()).getByRole("button", { name: "intro.tex" })).toBeInTheDocument();
  });

  it("creates folders and files, renames, moves into a folder and deletes a folder with its contents", async () => {
    const { user, id } = await open();
    await user.click(screen.getByRole("button", { name: "New folder" }));
    await user.type(screen.getByLabelText("New folder name"), "figures{Enter}");
    expect(await within(tree()).findByRole("button", { name: "figures" })).toBeInTheDocument();
    expect(screen.getByText("Created figures.")).toBeInTheDocument();

    // A name without an extension becomes a .tex file, which opens.
    await user.click(screen.getByRole("button", { name: "New file" }));
    await user.type(screen.getByLabelText("New file name"), "intro");
    await user.click(screen.getByRole("button", { name: "Create" }));
    await findEditor("Source of intro.tex");
    expect(backend.files(id)).toEqual({ figures: null, "intro.tex": "" });

    // Invalid names are caught before they're sent.
    await user.click(screen.getByRole("button", { name: "New file" }));
    await user.type(screen.getByLabelText("New file name"), "notes.exe");
    await user.click(screen.getByRole("button", { name: "Create" }));
    expect(screen.getByRole("alert")).toHaveTextContent("Text files need a text extension");
    await user.clear(screen.getByLabelText("New file name"));
    await user.type(screen.getByLabelText("New file name"), ".hidden.tex");
    await user.click(screen.getByRole("button", { name: "Create" }));
    expect(screen.getByRole("alert")).toHaveTextContent("Names can't start with a dot.");
    await user.click(screen.getByRole("button", { name: "Cancel" }));

    // Move intro.tex into a new chapters folder.
    await user.click(screen.getByRole("button", { name: "Actions for intro.tex" }));
    await user.click(screen.getByRole("button", { name: "Rename or move" }));
    const rename = screen.getByLabelText("New name or path for intro.tex");
    await user.clear(rename);
    await user.type(rename, "chapters/intro.tex{Enter}");
    await waitFor(() => expect(Object.keys(backend.files(id)).sort()).toEqual(["chapters", "chapters/intro.tex", "figures"]));
    expect(await within(tree()).findByRole("button", { name: "chapters" })).toBeInTheDocument();
    // The open file follows its move.
    await findEditor("Source of chapters/intro.tex");

    // A rename that keeps the name changes nothing; one that changes the type is refused.
    await user.click(screen.getByRole("button", { name: "Actions for chapters/intro.tex" }));
    await user.click(screen.getByRole("button", { name: "Rename or move" }));
    await user.click(screen.getByRole("button", { name: "Save" }));
    expect(screen.queryByLabelText(/New name or path/)).not.toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Actions for chapters/intro.tex" }));
    await user.click(screen.getByRole("button", { name: "Rename or move" }));
    await user.clear(screen.getByLabelText("New name or path for intro.tex"));
    await user.type(screen.getByLabelText("New name or path for intro.tex"), "chapters/intro.png{Enter}");
    expect(screen.getByRole("alert")).toHaveTextContent("Keep the file's type");
    await user.click(screen.getByRole("button", { name: "Cancel" }));

    // Renaming the folder moves what's in it.
    await user.click(screen.getByRole("button", { name: "Actions for chapters" }));
    await user.click(screen.getByRole("button", { name: "Rename or move" }));
    await user.clear(screen.getByLabelText("New name or path for chapters"));
    await user.type(screen.getByLabelText("New name or path for chapters"), "parts{Enter}");
    await waitFor(() => expect(Object.keys(backend.files(id)).sort()).toEqual(["figures", "parts", "parts/intro.tex"]));

    // Deleting a folder asks first, then takes its contents and closes the open file.
    await user.click(await screen.findByRole("button", { name: "Actions for parts" }));
    await user.click(screen.getByRole("button", { name: "Delete" }));
    expect(screen.getByText(/Delete parts and everything in it\?/)).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Confirm deleting parts" }));
    await waitFor(() => expect(backend.files(id)).toEqual({ figures: null }));
    expect(screen.getAllByTestId("latex-editor")).toHaveLength(1);
    expect(screen.getByRole("button", { name: /^main\.tex/ })).toHaveAttribute("aria-current", "true");
  });

  it("uploads files, into a folder too, asks before replacing one, and refuses unknown types", async () => {
    const confirm = vi.spyOn(window, "confirm").mockReturnValueOnce(false).mockReturnValueOnce(true);
    const { user, id } = await open({ files: { figures: null } });
    const picker = screen.getByLabelText("Choose files to upload");

    await user.click(screen.getByRole("button", { name: "Upload files" }));
    await user.upload(picker, new File(["@book{a}"], "refs.bib", { type: "text/x-bibtex" }));
    expect(await screen.findByText("Uploaded refs.bib.")).toBeInTheDocument();
    expect(backend.files(id)["refs.bib"]).toBe("@book{a}");

    await user.click(screen.getByRole("button", { name: "Actions for figures" }));
    await user.click(screen.getByRole("button", { name: "Upload here" }));
    await user.upload(picker, [new File([PNG], "plot.png", { type: "image/png" }), new File([PNG], "fit.png", { type: "image/png" })]);
    expect(await screen.findByText("Uploaded 2 files.")).toBeInTheDocument();
    expect(backend.files(id)["figures/plot.png"]).toEqual(PNG);

    // The same name again: declined, then accepted.
    await user.click(screen.getByRole("button", { name: "Upload files" }));
    await user.upload(picker, new File(["@book{b}"], "refs.bib"));
    await waitFor(() => expect(confirm).toHaveBeenCalledTimes(1));
    expect(backend.files(id)["refs.bib"]).toBe("@book{a}");
    await user.upload(picker, new File(["@book{b}"], "refs.bib"));
    await waitFor(() => expect(backend.files(id)["refs.bib"]).toBe("@book{b}"));

    // The picker only offers known types; a drop can bring anything.
    fireEvent.drop(tree(), { dataTransfer: { types: ["Files"], files: [new File(["MZ"], "tool.exe")] } });
    expect(await screen.findByRole("alert")).toHaveTextContent("tool.exe: Projects take text files");
    expect(backend.files(id)["tool.exe"]).toBeUndefined();
    const big = new File(["x"], "huge.png");
    Object.defineProperty(big, "size", { value: 6 * 1024 * 1024 });
    fireEvent.drop(tree(), { dataTransfer: { types: ["Files"], files: [big] } });
    expect(await screen.findByRole("alert")).toHaveTextContent("huge.png: Files can be up to 5 MB");

    // The server checks the bytes, too.
    await user.upload(picker, new File(["not a png"], "fake.png"));
    expect(await screen.findByRole("alert")).toHaveTextContent("Projects take text files");
  });

  it("uploads files dropped from the computer onto the list or onto a folder", async () => {
    const { id } = await open({ files: { figures: null } });
    const folderRow = screen.getByRole("button", { name: "figures" }).parentElement as HTMLElement;
    const files = [new File([PNG], "drop.png", { type: "image/png" })];
    fireEvent.dragOver(folderRow, { dataTransfer: { types: ["Files"], files } });
    expect(folderRow.className).toContain("ring-2");
    fireEvent.dragLeave(folderRow, { dataTransfer: { types: ["Files"], files } });
    expect(folderRow.className).not.toContain("ring-2");
    fireEvent.drop(folderRow, { dataTransfer: { types: ["Files"], files } });
    await waitFor(() => expect(backend.files(id)["figures/drop.png"]).toEqual(PNG));

    // Text being dragged isn't a file drop.
    fireEvent.dragOver(tree(), { dataTransfer: { types: ["text/plain"], files: [] } });
    fireEvent.drop(tree(), { dataTransfer: { types: ["Files"], files: [new File(["x"], "notes.txt")] } });
    await waitFor(() => expect(backend.files(id)["notes.txt"]).toBe("x"));
  });

  it("shows viewers the files with downloads only", async () => {
    const { created, names } = captureDownloads();
    const { user } = await open({ role: "viewer", files: { "chapters/intro.tex": "Hi", "figures/plot.png": PNG } });
    expect(screen.queryByRole("button", { name: "New file" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Upload files" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Actions for chapters" })).not.toBeInTheDocument();
    expect(screen.queryByText("Drop files here to upload them.")).not.toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "Actions for chapters/intro.tex" }));
    expect(screen.queryByRole("button", { name: "Rename or move" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Delete" })).not.toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Download" }));
    await waitFor(() => expect(names).toEqual(["intro.tex"]));
    expect(created[0]?.type).toBe("text/plain; charset=utf-8");

    // A viewer can read another file but not change it.
    await user.click(screen.getByRole("button", { name: "intro.tex" }));
    const content = (await findEditor("Source of chapters/intro.tex")).contentDOM;
    expect(content).toHaveAttribute("aria-readonly", "true");
    expect(await screen.findByText("View only")).toBeInTheDocument();
    await act(() => new Promise((resolve) => setTimeout(resolve, 1100)));
  });

  it("opens another .tex file, saves it as you type, and says so in the header", async () => {
    const { user, id } = await open({ files: { "chapters/intro.tex": "\\section{Intro}" } });
    await user.click(screen.getByRole("button", { name: "intro.tex" }));
    await findEditor("Source of chapters/intro.tex");
    expect(screen.getByTitle("Open file")).toHaveTextContent("chapters/intro.tex");
    expect(screen.getByRole("button", { name: "intro.tex" })).toHaveAttribute("aria-current", "true");

    typeIn("Source of chapters/intro.tex", "\nMore.");
    expect(await screen.findByText("Saving…")).toBeInTheDocument();
    await waitFor(() => expect(backend.files(id)["chapters/intro.tex"]).toBe("\\section{Intro}\nMore."), { timeout: 3000 });
    expect(await screen.findByText("All changes saved")).toBeInTheDocument();

    // Leaving the file right after typing still saves it.
    typeIn("Source of chapters/intro.tex", " Last.");
    await user.click(screen.getByRole("button", { name: /^main\.tex/ }));
    await waitFor(() => expect(backend.files(id)["chapters/intro.tex"]).toBe("\\section{Intro}\nMore. Last."));
    expect(screen.getByTitle("Open file")).toHaveTextContent("main.tex");
  });

  it("stops on a conflicting save of a file and lets the user load theirs or keep their own", async () => {
    const { user, id } = await open({ files: { "intro.tex": "start" } });
    await user.click(screen.getByRole("button", { name: "intro.tex" }));
    await findEditor("Source of intro.tex");
    backend.saveFileAs("grace", id, "intro.tex", "grace's");
    typeIn("Source of intro.tex", " mine");
    expect(await screen.findByText("Someone else saved intro.tex while you were editing.", {}, { timeout: 3000 })).toBeInTheDocument();
    expect(await screen.findByText("Not saved: someone else changed this file")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Keep mine" }));
    await waitFor(() => expect(backend.files(id)["intro.tex"]).toBe("start mine"));

    backend.saveFileAs("grace", id, "intro.tex", "grace again");
    typeIn("Source of intro.tex", "!");
    await user.click(await screen.findByRole("button", { name: "Load their version" }, { timeout: 3000 }));
    await waitFor(() => expect(editorView("Source of intro.tex").state.doc.toString()).toBe("grace again"));
    expect(screen.queryByText(/Someone else saved/)).not.toBeInTheDocument();
  });

  it("stops saving a file that was deleted, and retries one that failed for a moment", async () => {
    const { user, id } = await open({ files: { "a.tex": "a", "b.tex": "b" } });
    await user.click(screen.getByRole("button", { name: "a.tex" }));
    await findEditor("Source of a.tex");
    backend.failNext("PUT", /\/files\//, { status: 503, body: { code: "unavailable", error: "down" } });
    typeIn("Source of a.tex", "1");
    expect(await screen.findByText(/Not saved: SkoLab couldn't save just now/, {}, { timeout: 3000 })).toBeInTheDocument();
    typeIn("Source of a.tex", "2");
    await waitFor(() => expect(backend.files(id)["a.tex"]).toBe("a12"), { timeout: 3000 });

    await user.click(screen.getByRole("button", { name: "b.tex" }));
    await findEditor("Source of b.tex");
    backend.failNext("PUT", /\/files\//, { status: 404, body: { code: "not_found", error: "gone" } });
    typeIn("Source of b.tex", "!");
    expect(await screen.findByText(/This file was deleted or moved/, {}, { timeout: 3000 })).toBeInTheDocument();
  });

  it("explains a file that won't open, and tries again", async () => {
    const { user } = await open({ files: { "a.tex": "a" } });
    backend.failNext("GET", /\/files\/[^/]+$/, { status: 503, body: { code: "unavailable", error: "down" } });
    await user.click(screen.getByRole("button", { name: "a.tex" }));
    expect(await screen.findByText("We couldn't open this file.")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Try again" }));
    await findEditor("Source of a.tex");
  });

  it("saves main.tex and the open file, then compiles the whole project", async () => {
    const { user, id } = await open({ files: { "chapters/intro.tex": "Intro", "figures/plot.png": PNG } });
    expect(compiles).toHaveLength(1);
    expect(compiles[0]?.files.map((file) => file.path)).toEqual(["chapters/intro.tex", "figures/plot.png"]);

    await user.click(screen.getByRole("button", { name: "intro.tex" }));
    await findEditor("Source of chapters/intro.tex");
    typeIn("Source of chapters/intro.tex", " and more");
    await user.click(screen.getByRole("button", { name: "Compile" }));
    await waitFor(() => expect(compiles).toHaveLength(2));
    // Saved before it compiled, without waiting for the pause.
    expect(atob(compiles[1]?.files[0]?.content_base64 ?? "")).toBe("Intro and more");
    expect(backend.output(id)).not.toBeNull();
    expect(await screen.findByTestId("pdf")).toHaveTextContent("%PDF-1.5 project");
  });

  it("shows output.pdf, opens it in the preview and downloads it", async () => {
    const { created, names } = captureDownloads();
    const { user, id } = await open();
    backend.setCompiler(() => ({ status: 200, body: { status: "compiled", pdf_base64: btoa("%PDF-1.5 second"), log: "" } }));
    await user.click(screen.getByRole("button", { name: /^output\.pdf Compiled/ }));
    await waitFor(() => expect(screen.getByTestId("pdf")).toHaveTextContent("%PDF-1.5 project"));

    await user.click(screen.getByRole("button", { name: "Actions for output.pdf" }));
    await user.click(screen.getByRole("button", { name: "Download" }));
    await waitFor(() => expect(names).toEqual(["Thesis.pdf"]));
    expect(created[0]?.type).toBe("application/pdf");

    await user.click(screen.getByRole("button", { name: "Actions for main.tex" }));
    await user.click(screen.getByRole("button", { name: "Download" }));
    expect(names).toEqual(["Thesis.pdf", "main.tex"]);
    expect(backend.output(id)).not.toBeNull();
    await act(() => new Promise((resolve) => setTimeout(resolve, 1100)));
  });

  it("downloads the whole project as a zip", async () => {
    const { created, names } = captureDownloads();
    const { user } = await open({ files: { "chapters/intro.tex": "Intro", figures: null } });
    await user.click(screen.getByRole("button", { name: "Download project as .zip" }));
    await waitFor(() => expect(names).toEqual(["Thesis.zip"]));
    expect(created[0]?.type).toBe("application/zip");
    const entries = readZip(new Uint8Array(await (created[0] as Blob).arrayBuffer()));
    expect(entries.map((entry) => entry.name)).toEqual(["main.tex", "chapters/", "chapters/intro.tex", "figures/", "output.pdf"]);
    await act(() => new Promise((resolve) => setTimeout(resolve, 1100)));
  });

  it("previews images, and shows a project PDF in the preview pane", async () => {
    const { created, names } = captureDownloads();
    const { user } = await open({ files: { "figures/plot.png": PNG, "figures/scan.pdf": new TextEncoder().encode("%PDF-1.4 scan"), "figures/old.eps": new TextEncoder().encode("%!PS-Adobe") } });
    await user.click(screen.getByRole("button", { name: "plot.png" }));
    const image = await screen.findByRole("img", { name: "plot.png, a figure in this project" });
    expect(image.getAttribute("src")).toMatch(/^data:image\/png;base64,/);
    await user.click(screen.getByRole("button", { name: "Download plot.png" }));
    expect(names).toEqual(["plot.png"]);
    expect(created[0]?.type).toBe("image/png");

    await user.click(screen.getByRole("button", { name: "old.eps" }));
    expect(await screen.findByText(/An EPS figure in this project/)).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Show in the PDF preview" })).not.toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "scan.pdf" }));
    await user.click(await screen.findByRole("button", { name: "Show in the PDF preview" }));
    expect(screen.getByTestId("pdf")).toHaveTextContent("%PDF-1.4 scan");
    expect(screen.getByText("Showing figures/scan.pdf")).toBeInTheDocument();
    // The PDF button still downloads the compiled output, not the figure.
    await user.click(screen.getByRole("button", { name: "PDF" }));
    await waitFor(() => expect(names).toEqual(["plot.png", "Thesis.pdf"]));
    await act(() => new Promise((resolve) => setTimeout(resolve, 1100)));
  });

  it("reports what the server refuses, and refreshes after a file went missing", async () => {
    const { user, id } = await open({ files: { "a.tex": "a" } });
    backend.failNext("POST", /\/files$/, { status: 413, body: { code: "project_full", error: "full" } });
    await user.click(screen.getByRole("button", { name: "New folder" }));
    await user.type(screen.getByLabelText("New folder name"), "more{Enter}");
    expect(await screen.findByRole("alert")).toHaveTextContent("The project is full");

    await user.clear(screen.getByLabelText("New folder name"));
    await user.type(screen.getByLabelText("New folder name"), "A.TEX{Enter}");
    expect(await screen.findByRole("alert")).toHaveTextContent("A file or folder with that name is already there.");
    await user.keyboard("{Escape}");
    expect(screen.queryByLabelText("New folder name")).not.toBeInTheDocument();

    const gone = Object.keys(backend.files(id));
    expect(gone).toEqual(["a.tex"]);
    backend.failNext("DELETE", /\/files\//, { status: 404, body: { code: "not_found", error: "gone" } });
    await user.click(screen.getByRole("button", { name: "Actions for a.tex" }));
    await user.click(screen.getByRole("button", { name: "Delete" }));
    await user.click(screen.getByRole("button", { name: "Confirm deleting a.tex" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("That file is no longer there.");
  });

  it("says when the files can't be listed, and tries again", async () => {
    const id = backend.seed(ADA, "Thesis", { source: SOURCE });
    // A successful compile refreshes the list by itself.
    backend.setCompiler(() => ({ status: 200, body: { status: "error", errors: ["no"], log: "" } }));
    backend.failNext("GET", /\/files$/, { status: 503, body: { code: "unavailable", error: "down" } });
    const { user } = await renderApp(`/editor/${id}`, async (auth) => {
      await auth.signInWithGoogle();
    });
    expect(await screen.findByText("We couldn't load the project's files.", {}, { timeout: 5000 })).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Try again" }));
    expect(await screen.findByRole("list", { name: "Project files" })).toBeInTheDocument();
  });

  it("opens the files in a panel on phones, and hides the column on wider screens", async () => {
    const { user } = await open({ files: { "intro.tex": "x" } });
    const toggle = screen.getByRole("button", { name: "Files" });
    expect(toggle).toHaveAttribute("aria-expanded", "true");
    await user.click(toggle);
    expect(toggle).toHaveAttribute("aria-expanded", "false");
    expect(screen.queryByRole("list", { name: "Project files" })).not.toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "Open project files" }));
    const sheet = screen.getByRole("dialog", { name: "Project files" });
    await user.click(within(sheet).getByRole("button", { name: "intro.tex" }));
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    await findEditor("Source of intro.tex");
    expect(screen.getByRole("tab", { name: "Source" })).toHaveAttribute("aria-selected", "true");
  });
});

// user-event's click, for helpers outside a test's own `user`.
async function userClick(element: HTMLElement) {
  await act(async () => {
    fireEvent.click(element);
    await Promise.resolve();
  });
}
