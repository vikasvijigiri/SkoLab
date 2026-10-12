import { act, screen, waitFor, within } from "@testing-library/react";
import { beforeAll, beforeEach, describe, expect, it, vi } from "vitest";
import { EditorView } from "@codemirror/view";
import type { FakeBackend } from "../test/fakeBackend";
import { renderApp, stubBackend } from "../test/renderApp";

vi.mock("../editor/PdfPreview", () => ({
  PdfPreview: () => <p data-testid="pdf">pdf</p>,
}));

const ADA = "google-ada";
const SOURCE = "\\documentclass{article}\n\\title{Spin waves}\n\\begin{document}\n\\section{Introduction}\nMagnons carry spin.\n\\end{document}\n";

let backend: FakeBackend;

function typeInSource(text: string) {
  const dom = screen.getByTestId("latex-editor").shadowRoot?.querySelector<HTMLElement>(".cm-editor");
  const view = dom ? EditorView.findFromDOM(dom) : null;
  if (!view) throw new Error("no editor");
  act(() => view.dispatch({ changes: { from: view.state.doc.length, insert: text } }));
}

async function open(owner = ADA, role?: "editor" | "viewer") {
  const id = backend.seed(owner, "My paper", { source: SOURCE, ...(role ? { members: { [ADA]: role } } : {}) });
  backend.setName(ADA, "Ada Lovelace");
  backend.setName("grace", "Grace Hopper");
  const view = await renderApp(`/editor/${id}`, async (auth) => {
    await auth.signInWithGoogle();
  });
  await screen.findByTestId("pdf", {}, { timeout: 5000 });
  return { id, ...view };
}

