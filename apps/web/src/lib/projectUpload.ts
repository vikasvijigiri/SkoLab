import { ApiError } from "../api/client";
import { BINARY_EXTENSIONS, extensionOf, MAX_UPLOAD_BYTES, TEXT_EXTENSIONS } from "../api/editorTypes";
import { writeZip, type ZipEntry } from "./zip";

/**
 * Getting a LaTeX project in the way Overleaf does: a .zip, a whole folder, or
 * loose .tex files (with their .bib and figures), picked or dropped. Anything
 * that is not already a zip is packed into one in the browser, so the import
 * route has a single shape to check.
 */

/** A picked or dropped file and where it sits in the project ("sections/intro.tex"). */
export interface PickedFile {
  path: string;
  file: File;
}

export interface PackedProject {
  archive: File;
  /** The new document's title, when the pick names one (a folder, a lone .tex file). */
  title?: string;
  /** Files left out because a project can't hold them. */
  skipped: string[];
}

const KEPT = new Set<string>([...TEXT_EXTENSIONS, ...BINARY_EXTENSIONS]);

/** What a TeX run leaves behind: rebuilt on every compile, never part of the source. */
const BUILD_OUTPUT = new Set(["aux", "log", "out", "toc", "lof", "lot", "fls", "fdb_latexmk", "synctex", "gz", "bbl", "blg", "bcf", "xml", "nav", "snm", "vrb", "xdv", "dvi", "idx", "ilg", "ind", "glo", "gls", "glg", "acn", "acr", "alg", "loa", "thm", "spl"]);

/** Folders and files no project wants: version control, editors, OS litter, dependencies. */
function noise(path: string): boolean {
  return path.split("/").some((segment) => segment.startsWith(".") || segment === "__MACOSX" || segment === "node_modules" || segment === "Thumbs.db" || segment === "desktop.ini");
}

const clean = (path: string) => path.replace(/\\/g, "/").replace(/^\/+/, "");

/** Files from a file input: a folder pick carries each file's path inside the folder. */
export function pickedFiles(list: FileList | readonly File[]): PickedFile[] {
  return Array.from(list, (file) => ({ path: clean(file.webkitRelativePath || file.name), file }));
}

function fileOf(entry: FileSystemFileEntry): Promise<File> {
  return new Promise((resolve, reject) => entry.file(resolve, reject));
}

/** Every entry of a dropped folder: readEntries answers in batches until it answers none. */
async function entriesOf(folder: FileSystemDirectoryEntry): Promise<FileSystemEntry[]> {
  const reader = folder.createReader();
  const all: FileSystemEntry[] = [];
  for (;;) {
    const batch = await new Promise<FileSystemEntry[]>((resolve, reject) => reader.readEntries(resolve, reject));
    if (batch.length === 0) return all;
    all.push(...batch);
  }
}

async function walk(entry: FileSystemEntry, into: PickedFile[]): Promise<void> {
  const path = clean(entry.fullPath || entry.name);
  if (noise(path)) return;
  if (entry.isFile) into.push({ path, file: await fileOf(entry as FileSystemFileEntry) });
  else if (entry.isDirectory) for (const child of await entriesOf(entry as FileSystemDirectoryEntry)) await walk(child, into);
}

/**
 * What was dropped: files and whole folders. Call it inside the drop handler,
 * before any await; the browser empties the transfer once the event ends.
 */
export function droppedFiles(transfer: DataTransfer): Promise<PickedFile[]> {
  const entries = Array.from(transfer.items, (item) => (item.kind === "file" ? item.webkitGetAsEntry() : null));
  if (entries.length === 0 || entries.some((entry) => entry === null)) return Promise.resolve(pickedFiles(transfer.files));
  return (async () => {
    const picked: PickedFile[] = [];
    for (const entry of entries) if (entry) await walk(entry, picked);
    return picked;
  })();
}

/** The one folder everything sits in ("thesis/" when a folder was picked), or "". */
function commonRoot(paths: readonly string[]): string {
  const first = paths[0]?.split("/")[0] ?? "";
  if (!first || paths.some((path) => !path.startsWith(`${first}/`))) return "";
  return first;
}

/** Why a pick can't become a project, as the import route would say it. */
export class PickProblem extends ApiError {}

/** Turns a pick into one archive for the import route: a lone .zip as it is, anything else packed. */
export async function packProject(picked: readonly PickedFile[]): Promise<PackedProject> {
  const only = picked.length === 1 ? picked[0] : undefined;
  if (only && extensionOf(only.path) === "zip") return { archive: only.file, skipped: [] };

  const root = commonRoot(picked.map((item) => item.path));
  const files = picked
    .map((item) => ({ ...item, path: root ? item.path.slice(root.length + 1) : item.path }))
    .filter((item) => item.path && !noise(item.path));
  const sources = new Set(files.filter((item) => extensionOf(item.path) === "tex").map((item) => item.path.slice(0, -4).toLowerCase()));
  if (sources.size === 0) throw new PickProblem(400, "no_tex_file", "Pick a .zip, a project folder, or .tex files");

  const skipped: string[] = [];
  const kept: PickedFile[] = [];
  for (const item of files) {
    const extension = extensionOf(item.path);
    if (BUILD_OUTPUT.has(extension)) continue;
    // paper.pdf beside paper.tex is that file's last compile, not a figure.
    if (extension === "pdf" && sources.has(item.path.slice(0, -4).toLowerCase())) continue;
    if (KEPT.has(extension)) kept.push(item);
    else skipped.push(item.path);
  }
  const size = kept.reduce((sum, item) => sum + item.file.size, 0);
  if (size > MAX_UPLOAD_BYTES) throw new PickProblem(413, "body_too_large", "The project is too large");

  const folders = new Set<string>();
  for (const item of kept) {
    const parts = item.path.split("/").slice(0, -1);
    for (let n = 1; n <= parts.length; n += 1) folders.add(`${parts.slice(0, n).join("/")}/`);
  }
  const entries: ZipEntry[] = [...folders].sort().map((name) => ({ name, data: new Uint8Array() }));
  for (const item of kept) entries.push({ name: item.path, data: new Uint8Array(await item.file.arrayBuffer()) });

  const lone = kept.filter((item) => extensionOf(item.path) === "tex");
  const title = root || (lone.length === 1 ? (lone[0]?.path.split("/").pop()?.replace(/\.tex$/i, "") ?? "") : "");
  const archive = new File([writeZip(entries)], `${title || "Imported project"}.zip`, { type: "application/zip" });
  return { archive, ...(title ? { title } : {}), skipped };
}
