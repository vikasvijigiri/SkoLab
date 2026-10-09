import { screen, waitFor, within } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { TEST_TEMPLATES } from "../test/fakeBackend";
import { renderApp, stubBackend } from "../test/renderApp";

describe("home", () => {
  it("lets the user retry a failed profile setup, and sign out", async () => {
    let attempts = 0;
    stubBackend(() => {
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
  const ADA = "google-ada";

  async function openHome() {
    return renderApp("/", async (auth) => {
      await auth.signInWithGoogle();
    });
  }

  it("starts a document from a template and opens it in the editor", async () => {
    const { backend } = stubBackend(() => Response.json({ status: "error", errors: ["x"] }));
    const { user } = await openHome();
    expect(await screen.findByText("No documents yet. Start one from a template below.")).toBeInTheDocument();
    expect(await screen.findAllByRole("button", { name: /^Use the / })).toHaveLength(TEST_TEMPLATES.length);

    await user.click(screen.getByRole("button", { name: "Chemistry" }));
    expect(screen.getByRole("button", { name: "Chemistry" })).toHaveAttribute("aria-pressed", "true");
    expect(screen.getAllByRole("button", { name: /^Use the / })).toHaveLength(1);
    expect(screen.getByText("Journal of the American Chemical Society")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "source" })).toHaveAttribute("href", "https://ctan.org/pkg/achemso");

    await user.click(screen.getByRole("button", { name: "Use the ACS Journal (achemso) template" }));
    expect(await screen.findByDisplayValue("ACS Journal (achemso)")).toBeInTheDocument();
    const create = backend.requests.find((request) => request.method === "POST");
    expect(create?.headers["idempotency-key"]).toMatch(/^[0-9a-f-]{36}$/);
    const id = /\/api\/v1\/workspaces\/([^/]+)\/document/.exec(backend.requests.find((request) => request.method === "PUT")?.url ?? "")?.[1] ?? "";
    expect(backend.document(id)).toMatchObject({ template_id: "acs-jacs", version: 1 });
    expect(backend.document(id)?.source).toContain("{achemso}");
  });

  it("lists your documents and shared ones; only owners can delete", async () => {
    const { backend } = stubBackend();
    backend.seed(ADA, "Old notes", { source: "a" });
    backend.seed("grace", "Grace's paper", { source: "b", members: { [ADA]: "viewer" } });
    backend.seed(ADA, "Thesis", { source: "c" });
    const confirm = vi.spyOn(window, "confirm").mockReturnValueOnce(false).mockReturnValueOnce(true);
    const { user } = await openHome();
    const links = await screen.findAllByRole("link", { name: /Old notes|Thesis|Grace/ });
    expect(links.map((link) => link.textContent)).toEqual(["Thesis", "Grace's paper", "Old notes"]);
    const shared = links[1]?.closest("li");
    expect(shared && within(shared).getByText(/Can view · created/)).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Delete Grace's paper" })).not.toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "Delete Old notes" }));
    expect(screen.getByRole("link", { name: "Old notes" })).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Delete Old notes" }));
    await waitFor(() => expect(screen.queryByRole("link", { name: "Old notes" })).not.toBeInTheDocument());
    expect(confirm).toHaveBeenCalledTimes(2);
    expect(backend.titles()).toEqual(["Grace's paper", "Thesis"]);
  });

  it("treats a document someone else already deleted as deleted, and reports other failures", async () => {
    const { backend } = stubBackend();
    const gone = backend.seed(ADA, "Gone", { source: "a" });
    backend.seed(ADA, "Stuck", { source: "b" });
    vi.spyOn(window, "confirm").mockReturnValue(true);
    const { user } = await openHome();
    await screen.findByRole("link", { name: "Gone" });
    backend.remove(gone);
    await user.click(screen.getByRole("button", { name: "Delete Gone" }));
    await waitFor(() => expect(screen.queryByRole("link", { name: "Gone" })).not.toBeInTheDocument());

    backend.failNext("DELETE", /workspaces/, { status: 503, body: { code: "unavailable", error: "down" } });
    await user.click(screen.getByRole("button", { name: "Delete Stuck" }));
    expect(await screen.findByRole("alert")).toHaveTextContent('We couldn\'t delete "Stuck"');
    expect(screen.getByRole("link", { name: "Stuck" })).toBeInTheDocument();
  });

  it("offers a retry when documents or templates fail to load", async () => {
    const { backend } = stubBackend();
    backend.failNext("GET", /^\/api\/v1\/workspaces$/, { status: 503, body: { code: "unavailable", error: "down" } });
    backend.failNext("GET", /^\/api\/v1\/templates$/, { status: 503, body: { code: "unavailable", error: "down" } });
    const { user } = await openHome();
    expect(await screen.findByText(/We couldn't load your documents/)).toBeInTheDocument();
    expect(screen.getByText(/We couldn't load the templates/)).toBeInTheDocument();
    await user.click(screen.getAllByRole("button", { name: "Retry" })[0] as HTMLElement);
    expect(await screen.findByText("No documents yet. Start one from a template below.")).toBeInTheDocument();
    expect(await screen.findAllByRole("button", { name: /^Use the / })).toHaveLength(TEST_TEMPLATES.length);
  });

  it("explains a start that failed, and removes the half-made document", async () => {
    const { backend } = stubBackend();
    backend.failNext("PUT", /document$/, { status: 503, body: { code: "unavailable", error: "down" } });
    const { user } = await openHome();
    await user.click(await screen.findByRole("button", { name: "Use the Nature template" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("We couldn't create the document. Try again.");
    await waitFor(() => expect(backend.titles()).toEqual([]));

    backend.failNext("POST", /workspaces$/, { status: 429, body: { code: "rate_limited", error: "slow down" } });
    await user.click(screen.getByRole("button", { name: "Use the Nature template" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("creating documents too quickly");
  });

  it("says when it can't reach SkoLab", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn((url: string) => (url.includes("/profile/") ? Promise.resolve(Response.json({ status: "synced", uid: "x" })) : Promise.reject(new TypeError("Failed to fetch")))),
    );
    await openHome();
    expect(await screen.findAllByText(/couldn't reach SkoLab/)).toHaveLength(2);
  });
});
