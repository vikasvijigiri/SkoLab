import { screen, waitFor, within } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { forgetPreviews } from "../components/PreviewImage";
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
    await waitFor(() => expect(screen.queryByText(/couldn't finish setting up/)).not.toBeInTheDocument());
    expect(screen.getByText("AL")).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "Account" }));
    await user.click(screen.getByRole("menuitem", { name: "Sign out" }));
    expect(await screen.findByRole("heading", { name: "Welcome back" })).toBeInTheDocument();
  });
});

describe("home documents and templates", () => {
  const ADA = "google-ada";

  async function openHome(path = "/") {
    return renderApp(path, async (auth) => {
      await auth.signInWithGoogle();
    });
  }

  /** Object URLs for preview pictures, which jsdom does not make. */
  function stubObjectUrls() {
    forgetPreviews();
    const made: Blob[] = [];
    vi.spyOn(URL, "createObjectURL").mockImplementation((blob) => `blob:test/${made.push(blob as Blob)}`);
    return made;
  }

  it("starts a document from a template and opens it in the editor", async () => {
    const { backend } = stubBackend(() => Response.json({ status: "error", errors: ["x"] }));
    const { user } = await openHome();
    expect(await screen.findByText("No projects yet")).toBeInTheDocument();
    await user.click(screen.getByRole("link", { name: "Choose a template" }));
    expect(await screen.findAllByRole("button", { name: /^Use the / })).toHaveLength(TEST_TEMPLATES.length);

    await user.click(screen.getByRole("button", { name: "Chemistry" }));
    expect(screen.getByRole("button", { name: "Chemistry" })).toHaveAttribute("aria-pressed", "true");
    expect(screen.getAllByRole("button", { name: /^Use the / })).toHaveLength(1);
    expect(screen.getByText("Journal of the American Chemical Society")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "The ACS Journal (achemso) template's source" })).toHaveAttribute("href", "https://ctan.org/pkg/achemso");

    await user.click(screen.getByRole("button", { name: "Use the ACS Journal (achemso) template" }));
    expect(await screen.findByDisplayValue("ACS Journal (achemso)", {}, { timeout: 5000 })).toBeInTheDocument();
    const create = backend.requests.find((request) => request.method === "POST");
    expect(create?.headers["idempotency-key"]).toMatch(/^[0-9a-f-]{36}$/);
    const id = /\/api\/v1\/workspaces\/([^/]+)\/document/.exec(backend.requests.find((request) => request.method === "PUT")?.url ?? "")?.[1] ?? "";
    expect(backend.document(id)).toMatchObject({ template_id: "acs-jacs", version: 1 });
    expect(backend.document(id)?.source).toContain("{achemso}");
  });

  it("lists your projects and shared ones; only owners can delete", async () => {
    const { backend } = stubBackend();
    backend.seed(ADA, "Old notes", { source: "a" });
    backend.seed("grace", "Grace's paper", { source: "b", members: { [ADA]: "viewer" } });
    backend.seed(ADA, "Thesis", { source: "c" });
    const confirm = vi.spyOn(window, "confirm").mockReturnValueOnce(false).mockReturnValueOnce(true);
    const { user } = await openHome();
    const links = await screen.findAllByRole("link", { name: /Old notes|Thesis|Grace/ });
    expect(links.map((link) => link.textContent)).toEqual(["Thesis", "Grace's paper", "Old notes"]);
    const shared = links[1]?.closest("li");
    expect(shared && within(shared).getByText("Can view")).toBeInTheDocument();
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
    expect(await screen.findByText(/We couldn't load your projects/)).toBeInTheDocument();
    await user.click(screen.getByRole("link", { name: "Templates" }));
    expect(await screen.findByText(/We couldn't load the templates/)).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Retry" }));
    expect(await screen.findAllByRole("button", { name: /^Use the / })).toHaveLength(TEST_TEMPLATES.length);
    await user.click(screen.getByRole("link", { name: "Projects" }));
    expect(await screen.findByText("No projects yet")).toBeInTheDocument();
  });

  it("explains a start that failed, and removes the half-made document", async () => {
    const { backend } = stubBackend();
    backend.failNext("PUT", /document$/, { status: 503, body: { code: "unavailable", error: "down" } });
    const { user } = await openHome("/templates");
    await user.click(await screen.findByRole("button", { name: "Use the Nature template" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("We couldn't create the project. Try again.");
    await waitFor(() => expect(backend.titles()).toEqual([]));

    backend.failNext("POST", /workspaces$/, { status: 429, body: { code: "rate_limited", error: "slow down" } });
    await user.click(screen.getByRole("button", { name: "Use the Nature template" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("creating projects too quickly");
  });

  it("says when it can't reach SkoLab", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn((url: string) => (url.includes("/profile/") ? Promise.resolve(Response.json({ status: "synced", uid: "x" })) : Promise.reject(new TypeError("Failed to fetch")))),
    );
    const { user } = await openHome();
    expect(await screen.findAllByText(/couldn't reach SkoLab/)).toHaveLength(1);
    await user.click(screen.getByRole("link", { name: "Templates" }));
    expect(await screen.findAllByText(/couldn't reach SkoLab/)).toHaveLength(1);
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
    // The editor lists the project's files once it opens; that request names the new project.
    await waitFor(() => expect(backend.requests.some((request) => request.url.endsWith("/files"))).toBe(true));
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

  it("offers every way in from the one New menu, on either page", async () => {
    stubBackend();
    const { user } = await openHome("/templates");
    await screen.findByRole("heading", { name: "Journal templates" });
    // No second set of start buttons on the page: New is the one place.
    expect(screen.queryByRole("button", { name: "Upload project" })).not.toBeInTheDocument();
    const picker = screen.getByLabelText("Choose a project folder to upload");
    expect(picker).toHaveAttribute("webkitdirectory");
    const opened = vi.spyOn(picker, "click");
    await user.click(screen.getByRole("button", { name: "New" }));
    const menu = screen.getByRole("menu", { name: "Create a project" });
    expect(within(menu).getAllByRole("menuitem").map((item) => item.textContent)).toEqual([
      expect.stringContaining("Blank project"),
      expect.stringContaining("From a journal template"),
      expect.stringContaining("Upload a .zip"),
      expect.stringContaining("Upload a folder"),
      expect.stringContaining("Upload .tex files"),
    ]);
    await user.click(within(menu).getByRole("menuitem", { name: /Upload a folder/ }));
    expect(opened).toHaveBeenCalledOnce();

    await user.click(screen.getByRole("link", { name: "Projects" }));
    await user.click(await screen.findByRole("button", { name: "New" }));
    await user.click(within(screen.getByRole("menu", { name: "Create a project" })).getByRole("menuitem", { name: /From a journal template/ }));
    expect(await screen.findByRole("heading", { name: "Journal templates" })).toBeInTheDocument();
  });

  it("shows each document's first page, authors, last edit, words and progress", async () => {
    const { backend } = stubBackend();
    const made = stubObjectUrls();
    backend.setName(ADA, "Ada Lovelace");
    backend.setName("grace", "Grace Hopper");
    backend.setName("alan", "Alan Turing");
    const source = String.raw`\documentclass{article}\title{Engines}\begin{document}\section{Introduction}One two three four five.\end{document}`;
    const paper = backend.seed(ADA, "Analytical engines", { source, members: { grace: "editor", alan: "viewer" } });
    backend.compiled(paper);
    backend.seed(ADA, "Fresh draft", { source: "" });
    const { user } = await openHome();

    const card = (await screen.findByRole("link", { name: "Analytical engines" })).closest("li") as HTMLElement;
    expect(await within(card).findByRole("img", { name: "First page of Analytical engines" })).toHaveAttribute("src", "blob:test/1");
    expect(made).toHaveLength(1);
    const asked = backend.requests.filter((request) => request.url.includes("/preview.jpg"));
    expect(asked).toHaveLength(1);
    expect(asked[0]?.url).toContain(`/api/v1/workspaces/${paper}/preview.jpg?at=`);
    expect(asked[0]?.headers.authorization ?? asked[0]?.headers.Authorization).toBe(`Bearer fake-token:${ADA}`);

    expect(within(card).getByText("Ada Lovelace, Grace Hopper and 1 other")).toBeInTheDocument();
    expect(within(card).getByText(/^Edited/)).toHaveTextContent(/by Ada Lovelace$/);
    expect(within(card).getByText(/^\d+ words$/)).toBeInTheDocument();
    expect(within(card).getByRole("progressbar", { name: "Manuscript progress" })).toHaveAttribute("aria-valuenow", expect.stringMatching(/^\d+$/));

    // Never compiled: no request, and the card says how to get a picture.
    const fresh = screen.getByRole("link", { name: "Fresh draft" }).closest("li") as HTMLElement;
    expect(within(fresh).getByText("Compile to see the first page")).toBeInTheDocument();
    expect(within(fresh).queryByRole("img")).not.toBeInTheDocument();

    // Going to Templates and back shows the kept picture without asking again.
    await user.click(screen.getByRole("link", { name: "Templates" }));
    expect(await screen.findAllByRole("img", { name: /^First page of the .* template$/ })).toHaveLength(TEST_TEMPLATES.length);
    await user.click(screen.getByRole("link", { name: "Projects" }));
    expect(await screen.findByRole("img", { name: "First page of Analytical engines" })).toBeInTheDocument();
    expect(backend.requests.filter((request) => request.url.includes("/workspaces/") && request.url.includes("/preview.jpg"))).toHaveLength(1);
  });

  it("falls back to a drawing when a preview cannot be fetched", async () => {
    const { backend } = stubBackend();
    stubObjectUrls();
    const paper = backend.seed(ADA, "Broken picture", { source: "x" });
    backend.compiled(paper);
    backend.failNext("GET", /preview\.jpg$/, { status: 404, body: { code: "no_preview", error: "none" } });
    await openHome();
    const card = (await screen.findByRole("link", { name: "Broken picture" })).closest("li") as HTMLElement;
    expect(await within(card).findByText("Preview unavailable")).toBeInTheDocument();
    expect(within(card).queryByRole("img")).not.toBeInTheDocument();
  });

  it("keeps the template filter in the address", async () => {
    stubBackend();
    await openHome("/templates?field=mathematics");
    expect(await screen.findByRole("button", { name: "Mathematics" })).toHaveAttribute("aria-pressed", "true");
    expect(await screen.findAllByRole("button", { name: /^Use the / })).toHaveLength(TEST_TEMPLATES.filter((template) => template.domain === "mathematics").length);
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
    // The editor lists the project's files once it opens; that request names the new project.
    await waitFor(() => expect(backend.requests.some((request) => request.url.endsWith("/files"))).toBe(true));
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
    // The editor lists the project's files once it opens; that request names the new project.
    await waitFor(() => expect(backend.requests.some((request) => request.url.endsWith("/files"))).toBe(true));
    const id = /\/workspaces\/([^/]+)\/files$/.exec(backend.requests.find((request) => request.url.endsWith("/files"))?.url ?? "")?.[1] ?? "";
    expect(backend.document(id)?.source).toContain("beamer");
    expect(Object.keys(backend.files(id)).sort()).toEqual(["paper.tex", "preamble.tex"]);
  });
});
