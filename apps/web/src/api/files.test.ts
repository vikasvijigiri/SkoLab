import { beforeEach, describe, expect, it, vi } from "vitest";
import type { FakeBackend } from "../test/fakeBackend";
import { stubBackend } from "../test/renderApp";
import { readZip, writeZip } from "../test/zip";
import { ApiError } from "./client";
import { VersionConflictError } from "./editor";
import { formatBytes, kindForPath, pathProblem } from "./editorTypes";
import {
  compileProject,
  createFile,
  deleteFile,
  downloadArchive,
  fileBlob,
  getFile,
  importProject,
  listFiles,
  moveFile,
  outputPdf,
  saveFile,
  uploadFiles,
} from "./files";

const ADA = "fake-token:ada";
const PNG = new Uint8Array([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]);
const text = (value: string) => new TextEncoder().encode(value);

let backend: FakeBackend;
let fetchMock: ReturnType<typeof stubBackend>["fetchMock"];
let id: string;

const failure = (promise: Promise<unknown>) => promise.then(() => null, (error: unknown) => error as ApiError);

describe("project files API (against the fake backend)", () => {
  beforeEach(() => {
    ({ backend, fetchMock } = stubBackend());
    id = backend.seed("ada", "Thesis", { source: "\\documentclass{article}" });
  });

  it("creates, lists, reads, saves, moves and deletes files", async () => {
    const created = await createFile(ADA, id, { path: "chapters/intro.tex", kind: "text", content: "Hi" });
    expect(created).toMatchObject({ path: "chapters/intro.tex", kind: "text", size: 2, version: 1, content: "Hi", content_type: "text/plain; charset=utf-8" });
    const listing = await listFiles(ADA, id);
    expect(listing.files.map((file) => [file.path, file.kind])).toEqual([
      ["chapters", "folder"],
      ["chapters/intro.tex", "text"],
    ]);
    expect(listing.main).toMatchObject({ path: "main.tex", version: 1 });
    expect(listing.usage).toEqual({ bytes: 2, limit_bytes: 10 * 1024 * 1024, entries: 2, limit_entries: 200 });
    expect(listing.output).toBeNull();

    expect((await getFile(ADA, id, created.id)).content).toBe("Hi");
    const saved = await saveFile(ADA, id, created.id, { content: "Hello", base_version: 1 });
    expect(saved).toMatchObject({ version: 2, size: 5, content: "Hello" });
    const conflict = await failure(saveFile(ADA, id, created.id, { content: "x", base_version: 1 }));
    expect(conflict).toBeInstanceOf(VersionConflictError);
    expect(conflict).toMatchObject({ currentVersion: 2 });

    const folder = listing.files[0]?.id ?? "";
    await moveFile(ADA, id, folder, "parts/one");
    expect(Object.keys(backend.files(id)).sort()).toEqual(["parts", "parts/one", "parts/one/intro.tex"]);
    expect(await failure(moveFile(ADA, id, folder, "parts/one/inner"))).toMatchObject({ status: 400, code: "invalid_path" });
    await deleteFile(ADA, id, folder);
    expect(backend.files(id)).toEqual({ parts: null });
  });

  it("enforces the contract's names, types and limits", async () => {
    expect(await failure(createFile(ADA, id, { path: "main.tex", kind: "text" }))).toMatchObject({ status: 400, code: "reserved_path" });
    expect(await failure(createFile(ADA, id, { path: "a//b.tex", kind: "text" }))).toMatchObject({ status: 400, code: "invalid_path" });
    expect(await failure(createFile(ADA, id, { path: "a/b/c/d/e/f/g.tex", kind: "text" }))).toMatchObject({ status: 400, code: "invalid_path" });
    expect(await failure(createFile(ADA, id, { path: "virus.exe", kind: "text" }))).toMatchObject({ status: 415, code: "unsupported_file_type" });
    expect(await failure(createFile(ADA, id, { path: "bad.tex", kind: "text", content: "a\u0001b" }))).toMatchObject({ status: 400, code: "invalid_text" });
    await createFile(ADA, id, { path: "Notes.tex", kind: "text" });
    expect(await failure(createFile(ADA, id, { path: "notes.TEX", kind: "text" }))).toMatchObject({ status: 409, code: "file_exists" });
    expect(await failure(createFile(ADA, id, { path: "Notes.tex/inner.tex", kind: "text" }))).toMatchObject({ status: 409, code: "file_exists" });

    const fake = new File([text("hello")], "plot.png");
    expect(await failure(uploadFiles(ADA, id, [fake]))).toMatchObject({ status: 415, code: "unsupported_file_type" });
    const big = new Uint8Array(5 * 1024 * 1024 + 1);
    big.set(PNG);
    expect(await failure(uploadFiles(ADA, id, [new File([big], "big.png")]))).toMatchObject({ status: 413, code: "file_too_large" });

    for (let i = 0; i < 198; i += 1) backend.addFile(id, `f${i}.txt`, "x");
    expect(await failure(createFile(ADA, id, { path: "one-more/x.tex", kind: "text" }))).toMatchObject({ status: 413, code: "project_full" });
  });

  it("uploads as multipart without setting its Content-Type, into folders and over existing files", async () => {
    const files = await uploadFiles(ADA, id, [new File([PNG], "plot.png"), new File([text("@book{a}")], "refs.bib")], { folder: "figures" });
    expect(files.map((file) => [file.path, file.kind, file.content_type])).toEqual([
      ["figures/plot.png", "binary", "image/png"],
      ["figures/refs.bib", "text", "text/plain; charset=utf-8"],
    ]);
    const init = fetchMock.mock.calls.at(-1)?.[1];
    expect(new Headers(init?.headers).get("Content-Type")).toBeNull();
    expect(init?.body).toBeInstanceOf(FormData);

    expect(await failure(uploadFiles(ADA, id, [new File([PNG], "plot.png")], { folder: "figures" }))).toMatchObject({ status: 409, code: "file_exists" });
    const [replaced] = await uploadFiles(ADA, id, [new File([PNG, PNG], "plot.png")], { folder: "figures", replace: true });
    expect(replaced).toMatchObject({ version: 2, size: 16 });

    const raw = await fileBlob(ADA, id, replaced?.id ?? "");
    expect(new Uint8Array(await raw.arrayBuffer())).toEqual(new Uint8Array([...PNG, ...PNG]));
    const folder = (await listFiles(ADA, id)).files.find((file) => file.kind === "folder");
    expect(await failure(fileBlob(ADA, id, folder?.id ?? ""))).toMatchObject({ status: 400, code: "not_a_file" });
  });

  it("keeps viewers out of writes, and strangers out entirely", async () => {
    backend.setRole(id, "vic", "viewer");
    await expect(listFiles("fake-token:vic", id)).resolves.toMatchObject({ role: "viewer" });
    expect(await failure(createFile("fake-token:vic", id, { path: "a.tex", kind: "text" }))).toMatchObject({ status: 403, code: "edit_forbidden" });
    expect(await failure(listFiles("fake-token:mallory", id))).toMatchObject({ status: 404, code: "not_found" });
    // Any member compiles.
    await expect(compileProject("fake-token:vic", id)).resolves.toMatchObject({ status: "compiled" });
  });

  it("compiles the project, stores output.pdf and zips everything", async () => {
    expect(await failure(outputPdf(ADA, id))).toMatchObject({ status: 404 });
    backend.addFile(id, "chapters/intro.tex", "Intro");
    const result = await compileProject(ADA, id);
    expect(result.status).toBe("compiled");
    const pdf = await outputPdf(ADA, id);
    expect(new TextDecoder().decode(await pdf.arrayBuffer())).toMatch(/^%PDF-/);
    expect((await listFiles(ADA, id)).output).toMatchObject({ status: "compiled", compiled_by: "ada" });

    const zip = readZip(new Uint8Array(await (await downloadArchive(ADA, id)).arrayBuffer()));
    expect(zip.map((entry) => entry.name)).toEqual(["main.tex", "chapters/", "chapters/intro.tex", "output.pdf"]);
    expect(new TextDecoder().decode(zip[0]?.data)).toBe("\\documentclass{article}");
  });

  it("imports a zip, choosing main.tex or the one root file with \\documentclass", async () => {
    const zip = writeZip([
      { name: "main.tex", data: text("\\documentclass{book}") },
      { name: "other.tex", data: text("\\documentclass{article}") },
      { name: ".hidden/x.tex", data: text("x") },
      { name: "output.pdf", data: text("%PDF-1.4") },
    ]);
    const created = await importProject(ADA, new File([zip], "book.zip"), "My book");
    expect(created).toMatchObject({ title: "My book", role: "owner", skipped: [".hidden/x.tex", "output.pdf"] });
    expect(backend.document(created.id)?.source).toBe("\\documentclass{book}");
    expect(Object.keys(backend.files(created.id))).toEqual(["other.tex"]);

    vi.stubGlobal("fetch", () => Promise.resolve(Response.json({ id: "w", title: "t", owner_id: "o", role: "owner", created_at: "" }, { status: 201 })));
    await expect(importProject(ADA, new File([zip], "book.zip"))).resolves.toMatchObject({ skipped: [] });
  });
});

