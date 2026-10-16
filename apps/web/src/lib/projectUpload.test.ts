import { describe, expect, it } from "vitest";
import { readZip } from "../test/zip";
import { droppedFiles, packProject, PickProblem, type PickedFile } from "./projectUpload";

const picked = (path: string, content: BlobPart = "x"): PickedFile => ({ path, file: new File([content], path.split("/").pop() ?? path) });
const names = async (file: File) => readZip(new Uint8Array(await file.arrayBuffer())).map((entry) => entry.name);

/** A dropped file or folder, as the File and Directory Entries API hands it over. */
function entry(fullPath: string, children?: FileSystemEntry[]): FileSystemEntry {
  const name = fullPath.split("/").pop() ?? "";
  if (!children) {
    return { isFile: true, isDirectory: false, name, fullPath, file: (done: (file: File) => void) => done(new File([name], name)) } as unknown as FileSystemEntry;
  }
  return {
    isFile: false,
    isDirectory: true,
    name,
    fullPath,
    // Browsers answer in batches (Chrome: 100 at a time), then with none.
    createReader: () => {
      const batches = [children.slice(0, 2), children.slice(2), []];
      return { readEntries: (done: (entries: FileSystemEntry[]) => void) => done(batches.shift() ?? []) };
    },
  } as unknown as FileSystemEntry;
}

function transfer(entries: FileSystemEntry[]): DataTransfer {
  return { items: entries.map((value) => ({ kind: "file", webkitGetAsEntry: () => value })), files: [] } as unknown as DataTransfer;
}

describe("packProject", () => {
  it("passes a lone zip through untouched", async () => {
    const zip = new File(["PK"], "paper.zip");
    expect(await packProject([{ path: "paper.zip", file: zip }])).toEqual({ archive: zip, skipped: [] });
  });

  it("unwraps a picked folder, names the project after it, and adds its subfolders", async () => {
    const packed = await packProject([picked("Thesis/main.tex"), picked("Thesis/chapters/one/a.tex"), picked("Thesis/figs/b.png")]);
    expect(packed.title).toBe("Thesis");
    expect(packed.archive.name).toBe("Thesis.zip");
    expect(await names(packed.archive)).toEqual(["chapters/", "chapters/one/", "figs/", "main.tex", "chapters/one/a.tex", "figs/b.png"]);
  });

  it("drops build output, version control and a file's own compiled PDF; reports what a project can't hold", async () => {
    const packed = await packProject([
      picked("paper.tex"),
      picked("paper.aux"),
      picked("paper.synctex.gz"),
      picked("paper.pdf"),
      picked("figure.pdf"),
      picked(".git/HEAD"),
      picked("__MACOSX/._paper.tex"),
      picked("data.xlsx"),
    ]);
    expect(await names(packed.archive)).toEqual(["paper.tex", "figure.pdf"]);
    expect(packed.skipped).toEqual(["data.xlsx"]);
    expect(packed.title).toBe("paper");
  });

  it("leaves the title to the server when loose files hold several .tex files", async () => {
    expect((await packProject([picked("a.tex"), picked("b.tex")])).title).toBeUndefined();
  });

  it("refuses a pick with no .tex file, and one too large to send", async () => {
    await expect(packProject([picked("notes.txt")])).rejects.toMatchObject({ code: "no_tex_file" });
    const huge = picked("main.tex");
    Object.defineProperty(huge.file, "size", { value: 13 * 1024 * 1024 });
    await expect(packProject([huge])).rejects.toBeInstanceOf(PickProblem);
  });
});

describe("droppedFiles", () => {
  it("walks dropped folders to the bottom, through every batch, skipping hidden folders", async () => {
    const dropped = transfer([
      entry("/paper", [entry("/paper/main.tex"), entry("/paper/.git", [entry("/paper/.git/HEAD")]), entry("/paper/sec", [entry("/paper/sec/a.tex")]), entry("/paper/refs.bib")]),
      entry("/cover.tex"),
    ]);
    expect((await droppedFiles(dropped)).map((item) => item.path)).toEqual(["paper/main.tex", "paper/sec/a.tex", "paper/refs.bib", "cover.tex"]);
  });

  it("falls back to the plain file list where entries are unavailable", async () => {
    const file = new File(["x"], "main.tex");
    const plain = { items: [{ kind: "file", webkitGetAsEntry: () => null }], files: [file] } as unknown as DataTransfer;
    expect(await droppedFiles(plain)).toEqual([{ path: "main.tex", file }]);
  });
});
