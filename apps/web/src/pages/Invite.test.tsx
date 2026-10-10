import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import type { FakeBackend } from "../test/fakeBackend";
import { renderApp, stubBackend, withAccount } from "../test/renderApp";

vi.mock("./Editor", () => ({ Editor: () => <h1>Editor open</h1> }));

let backend: FakeBackend;

describe("accepting an invite", () => {
  beforeEach(() => {
    sessionStorage.clear();
    backend = stubBackend().backend;
  });

  it("previews the document and joins it with the invite's role", async () => {
    const id = backend.seed("grace", "Spin waves");
    const token = backend.invite(id, "editor");
    const { user } = await renderApp(`/invite#${token}`, async (auth) => {
      await auth.signInWithGoogle();
    });
    expect(await screen.findByRole("heading", { name: "Spin waves" })).toBeInTheDocument();
    expect(screen.getByText("Can edit")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Open the document" }));
    expect(await screen.findByRole("heading", { name: "Editor open" })).toBeInTheDocument();
    expect(backend.members(id)["google-ada"]).toBe("editor");
    expect(sessionStorage.getItem("skolab.invite")).toBeNull();
  });

  it("keeps the token through signing in, without putting it in the address", async () => {
    const id = backend.seed("grace", "Spin waves");
    const token = backend.invite(id, "viewer");
    await renderApp(`/invite#${token}`, (auth) => withAccount(auth));
    expect(await screen.findByRole("heading", { name: "Welcome back" })).toBeInTheDocument();
    expect(sessionStorage.getItem("skolab.invite")).toBe(token);

    const user = userEvent.setup();
    await user.type(screen.getByLabelText("Email"), "grace@example.com");
    await user.type(screen.getByLabelText("Password"), "correct horse 42");
    await user.click(screen.getByRole("button", { name: "Sign in" }));
    expect(await screen.findByRole("heading", { name: "Spin waves" })).toBeInTheDocument();
    expect(backend.requests.every((request) => !request.url.includes(token))).toBe(true);
  });

  it("explains an expired or revoked link", async () => {
    const id = backend.seed("grace", "Spin waves");
    const token = backend.invite(id, "editor", { expiresAt: "2020-01-01T00:00:00Z" });
    await renderApp(`/invite#${token}`, async (auth) => {
      await auth.signInWithGoogle();
    });
    expect(await screen.findByRole("heading", { name: "Invite unavailable" })).toBeInTheDocument();
    expect(screen.getByText(/expired, has been used up, or was revoked/)).toBeInTheDocument();
  });

  it("explains a link with no token", async () => {
    await renderApp("/invite");
    expect(await screen.findByRole("heading", { name: "This invite link is incomplete" })).toBeInTheDocument();
  });

  it("does not lower access someone already has", async () => {
    const id = backend.seed("grace", "Spin waves", { members: { "google-ada": "editor" } });
    const token = backend.invite(id, "viewer");
    const { user } = await renderApp(`/invite#${token}`, async (auth) => {
      await auth.signInWithGoogle();
    });
    await user.click(await screen.findByRole("button", { name: "Open the document" }));
    expect(await screen.findByRole("heading", { name: "Editor open" })).toBeInTheDocument();
    expect(backend.members(id)["google-ada"]).toBe("editor");
  });
});
