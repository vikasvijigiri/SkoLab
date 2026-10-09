import { screen } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { createDocumentStore } from "../editor/documents";
import { TEMPLATES } from "../editor/templates";
import { renderApp, stubApi } from "../test/renderApp";

describe("home", () => {
  it("lets the user retry a failed profile setup, and sign out", async () => {
    let attempts = 0;
    stubApi(() => {
      attempts += 1;
      return attempts === 1 ? Response.json({ code: "database_unavailable", error: "down" }, { status: 503 }) : Response.json({ status: "synced", uid: "x" });
    });
    const { user } = await renderApp("/", async (auth) => {
      await auth.signInWithGoogle();
    });
    expect(await screen.findByRole("alert")).toHaveTextContent("couldn't finish setting up");
    await user.click(screen.getByRole("button", { name: "Try again" }));
    expect(await screen.findByText(/You're all set/)).toBeInTheDocument();
    expect(screen.getByText("AL")).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "Sign out" }));
    expect(await screen.findByRole("heading", { name: "Welcome back" })).toBeInTheDocument();
  });
});

describe("home documents and templates", () => {
  beforeEach(() => window.localStorage.clear());

  it("starts a document from a template and opens it in the editor", async () => {
    stubApi((url) => (url.endsWith("/compile") ? Response.json({ status: "error", errors: ["x"] }) : Response.json({ status: "synced", uid: "x" })));
    const { user } = await renderApp("/", async (auth) => {
      await auth.signInWithGoogle();
    });
    expect(await screen.findByText("No documents yet. Start one from a template below.")).toBeInTheDocument();
    expect(screen.getAllByRole("button", { name: /^Use the / })).toHaveLength(TEMPLATES.length);

    await user.click(screen.getByRole("button", { name: "Presentation" }));
    expect(screen.getByRole("button", { name: "Presentation" })).toHaveAttribute("aria-pressed", "true");
    expect(screen.getAllByRole("button", { name: /^Use the / })).toHaveLength(TEMPLATES.filter((t) => t.category === "Presentation").length);

    await user.click(screen.getByRole("button", { name: "Use the Beamer Conference Talk template" }));
    expect(await screen.findByDisplayValue("Beamer Conference Talk")).toBeInTheDocument();
    const [doc] = createDocumentStore(window.localStorage, "google-ada").list();
    expect(doc?.templateId).toBe("beamer-talk");
    expect(createDocumentStore(window.localStorage, "google-ada").get(doc?.id ?? "")?.source).toContain("\\documentclass{beamer}");
  });

  it("lists, opens and deletes documents", async () => {
    stubApi();
    const store = createDocumentStore(window.localStorage, "google-ada");
    store.create("Old notes", "a", null, 1);
    store.create("Thesis", "b", "ieee-journal", 2);
    const confirm = vi.spyOn(window, "confirm").mockReturnValueOnce(false).mockReturnValueOnce(true);
    const { user } = await renderApp("/", async (auth) => {
      await auth.signInWithGoogle();
    });
    const links = await screen.findAllByRole("link", { name: /Old notes|Thesis/ });
    expect(links.map((link) => link.textContent)).toEqual(["Thesis", "Old notes"]);
    expect(screen.getByText(/IEEE Journal Article · edited/)).toBeInTheDocument();
    expect(screen.getByText(/Blank · edited/)).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "Delete Old notes" }));
    expect(screen.getByRole("link", { name: "Old notes" })).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Delete Old notes" }));
    expect(screen.queryByRole("link", { name: "Old notes" })).not.toBeInTheDocument();
    expect(confirm).toHaveBeenCalledTimes(2);
    expect(store.list().map((d) => d.title)).toEqual(["Thesis"]);
  });

  it("explains when the browser cannot store another document", async () => {
    stubApi();
    vi.spyOn(Storage.prototype, "setItem").mockImplementation(() => {
      throw new DOMException("full", "QuotaExceededError");
    });
    const { user } = await renderApp("/", async (auth) => {
      await auth.signInWithGoogle();
    });
    await user.click(await screen.findByRole("button", { name: "Use the Blank Article template" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("storage is full");
  });
});