describe("path rules", () => {
  it("names what's wrong with a path", () => {
    expect(pathProblem("chapters/intro.tex")).toBeNull();
    expect(pathProblem("")).toBe("Enter a name.");
    expect(pathProblem("x".repeat(201))).toMatch(/up to 200/);
    expect(pathProblem("a/b/c/d/e/f/g")).toMatch(/5 folders/);
    expect(pathProblem("/a")).toMatch(/no empty folder/);
    expect(pathProblem("a/.git")).toMatch(/start with a dot/);
    expect(pathProblem("a*b")).toMatch(/letters, digits/);
    expect(pathProblem("OUTPUT.PDF")).toMatch(/reserved/);
    expect(pathProblem("figures/output.pdf")).toBeNull();
    expect(kindForPath("a/b.BIB")).toBe("text");
    expect(kindForPath("plot.jpeg")).toBe("binary");
    expect(kindForPath("Makefile")).toBeNull();
  });

  it("formats sizes", () => {
    expect(formatBytes(1)).toBe("1 byte");
    expect(formatBytes(900)).toBe("900 bytes");
    expect(formatBytes(2048)).toBe("2 KB");
    expect(formatBytes(1.25 * 1024 * 1024)).toBe("1.3 MB");
    expect(formatBytes(10 * 1024 * 1024)).toBe("10 MB");
  });
});
