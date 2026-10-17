import { screen, waitFor, within } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { renderApp, stubBackend } from "../test/renderApp";

const ADA = "google-ada";

async function openHome() {
  const view = await renderApp("/", async (auth) => {
    await auth.signInWithGoogle();
  });
  await screen.findByRole("heading", { name: "Your documents" });
  return view;
}

describe("app header", () => {
  it("has a skip link, page navigation and a home link", async () => {
    stubBackend();
    const { user } = await openHome();
    expect(screen.getByRole("link", { name: "Skip to content" })).toHaveAttribute("href", "#main");
    expect(screen.getByRole("link", { name: "SkoLab home" })).toHaveAttribute("href", "/documents");
    const nav = screen.getByRole("navigation", { name: "Pages" });
    expect(within(nav).getAllByRole("link").map((link) => link.getAttribute("href"))).toEqual(["/documents", "/templates"]);
    expect(within(nav).getByRole("link", { name: "Documents" })).toHaveAttribute("aria-current", "page");

    // Each is a page of its own: one heading, the other page's content gone.
    await user.click(within(nav).getByRole("link", { name: "Templates" }));
    expect(await screen.findByRole("heading", { level: 1, name: "Journal templates" })).toBeInTheDocument();
    expect(screen.queryByRole("heading", { name: "Your documents" })).not.toBeInTheDocument();
    expect(within(nav).getByRole("link", { name: "Templates" })).toHaveAttribute("aria-current", "page");
    expect(within(nav).getByRole("link", { name: "Documents" })).not.toHaveAttribute("aria-current");
    await user.click(screen.getByRole("link", { name: "SkoLab home" }));
    expect(await screen.findByRole("heading", { level: 1, name: "Your documents" })).toBeInTheDocument();
    expect(screen.queryByRole("heading", { name: "Journal templates" })).not.toBeInTheDocument();
  });

  it("shows unread activity, marks it read when opened, and links to the document", async () => {
    const { backend } = stubBackend();
    const ws = backend.seed(ADA, "Spin waves", { source: "x" });
    backend.addActivity(ADA, { kind: "document_edited", workspace: { id: ws, title: "Spin waves" }, actor: { id: "bo", display_name: "Bo Diddley" }, count: 12 });
    backend.addActivity(ADA, { kind: "member_joined", workspace: { id: ws, title: "Spin waves" }, actor: { id: "cy", display_name: "Cy" }, role: "editor" });
    const { user } = await openHome();

    const bell = await screen.findByRole("button", { name: "Notifications, 2 unread" });
    await user.click(bell);
    const panel = screen.getByRole("dialog", { name: "Notifications" });
    const items = await within(panel).findAllByRole("link");
    expect(items.map((item) => item.textContent)).toEqual([
      expect.stringContaining("Cy joined Spin waves as an editor"),
      expect.stringContaining("Bo Diddley edited Spin waves12 saves"),
    ]);
    expect(within(panel).getAllByLabelText("New")).toHaveLength(2);
    await waitFor(() => expect(backend.activitySeenAt(ADA)).not.toBeNull());
    expect(screen.getByRole("button", { name: "Notifications" })).toBeInTheDocument();

    // Closing it clears the highlights; Escape returns focus to the bell.
    await user.keyboard("{Escape}");
    expect(screen.queryByRole("dialog", { name: "Notifications" })).not.toBeInTheDocument();
    await waitFor(() => expect(screen.getByRole("button", { name: "Notifications" })).toHaveFocus());
    await user.click(screen.getByRole("button", { name: "Notifications" }));
    expect(within(screen.getByRole("dialog", { name: "Notifications" })).queryAllByLabelText("New")).toHaveLength(0);

    await user.click(within(screen.getByRole("dialog", { name: "Notifications" })).getAllByRole("link")[0] as HTMLElement);
    expect(await screen.findByDisplayValue("Spin waves", {}, { timeout: 5000 })).toBeInTheDocument();
  });

  it("has an empty state, and a removal is shown without a link", async () => {
    const { backend } = stubBackend();
    const { user } = await openHome();
    await user.click(await screen.findByRole("button", { name: "Notifications" }));
    expect(await screen.findByText("You're all caught up")).toBeInTheDocument();
    await user.keyboard("{Escape}");

    backend.addActivity(ADA, { kind: "member_removed", workspace: { id: "gone", title: "Old draft" }, actor: { id: "bo", display_name: "Bo" }, subject: { id: ADA, display_name: "Ada" } });
    await user.click(screen.getByRole("button", { name: "Notifications" }));
    const panel = screen.getByRole("dialog", { name: "Notifications" });
    expect(await within(panel).findByText(/Bo removed you from/)).toBeInTheDocument();
    expect(within(panel).queryAllByRole("link")).toHaveLength(0);
  });

  it("caps the badge at 99+ and pages through older activity", async () => {
    const { backend } = stubBackend();
    for (let n = 0; n < 120; n += 1) backend.addActivity(ADA, { kind: "member_left", workspace: { id: `w${n}`, title: `Paper ${n}` }, actor: { id: "bo", display_name: "Bo" } });
    const { user } = await openHome();
    const bell = await screen.findByRole("button", { name: "Notifications, more than 99 unread" });
    expect(within(bell).getByText("99+")).toBeInTheDocument();
    await user.click(bell);
    const panel = screen.getByRole("dialog", { name: "Notifications" });
    expect(await within(panel).findAllByRole("link")).toHaveLength(20);
    await user.click(within(panel).getByRole("button", { name: /Show older/ }));
    await waitFor(() => expect(within(panel).getAllByRole("link")).toHaveLength(40));
  });

  it("explains a feed it cannot load and retries", async () => {
    const { backend } = stubBackend();
    backend.failNext("GET", /\/activity$/, { status: 503, body: { code: "unavailable", error: "down" } });
    const { user } = await openHome();
    await user.click(await screen.findByRole("button", { name: "Notifications" }));
    // Opening reloads, which succeeds.
    expect(await within(screen.getByRole("dialog", { name: "Notifications" })).findByText("You're all caught up")).toBeInTheDocument();
  });

  it("creates a blank document from New", async () => {
    const { backend } = stubBackend();
    const { user } = await openHome();
    await user.click(screen.getByRole("button", { name: "New" }));
    const menu = screen.getByRole("menu", { name: "Create a document" });
    expect(within(menu).getAllByRole("menuitem")).toHaveLength(5);
    expect(within(menu).getAllByRole("menuitem")[0]).toHaveFocus();
    await user.click(within(menu).getByRole("menuitem", { name: /Blank document/ }));
    expect(await screen.findByDisplayValue("Untitled document", {}, { timeout: 5000 })).toBeInTheDocument();
    expect(backend.titles()).toEqual(["Untitled document"]);
    const id = backend.requests.map((request) => /\/workspaces\/([^/]+)\/document$/.exec(request.url)?.[1]).find(Boolean) ?? "";
    expect(backend.document(id)?.source).toContain("\\documentclass[11pt]{article}");
  });

  it("moves through menus with the keyboard and closes them with Escape", async () => {
    stubBackend();
    const { user } = await openHome();
    const account = screen.getByRole("button", { name: "Account" });
    account.focus();
    await user.keyboard("{ArrowDown}");
    const menu = screen.getByRole("menu", { name: "Account" });
    const first = within(menu).getByRole("menuitemradio", { name: "Light" });
    expect(first).toHaveFocus();
    await user.keyboard("{End}");
    expect(within(menu).getByRole("menuitem", { name: "Sign out" })).toHaveFocus();
    await user.keyboard("{ArrowDown}");
    expect(first).toHaveFocus();
    await user.keyboard("{Escape}");
    expect(screen.queryByRole("menu")).not.toBeInTheDocument();
    await waitFor(() => expect(account).toHaveFocus());
  });

  it("switches the theme from the account menu", async () => {
    stubBackend();
    const { user } = await openHome();
    await user.click(screen.getByRole("button", { name: "Account" }));
    expect(screen.getByRole("menuitemradio", { name: "System" })).toHaveAttribute("aria-checked", "true");
    await user.click(screen.getByRole("menuitemradio", { name: "Dark" }));
    expect(document.documentElement.dataset.theme).toBe("dark");
    expect(screen.getByRole("menuitemradio", { name: "Dark" })).toHaveAttribute("aria-checked", "true");
    await user.click(screen.getByRole("menuitemradio", { name: "System" }));
    expect(document.documentElement.dataset.theme).toBeUndefined();
  });

  it("searches documents and templates with Ctrl K", async () => {
    const { backend } = stubBackend();
    backend.seed(ADA, "Quantum dots", { source: "x" });
    const { user } = await openHome();
    await screen.findByRole("link", { name: "Quantum dots" });
    await user.keyboard("{Control>}k{/Control}");
    const search = await screen.findByRole("combobox");
    await user.type(search, "quantum");
    await user.keyboard("{Enter}");
    expect(await screen.findByDisplayValue("Quantum dots", {}, { timeout: 5000 })).toBeInTheDocument();
  });

  it("lists the keyboard shortcuts on ?", async () => {
    stubBackend();
    const { user } = await openHome();
    await user.keyboard("?");
    const sheet = await screen.findByRole("dialog", { name: "Keyboard shortcuts" });
    expect(within(sheet).getByText("Search documents, templates and actions")).toBeInTheDocument();
  });
});
