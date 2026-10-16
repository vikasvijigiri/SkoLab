import { screen, waitFor, within } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { TEST_TEMPLATES } from "../test/fakeBackend";
import { renderApp, stubBackend } from "../test/renderApp";
import { writeZip } from "../test/zip";

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

    await user.click(screen.getByRole("button", { name: "Account" }));
    await user.click(screen.getByRole("menuitem", { name: "Sign out" }));
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
    expect(await screen.findByDisplayValue("ACS Journal (achemso)", {}, { timeout: 5000 })).toBeInTheDocument();
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

  it("uploads a project zip, opens it, and lists what it left out", async () => {
    const { backend } = stubBackend();
    const { user } = await openHome();
    const text = (value: string) => new TextEncoder().encode(value);
    const zip = writeZip([
      { name: "paper.tex", data: text("\\documentclass{article}\\begin{document}\\input{intro}\\end{document}") },
      { name: "intro.tex", data: text("Hello") },
      { name: "figures/", data: new Uint8Array() },
      { name: "figures/plot.png", data: new Uint8Array([0x89, 0x50, 0x4e, 0x47, 1]) },
      { name: "notes.docx", data: text("PK") },
      { name: "__MACOSX/._paper.tex", data: text("x") },
    ]);
    await user.upload(await screen.findByLabelText("Choose a project .zip to upload"), new File([zip], "Spin waves.zip", { type: "application/zip" }));
    expect(await screen.findByDisplayValue("Spin waves", {}, { timeout: 5000 })).toBeInTheDocument();
    expect(screen.getByText(/2 files were left out because SkoLab can't use them/)).toHaveTextContent("notes.docx, __MACOSX/._paper.tex");
    const id = /\/workspaces\/([^/]+)\/files$/.exec(backend.requests.find((request) => request.url.endsWith("/files"))?.url ?? "")?.[1] ?? "";
    expect(backend.document(id)?.source).toContain("\\input{intro}");
    expect(Object.keys(backend.files(id)).sort()).toEqual(["figures", "figures/plot.png", "intro.tex"]);
    await user.click(screen.getByRole("button", { name: "Dismiss" }));
    expect(screen.queryByText(/left out/)).not.toBeInTheDocument();
  });

  it("explains a project it can't import", async () => {
    const { backend } = stubBackend();
    const { user } = await openHome();
    const picker = await screen.findByLabelText("Choose a project .zip to upload");
    const text = (value: string) => new TextEncoder().encode(value);

    await user.upload(picker, new File(["not a zip"], "broken.zip", { type: "application/zip" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("That file isn't a zip we can read.");

    // No document class anywhere: nothing can be the main file.
    await user.upload(screen.getByLabelText("Choose .tex files to upload"), new File(["x"], "notes.tex"));
    expect(await screen.findByRole("alert")).toHaveTextContent("We couldn't find the main file");

    await user.upload(screen.getByLabelText("Choose .tex files to upload"), new File(["x"], "notes.txt"));
    expect(await screen.findByRole("alert")).toHaveTextContent("There's no .tex file in what you picked.");

    const huge = new File(["x"], "huge.zip", { type: "application/zip" });
    Object.defineProperty(huge, "size", { value: 13 * 1024 * 1024 });
    await user.upload(picker, huge);
    expect(await screen.findByRole("alert")).toHaveTextContent("That project is too large.");

    backend.failNext("POST", /import$/, { status: 503, body: { code: "unavailable", error: "down" } });
    await user.upload(picker, new File([writeZip([{ name: "main.tex", data: text("x") }])], "again.zip", { type: "application/zip" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("We couldn't import the project. Try again.");
    expect(backend.titles()).toEqual([]);
  });

  it("offers the three ways in from the Upload project menu", async () => {
    stubBackend();
    const { user } = await openHome();
    await user.click(await screen.findByRole("button", { name: "Upload project" }));
    const menu = screen.getByRole("menu", { name: "Upload a project" });
    expect(within(menu).getAllByRole("menuitem").map((item) => item.textContent)).toEqual([
      ".zip archiveFrom Overleaf, GitHub or anywhere else",
      "FolderA project folder on your computer",
      ".tex filesOne or more, with their .bib and figures",
    ]);
    expect(screen.getByLabelText("Choose a project folder to upload")).toHaveAttribute("webkitdirectory");
    await user.keyboard("{Escape}");
    expect(screen.queryByRole("menu")).not.toBeInTheDocument();
  });

  it("uploads a whole folder, leaving out build output and version control", async () => {
    const { backend } = stubBackend();
    const { user } = await openHome();
    const inFolder = (path: string, content: BlobPart) => {
      const file = new File([content], path.split("/").pop() ?? path);
      Object.defineProperty(file, "webkitRelativePath", { value: path });
      return file;
    };
    await user.upload(await screen.findByLabelText("Choose a project folder to upload"), [
      inFolder("Thesis/main.tex", "\\documentclass{book}\\begin{document}\\input{sections/intro}\\end{document}"),
      inFolder("Thesis/sections/intro.tex", "Hello"),
      inFolder("Thesis/figs/a.png", new Uint8Array([0x89, 0x50, 0x4e, 0x47, 1])),
      inFolder("Thesis/main.aux", "aux"),
      inFolder("Thesis/main.pdf", "%PDF-1.4"),
      inFolder("Thesis/.git/config", "git"),
      inFolder("Thesis/notes.docx", "PK"),
    ]);
    expect(await screen.findByDisplayValue("Thesis", {}, { timeout: 5000 })).toBeInTheDocument();
    expect(screen.getByText(/One file was left out/)).toHaveTextContent("notes.docx");
    const id = /\/workspaces\/([^/]+)\/files$/.exec(backend.requests.find((request) => request.url.endsWith("/files"))?.url ?? "")?.[1] ?? "";
    expect(backend.document(id)?.source).toContain("\\input{sections/intro}");
    expect(Object.keys(backend.files(id)).sort()).toEqual(["figs", "figs/a.png", "sections", "sections/intro.tex"]);
  });

  it("uploads a lone .tex file under its own name", async () => {
    const { backend } = stubBackend();
    const { user } = await openHome();
    await user.upload(await screen.findByLabelText("Choose .tex files to upload"), new File(["\\documentclass{article}\\begin{document}Hi\\end{document}"], "Paper draft.tex"));
    expect(await screen.findByDisplayValue("Paper draft", {}, { timeout: 5000 })).toBeInTheDocument();
    expect(backend.titles()).toEqual(["Paper draft"]);
  });

  it("asks which file is the main document when several could be", async () => {
    const { backend } = stubBackend();
    const { user } = await openHome();
    await user.upload(await screen.findByLabelText("Choose .tex files to upload"), [
      new File(["\\documentclass{article}\\begin{document}Paper\\end{document}"], "paper.tex"),
      new File(["\\documentclass{beamer}\\begin{document}Talk\\end{document}"], "slides.tex"),
      new File(["\\usepackage{amsmath}"], "preamble.tex"),
    ]);
    const sheet = await screen.findByRole("dialog", { name: "Which file is the main document?" });
    expect(within(sheet).getAllByRole("button").map((button) => button.textContent)).toContain("slides.tex");
    expect(backend.titles()).toEqual([]);
    await user.click(within(sheet).getByRole("button", { name: "slides.tex" }));
    expect(await screen.findByDisplayValue("slides", {}, { timeout: 5000 })).toBeInTheDocument();
    const id = /\/workspaces\/([^/]+)\/files$/.exec(backend.requests.find((request) => request.url.endsWith("/files"))?.url ?? "")?.[1] ?? "";
    expect(backend.document(id)?.source).toContain("beamer");
    expect(Object.keys(backend.files(id)).sort()).toEqual(["paper.tex", "preamble.tex"]);
  });
});
