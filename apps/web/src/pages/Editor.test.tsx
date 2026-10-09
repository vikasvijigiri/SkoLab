import { act, fireEvent, screen, waitFor } from "@testing-library/react";
import { beforeAll, beforeEach, describe, expect, it, vi } from "vitest";
import { createDocumentStore } from "../editor/documents";
import { templateById } from "../editor/templates";
import { renderApp, stubApi } from "../test/renderApp";

// pdf.js needs a real canvas; the browser tests (e2e/editor.spec.ts) cover it.
vi.mock("../editor/PdfPreview", () => ({
  PdfPreview: ({ pdf }: { pdf: Uint8Array }) => <p data-testid="pdf">{new TextDecoder().decode(pdf)}</p>,
}));

const ADA = "google-ada";

function seed(source = "\\documentclass{article}\\begin{document}Hi\\end{document}", templateId: string | null = "ieee-conference") {
  return createDocumentStore(window.localStorage, ADA).create("My paper", source, templateId);
}

function compileAnswers(...answers: (() => Response)[]) {
  const calls: unknown[] = [];
  const fetchMock = stubApi((url, init) => {
    if (!url.endsWith("/api/v1/colab/compile")) return Response.json({ status: "synced", uid: "x" });
    calls.push(JSON.parse(init.body as string));
    const next = answers.shift();
    return next ? next() : Response.json({ status: "compiled", pdf_base64: btoa("%PDF again") });
  });
  return { calls, fetchMock };
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
  beforeEach(() => window.localStorage.clear());

  it("compiles on open and shows the PDF", async () => {
    const doc = seed();
    const { calls } = compileAnswers(() => Response.json({ status: "compiled", pdf_base64: btoa("%PDF-1.7 first"), log: "" }));
    await openEditor(doc.id);
    // The first test also loads the editor's code.
    expect(await screen.findByTestId("pdf", {}, { timeout: 5000 })).toHaveTextContent("%PDF-1.7 first");
    expect(calls).toEqual([{ latex_source: doc.source, engine: "pdflatex" }]);
    expect(screen.getByDisplayValue("My paper")).toBeInTheDocument();
    expect(screen.getByText(`Template: ${templateById("ieee-conference")?.name ?? ""} (LPPL 1.3)`)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "PDF" })).toBeEnabled();
  });

  it("lists compile errors, keeps the last good PDF and shows the log", async () => {
    const doc = seed();
    compileAnswers(
      () => Response.json({ status: "compiled", pdf_base64: btoa("%PDF good") }),
      () => Response.json({ status: "error", errors: ["line 3: Undefined control sequence.", "Something else"], log: "full log" }),
    );
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
    const doc = seed();
    compileAnswers(
      () => Response.json({ code: "compile_in_progress", error: "busy" }, { status: 429 }),
      () => Response.json({ code: "quota_exceeded", error: "spent" }, { status: 429 }),
      () => Response.json({ code: "compile_unavailable", error: "down" }, { status: 503 }),
      () => Response.json({ status: "timeout", errors: ["Compilation exceeded the 20 second limit."], log: "" }),
    );
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
    const doc = seed("x".repeat(100_001));
    const { calls } = compileAnswers();
    await openEditor(doc.id);
    expect(await screen.findByRole("alert")).toHaveTextContent("up to 100,000 characters");
    expect(calls).toEqual([]);
  });

  it("autosaves the title and the source in this browser", async () => {
    const doc = seed();
    compileAnswers();
    const { user, unmount } = await openEditor(doc.id);
    await screen.findByTestId("pdf");
    const title = screen.getByLabelText("Document title");
    await user.clear(title);
    await user.type(title, "Renamed");
    await waitFor(() => expect(createDocumentStore(window.localStorage, ADA).get(doc.id)?.title).toBe("Renamed"));
    expect(screen.getByText("Saved in this browser")).toBeInTheDocument();
    await user.clear(title);
    unmount();
    expect(createDocumentStore(window.localStorage, ADA).get(doc.id)?.title).toBe("Untitled");
  });

  it("switches between source and PDF on phones, zooms, and downloads", async () => {
    const doc = seed();
    compileAnswers();
    const created: Blob[] = [];
    vi.spyOn(URL, "createObjectURL").mockImplementation((blob) => {
      created.push(blob as Blob);
      return "blob:x";
    });
    vi.spyOn(URL, "revokeObjectURL").mockImplementation(() => undefined);
    const click = vi.spyOn(HTMLAnchorElement.prototype, "click").mockImplementation(() => undefined);
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

  it("says when a document does not exist here", async () => {
    stubApi();
    await openEditor("missing");
    expect(await screen.findByRole("heading", { name: "Document not found" })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Back to your documents" })).toHaveAttribute("href", "/");
  });
});