describe("editor overview and sharing", () => {
  beforeAll(() => {
    Range.prototype.getClientRects = () => [] as unknown as DOMRectList;
    Range.prototype.getBoundingClientRect = () => new DOMRect();
  });
  beforeEach(() => {
    backend = stubBackend(() => Response.json({ status: "compiled", pdf_base64: btoa("%PDF"), log: "" })).backend;
  });

  it("shows progress and opens an overview of contents and history", async () => {
    const { user } = await open();
    const progress = screen.getByRole("button", { name: /^Progress 25%/ });
    expect(screen.getByText(/^Created /)).toBeInTheDocument();

    await user.click(progress);
    const sheet = screen.getByRole("dialog", { name: "Overview" });
    expect(within(sheet).getByText("25%")).toBeInTheDocument();
    expect(within(sheet).getByText("Title").parentElement).toHaveTextContent("(done)");
    expect(within(sheet).getByText("Reference list").parentElement).toHaveTextContent("(to do)");
    expect(within(sheet).getByText("Sections").nextElementSibling).toHaveTextContent("1");
    expect(within(sheet).getByText("Your access").nextElementSibling).toHaveTextContent("Owner");
    expect(within(sheet).getByText("Last saved").nextElementSibling).toHaveTextContent("by you");

    await user.keyboard("{Escape}");
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(progress).toHaveFocus();
  });

  it("updates progress as the document saves", async () => {
    await open();
    typeInSource("\\begin{thebibliography}{1}\\bibitem{a} A.\\end{thebibliography}");
    expect(await screen.findByRole("button", { name: /^Progress 37%/ }, { timeout: 3000 })).toBeInTheDocument();
  });

  it("creates an invite link, lists it and revokes it", async () => {
    const { user, id } = await open();
    await user.click(screen.getByRole("button", { name: /Share/ }));
    const sheet = screen.getByRole("dialog", { name: "Share" });
    expect(await within(sheet).findByText("Ada Lovelace")).toBeInTheDocument();
    expect(sheet).toHaveFocus();

    await user.selectOptions(within(sheet).getByLabelText("Access"), "viewer");
    await user.selectOptions(within(sheet).getByLabelText("Link works for"), "24");
    await user.selectOptions(within(sheet).getByLabelText("Who can use it"), "1");
    await user.click(within(sheet).getByRole("button", { name: "Create invite link" }));

    const link = await within(sheet).findByLabelText("Send this link to your co-author");
    expect((link as HTMLInputElement).value).toMatch(/\/invite#inv_/);
    const created = backend.requests.find((request) => request.method === "POST" && request.url.endsWith("/invites"));
    expect(created?.body).toEqual({ role: "viewer", expires_in_hours: 24, max_uses: 1 });

    const writeText = vi.fn(() => Promise.resolve());
    Object.defineProperty(navigator, "clipboard", { value: { writeText }, configurable: true });
    await user.click(within(sheet).getByRole("button", { name: "Copy" }));
    expect(writeText).toHaveBeenCalledWith((link as HTMLInputElement).value);
    expect(within(sheet).getByRole("button", { name: "Copied" })).toBeInTheDocument();

    const openLinks = within(sheet).getByRole("heading", { name: "Open invite links" }).parentElement as HTMLElement;
    expect(within(openLinks).getByText("Can view")).toBeInTheDocument();
    expect(within(openLinks).getByText(/used 0 of 1/)).toBeInTheDocument();
    await user.click(within(openLinks).getByRole("button", { name: "Revoke" }));
    await waitFor(() => expect(within(sheet).queryByRole("heading", { name: "Open invite links" })).not.toBeInTheDocument());
    expect(within(sheet).queryByLabelText("Send this link to your co-author")).not.toBeInTheDocument();
    expect(backend.requests.some((request) => request.method === "DELETE" && request.url.includes(`/workspaces/${id}/invites/`))).toBe(true);
  });

  it("lets the owner change a co-author's access and remove them", async () => {
    const id = backend.seed(ADA, "Shared", { source: SOURCE, members: { grace: "editor" } });
    backend.setName("grace", "Grace Hopper");
    const { user } = await renderApp(`/editor/${id}`, async (auth) => {
      await auth.signInWithGoogle();
    });
    await screen.findByTestId("pdf", {}, { timeout: 5000 });
    await user.click(screen.getByRole("button", { name: /Share/ }));
    const sheet = screen.getByRole("dialog", { name: "Share" });

    await user.selectOptions(await within(sheet).findByLabelText("Access for Grace Hopper"), "commenter");
    await waitFor(() => expect(backend.members(id).grace).toBe("commenter"));

    await user.click(within(sheet).getByRole("button", { name: "Remove Grace Hopper" }));
    expect(backend.members(id).grace).toBe("commenter"); // asks first
    await user.click(within(sheet).getByRole("button", { name: "Confirm removing Grace Hopper" }));
    await waitFor(() => expect(within(sheet).queryByText("Grace Hopper")).not.toBeInTheDocument());
    expect(backend.members(id).grace).toBeUndefined();
  });

  it("lets a viewer see who has access and leave, but not invite", async () => {
    const { user, id } = await open("grace", "viewer");
    await user.click(screen.getByRole("button", { name: /Share/ }));
    const sheet = screen.getByRole("dialog", { name: "Share" });
    expect(await within(sheet).findByText("Grace Hopper")).toBeInTheDocument();
    expect(within(sheet).getByText("Only the owner and editors can invite people.")).toBeInTheDocument();
    expect(within(sheet).queryByRole("button", { name: "Create invite link" })).not.toBeInTheDocument();
    expect(backend.requests.some((request) => request.url.includes("/invite-options"))).toBe(false);

    await user.click(within(sheet).getByRole("button", { name: "Leave" }));
    await user.click(within(sheet).getByRole("button", { name: "Leave this document?" }));
    expect(await screen.findByRole("heading", { name: /Welcome/ })).toBeInTheDocument();
    expect(backend.members(id)[ADA]).toBeUndefined();
  });

  it("says so when sharing fails, and keeps the panel usable", async () => {
    const { user } = await open();
    backend.failNext("GET", /\/members$/, { status: 503, body: { code: "unavailable", error: "down" } });
    await user.click(screen.getByRole("button", { name: /Share/ }));
    const sheet = screen.getByRole("dialog", { name: "Share" });
    expect(await within(sheet).findByText("We couldn't load who has access.")).toBeInTheDocument();
    await user.click(within(sheet).getByRole("button", { name: "Try again" }));
    expect(await within(sheet).findByText("Ada Lovelace")).toBeInTheDocument();

    backend.failNext("POST", /\/invites$/, { status: 429, body: { code: "rate_limited", error: "slow" } });
    await user.click(within(sheet).getByRole("button", { name: "Create invite link" }));
    expect(await within(sheet).findByText(/Too many changes at once/)).toBeInTheDocument();
  });
});
