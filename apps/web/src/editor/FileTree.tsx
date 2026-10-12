import { useId, useRef, useState, type DragEvent, type ReactNode, type SyntheticEvent } from "react";
import { ApiError } from "../api/client";
import {
  BINARY_EXTENSIONS,
  extensionOf,
  formatBytes,
  kindForPath,
  MAX_FILE_BYTES,
  pathProblem,
  TEXT_EXTENSIONS,
  type FileListing,
  type ProjectFile,
} from "../api/editorTypes";
import { createFile, deleteFile, downloadArchive, fileBlob, moveFile, uploadFiles } from "../api/files";
import { useIdToken } from "../auth/useIdToken";
import { Alert } from "../components/Alert";
import { Spinner } from "../components/Spinner";
import { formatDateTime, timeAgo } from "../lib/dates";
import { bytesOf, download, fileName } from "../lib/download";

export type FilesState = { state: "loading" } | { state: "error"; message: string } | { state: "ready"; listing: FileListing };

export type OpenTarget = { kind: "main" } | { kind: "file"; file: ProjectFile } | { kind: "output" };

const ACCEPT = [...TEXT_EXTENSIONS, ...BINARY_EXTENSIONS].map((extension) => `.${extension}`).join(",");

/** What went wrong with a file action, in words. */
export function filesMessage(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.code === "network") return error.message;
    if (error.status === 401) return "Your session expired. Sign in again.";
    if (error.status === 403) return "You can view this project but not change it.";
    if (error.status === 404) return "That file is no longer there. The list is up to date again.";
    if (error.code === "file_exists") return "A file or folder with that name is already there.";
    if (error.code === "invalid_path") return "That name isn't allowed. Use letters, digits, spaces and _ . , ( ) + - only, with / between folders.";
    if (error.code === "reserved_path") return "main.tex and output.pdf at the top of the project are managed for you. Pick another name.";
    if (error.code === "project_full") return "The project is full. It can hold up to 10 MB in up to 200 files and folders.";
    if (error.code === "file_too_large") return "Files can be up to 5 MB, and text files up to 1 MB.";
    if (error.code === "body_too_large") return "Upload up to 12 MB at a time.";
    if (error.code === "unsupported_file_type") return "Projects take text files (.tex, .bib, .sty, .cls and similar) and PNG, JPEG, PDF and EPS images.";
    if (error.code === "invalid_text") return "Text files must be UTF-8 text.";
    if (error.status === 429) return "Too many changes at once. Wait a moment and try again.";
  }
  return "That didn't work. Try again.";
}

interface TreeNode {
  name: string;
  path: string;
  /** Null for a folder the listing implies but doesn't list. */
  file: ProjectFile | null;
  children: TreeNode[];
}

const isFolder = (node: TreeNode) => node.file === null || node.file.kind === "folder";
const baseName = (path: string) => path.slice(path.lastIndexOf("/") + 1);

/** Folders first, then files, each by name. */
function buildTree(files: readonly ProjectFile[]): TreeNode[] {
  const root: TreeNode = { name: "", path: "", file: null, children: [] };
  const folders = new Map<string, TreeNode>([["", root]]);
  const folderAt = (path: string): TreeNode => {
    const found = folders.get(path.toLowerCase());
    if (found) return found;
    const node: TreeNode = { name: baseName(path), path, file: null, children: [] };
    const slash = path.lastIndexOf("/");
    folderAt(slash < 0 ? "" : path.slice(0, slash)).children.push(node);
    folders.set(path.toLowerCase(), node);
    return node;
  };
  for (const file of files) {
    if (file.kind === "folder") {
      folderAt(file.path).file = file;
      continue;
    }
    const slash = file.path.lastIndexOf("/");
    folderAt(slash < 0 ? "" : file.path.slice(0, slash)).children.push({ name: baseName(file.path), path: file.path, file, children: [] });
  }
  const sort = (nodes: TreeNode[]) => {
    nodes.sort((a, b) => Number(isFolder(b)) - Number(isFolder(a)) || a.name.localeCompare(b.name));
    for (const node of nodes) sort(node.children);
  };
  sort(root.children);
  return root.children;
}

const ICONS: Record<string, string> = {
  folder: "M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V7z",
  text: "M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8l-5-5zM14 3v5h5M9 13h6M9 17h4",
  image: "M5 4h14a1 1 0 0 1 1 1v14a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V5a1 1 0 0 1 1-1zM8.5 10a1.5 1.5 0 1 0 0-3 1.5 1.5 0 0 0 0 3zM20 15l-5-5L5 20",
  pdf: "M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8l-5-5zM14 3v5h5M9 15h1.5a1.5 1.5 0 0 0 0-3H9v5",
};

function iconFor(path: string, folder: boolean): { d: string; color: string } {
  if (folder) return { d: ICONS.folder ?? "", color: "text-amber-600 dark:text-amber-400" };
  const extension = extensionOf(path);
  if (["png", "jpg", "jpeg"].includes(extension)) return { d: ICONS.image ?? "", color: "text-emerald-700 dark:text-emerald-400" };
  if (["pdf", "eps"].includes(extension)) return { d: ICONS.pdf ?? "", color: "text-red-700 dark:text-red-400" };
  return { d: ICONS.text ?? "", color: extension === "tex" ? "text-brand-600 dark:text-brand-300" : "text-zinc-500 dark:text-zinc-400" };
}

function Icon({ d, className }: { d: string; className: string }) {
  return (
    <svg viewBox="0 0 24 24" aria-hidden="true" className={`size-4 shrink-0 ${className}`} fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
      <path d={d} />
    </svg>
  );
}

function ToolButton({ label, onClick, disabled, children }: { label: string; onClick: () => void; disabled?: boolean; children: ReactNode }) {
  return (
    <button
      type="button"
      aria-label={label}
      title={label}
      onClick={onClick}
      disabled={disabled}
      className="grid size-8 cursor-pointer place-items-center rounded-md text-zinc-700 hover:bg-zinc-100 hover:text-zinc-900 disabled:cursor-not-allowed disabled:opacity-50 dark:text-zinc-300 dark:hover:bg-zinc-800 dark:hover:text-zinc-100"
    >
      <svg viewBox="0 0 24 24" aria-hidden="true" className="size-[18px]" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
        {children}
      </svg>
    </button>
  );
}

const smallButton =
  "inline-flex h-8 cursor-pointer items-center rounded-md border border-zinc-300 bg-white px-2.5 text-xs font-semibold text-zinc-800 hover:bg-zinc-50 disabled:cursor-not-allowed disabled:opacity-60 dark:border-zinc-700 dark:bg-zinc-900 dark:text-zinc-100 dark:hover:bg-zinc-800";
const dangerButton = "inline-flex h-8 cursor-pointer items-center rounded-md bg-red-700 px-2.5 text-xs font-semibold text-white hover:bg-red-800 disabled:opacity-60";
const inputClass =
  "h-9 w-full min-w-0 rounded-md border border-zinc-300 bg-white px-2 text-sm text-zinc-900 focus:border-brand-500 dark:border-zinc-700 dark:bg-zinc-900 dark:text-zinc-100";

interface FileTreeProps {
  workspaceId: string;
  /** Names the downloaded zip. */
  title: string;
  files: FilesState;
  editable: boolean;
  /** The open file's id; null while main.tex is open. */
  openId: string | null;
  onOpen: (target: OpenTarget) => void;
  /** Fetches the listing again after a change. */
  onReload: () => Promise<void>;
  onDownloadMain: () => void;
  onDownloadOutput: () => void;
}

/**
 * The project's files, the way Overleaf lists them: main.tex first, then
 * folders and files, then the last compiled PDF. Owners and editors create,
 * upload (also by dropping files from the computer), rename, move and
 * delete; everyone can download.
 */
export function FileTree({ workspaceId, title, files, editable, openId, onOpen, onReload, onDownloadMain, onDownloadOutput }: FileTreeProps) {
  const idToken = useIdToken();
  const ids = useId();
  const picker = useRef<HTMLInputElement>(null);
  const [uploadFolder, setUploadFolder] = useState("");
  const [creating, setCreating] = useState<"text" | "folder" | null>(null);
  const [newPath, setNewPath] = useState("");
  // The row whose actions are open: a file id, or "main" / "output".
  const [menu, setMenu] = useState<string | null>(null);
  const [mode, setMode] = useState<"actions" | "rename" | "delete">("actions");
  const [renameTo, setRenameTo] = useState("");
  const [collapsed, setCollapsed] = useState<ReadonlySet<string>>(new Set());
  const [busy, setBusy] = useState<string | null>(null);
  const [failure, setFailure] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [dropTarget, setDropTarget] = useState<string | null>(null);

  async function act(key: string, action: (token: string) => Promise<string | null>) {
    setBusy(key);
    setFailure(null);
    setNotice(null);
    try {
      const done = await action(await idToken());
      setNotice(done);
    } catch (error) {
      setFailure(filesMessage(error));
      if (error instanceof ApiError && error.status === 404) await onReload().catch(() => undefined);
    } finally {
      setBusy(null);
    }
  }

  function toggleMenu(key: string) {
    setMenu(menu === key ? null : key);
    setMode("actions");
  }

  async function upload(list: File[], folder: string) {
    if (list.length === 0) return;
    const refused = list.filter((file) => !kindForPath(file.name));
    if (refused.length > 0) {
      setNotice(null);
      setFailure(`${refused.map((file) => file.name).join(", ")}: ${filesMessage(new ApiError(415, "unsupported_file_type", ""))}`);
      return;
    }
    const large = list.filter((file) => file.size > MAX_FILE_BYTES);
    if (large.length > 0) {
      setNotice(null);
      setFailure(`${large.map((file) => file.name).join(", ")}: ${filesMessage(new ApiError(413, "file_too_large", ""))}`);
      return;
    }
    await act("upload", async (token) => {
      try {
        await uploadFiles(token, workspaceId, list, { folder });
      } catch (error) {
        if (!(error instanceof ApiError && error.code === "file_exists")) throw error;
        const names = list.map((file) => file.name).join(", ");
        if (!window.confirm(`Replace ${names}? A file with the same name is already in ${folder || "the project"}.`)) return null;
        await uploadFiles(token, workspaceId, list, { folder, replace: true });
      }
      await onReload();
      if (folder) setCollapsed((current) => new Set([...current].filter((path) => path !== folder)));
      return list.length === 1 ? `Uploaded ${list[0]?.name ?? "1 file"}.` : `Uploaded ${list.length} files.`;
    });
  }

  function pickFiles(folder: string) {
    setUploadFolder(folder);
    setMenu(null);
    picker.current?.click();
  }

  function startCreating(kind: "text" | "folder", folder = "") {
    setCreating(kind);
    setNewPath(folder ? `${folder}/` : "");
    setFailure(null);
    setMenu(null);
  }

  function create(event: SyntheticEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!creating) return;
    let path = newPath.trim().replace(/\/+$/, "");
    if (creating === "text" && !extensionOf(path) && path) path = `${path}.tex`;
    const problem = pathProblem(path) ?? (creating === "text" && kindForPath(path) !== "text" ? "Text files need a text extension such as .tex or .bib. Upload images instead." : null);
    if (problem) {
      setFailure(problem);
      return;
    }
    const kind = creating;
    void act("create", async (token) => {
      const created = await createFile(token, workspaceId, { path, kind });
      setCreating(null);
      setNewPath("");
      await onReload();
      if (kind === "text") onOpen({ kind: "file", file: created });
      return `Created ${path}.`;
    });
  }

  function rename(event: SyntheticEvent<HTMLFormElement>, file: ProjectFile) {
    event.preventDefault();
    const path = renameTo.trim().replace(/\/+$/, "");
    if (path === file.path) {
      setMenu(null);
      return;
    }
    const problem = pathProblem(path) ?? (file.kind !== "folder" && kindForPath(path) !== file.kind ? "Keep the file's type when you rename it." : null);
    if (problem) {
      setFailure(problem);
      return;
    }
    void act(file.id, async (token) => {
      await moveFile(token, workspaceId, file.id, path);
      setMenu(null);
      await onReload();
      return `Renamed to ${path}.`;
    });
  }

  function remove(file: ProjectFile) {
    void act(file.id, async (token) => {
      await deleteFile(token, workspaceId, file.id);
      setMenu(null);
      await onReload();
      return `Deleted ${file.path}.`;
    });
  }

  function downloadFile(file: ProjectFile) {
    void act(file.id, async (token) => {
      const bytes = await bytesOf(await fileBlob(token, workspaceId, file.id));
      download(baseName(file.path), bytes, file.content_type);
      return null;
    });
  }

  function dropZone(folder: string) {
    if (!editable) return {};
    return {
      onDragOver: (event: DragEvent) => {
        if (!Array.from(event.dataTransfer.types).includes("Files")) return;
        event.preventDefault();
        event.stopPropagation();
        setDropTarget(folder);
      },
      onDragLeave: (event: DragEvent) => {
        event.stopPropagation();
        setDropTarget((current) => (current === folder ? null : current));
      },
      onDrop: (event: DragEvent) => {
        event.preventDefault();
        event.stopPropagation();
        setDropTarget(null);
        void upload(Array.from(event.dataTransfer.files), folder);
      },
    };
  }

  const rowButton =
    "flex min-h-8 min-w-0 flex-1 cursor-pointer items-center gap-2 rounded-md px-2 py-1 text-left text-sm text-zinc-800 hover:bg-zinc-100 aria-[current=true]:bg-brand-50 aria-[current=true]:font-semibold aria-[current=true]:text-brand-800 dark:text-zinc-200 dark:hover:bg-zinc-800 dark:aria-[current=true]:bg-brand-950 dark:aria-[current=true]:text-brand-100";

  function actionsButton(key: string, label: string) {
    return (
      <button
        type="button"
        aria-label={`Actions for ${label}`}
        aria-expanded={menu === key}
        aria-controls={menu === key ? `${ids}-${key}` : undefined}
        onClick={() => toggleMenu(key)}
        className="grid size-8 shrink-0 cursor-pointer place-items-center rounded-md text-zinc-600 hover:bg-zinc-100 hover:text-zinc-900 dark:text-zinc-400 dark:hover:bg-zinc-800 dark:hover:text-zinc-100"
      >
        <svg viewBox="0 0 24 24" aria-hidden="true" className="size-4" fill="currentColor">
          <circle cx="5" cy="12" r="1.8" />
          <circle cx="12" cy="12" r="1.8" />
          <circle cx="19" cy="12" r="1.8" />
        </svg>
      </button>
    );
  }

  function panel(key: string, children: ReactNode) {
    return (
      <div id={`${ids}-${key}`} className="mb-1 ml-6 flex flex-wrap gap-1.5 rounded-md bg-zinc-50 p-1.5 dark:bg-zinc-900">
        {children}
      </div>
    );
  }

  function itemActions(file: ProjectFile) {
    const folder = file.kind === "folder";
    if (mode === "rename") {
      return (
        <form onSubmit={(event) => rename(event, file)} className="w-full space-y-1.5">
          <label htmlFor={`${ids}-rename`} className="block text-xs font-medium text-zinc-700 dark:text-zinc-300">
            New name or path for {baseName(file.path)}
          </label>
          <input id={`${ids}-rename`} autoFocus value={renameTo} onChange={(event) => setRenameTo(event.target.value)} className={inputClass} />
          <div className="flex gap-1.5">
            <button type="submit" className={smallButton} disabled={busy === file.id}>
              Save
            </button>
            <button type="button" className={smallButton} onClick={() => setMenu(null)}>
              Cancel
            </button>
          </div>
        </form>
      );
    }
    if (mode === "delete") {
      return (
        <div className="w-full space-y-1.5">
          <p className="text-xs text-zinc-700 dark:text-zinc-300">{folder ? `Delete ${file.path} and everything in it?` : `Delete ${file.path}?`} This can't be undone.</p>
          <div className="flex gap-1.5">
            <button type="button" aria-label={`Confirm deleting ${file.path}`} className={dangerButton} disabled={busy === file.id} onClick={() => remove(file)}>
              Delete
            </button>
            <button type="button" className={smallButton} onClick={() => setMenu(null)}>
              Cancel
            </button>
          </div>
        </div>
      );
    }
    return (
      <>
        {folder && editable && (
          <>
            <button type="button" className={smallButton} onClick={() => startCreating("text", file.path)}>
              New file here
            </button>
            <button type="button" className={smallButton} disabled={busy === "upload"} onClick={() => pickFiles(file.path)}>
              Upload here
            </button>
          </>
        )}
        {!folder && (
          <button type="button" className={smallButton} disabled={busy === file.id} onClick={() => downloadFile(file)}>
            Download
          </button>
        )}
        {editable && (
          <>
            <button
              type="button"
              className={smallButton}
              onClick={() => {
                setMode("rename");
                setRenameTo(file.path);
              }}
            >
              Rename or move
            </button>
            <button type="button" className={smallButton} onClick={() => setMode("delete")}>
              Delete
            </button>
          </>
        )}
      </>
    );
  }

  function renderNodes(nodes: readonly TreeNode[], depth: number): ReactNode {
    return nodes.map((node) => {
      const folder = isFolder(node);
      const open = !collapsed.has(node.path);
      const icon = iconFor(node.path, folder);
      const { file } = node;
      const hasActions = file !== null && (editable || !folder);
      return (
        <li key={node.path}>
          <div
            className={`flex items-center gap-0.5 rounded-md ${dropTarget === node.path && folder ? "bg-brand-50 ring-2 ring-brand-500 dark:bg-brand-950" : ""}`}
            style={{ paddingLeft: `${depth * 12}px` }}
            {...(folder ? dropZone(node.path) : {})}
          >
            {folder ? (
              <button
                type="button"
                aria-expanded={open}
                onClick={() => setCollapsed((current) => (open ? new Set([...current, node.path]) : new Set([...current].filter((path) => path !== node.path))))}
                className={rowButton}
              >
                <svg viewBox="0 0 24 24" aria-hidden="true" className={`size-3 shrink-0 text-zinc-500 transition-transform ${open ? "rotate-90" : ""}`} fill="currentColor">
                  <path d="M8 5l8 7-8 7z" />
                </svg>
                <Icon d={icon.d} className={icon.color} />
                <span className="truncate">{node.name}</span>
              </button>
            ) : (
              <button type="button" aria-current={file?.id === openId} onClick={() => file && onOpen({ kind: "file", file })} className={rowButton}>
                <span className="w-3 shrink-0" />
                <Icon d={icon.d} className={icon.color} />
                <span className="truncate">{node.name}</span>
              </button>
            )}
            {hasActions && actionsButton(file.id, node.path)}
          </div>
          {file && menu === file.id && panel(file.id, itemActions(file))}
          {folder && open && node.children.length > 0 && <ul>{renderNodes(node.children, depth + 1)}</ul>}
        </li>
      );
    });
  }

  if (files.state === "loading") {
    return (
      <div role="status" className="flex items-center gap-2 p-3 text-sm text-zinc-600 dark:text-zinc-400">
        <Spinner /> Loading files…
      </div>
    );
  }
  if (files.state === "error") {
    return (
      <div className="space-y-2 p-3">
        <Alert tone="error">{files.message}</Alert>
        <button type="button" className={smallButton} onClick={() => void onReload().catch(() => undefined)}>
          Try again
        </button>
      </div>
    );
  }

  const { listing } = files;
  const tree = buildTree(listing.files);
  const mainIcon = iconFor("main.tex", false);
  const pdfIcon = iconFor("output.pdf", false);

  return (
    <div className="flex h-full min-h-0 flex-col" {...dropZone("")}>
      <div className="flex items-center justify-between gap-1 border-b border-zinc-200 py-1 pr-1 pl-3 dark:border-zinc-800">
        <h2 className="text-xs font-semibold tracking-wide text-zinc-700 uppercase dark:text-zinc-300">Files</h2>
        <div className="flex items-center">
          {editable && (
            <>
              <ToolButton label="New file" onClick={() => startCreating("text")}>
                <path d="M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8l-5-5zM14 3v5h5M12 11v6M9 14h6" />
              </ToolButton>
              <ToolButton label="New folder" onClick={() => startCreating("folder")}>
                <path d="M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V7zM12 10v6M9 13h6" />
              </ToolButton>
              <ToolButton label="Upload files" disabled={busy === "upload"} onClick={() => pickFiles("")}>
                <path d="M12 16V4M7 9l5-5 5 5M4 16v3a1 1 0 0 0 1 1h14a1 1 0 0 0 1-1v-3" />
              </ToolButton>
            </>
          )}
          <ToolButton
            label="Download project as .zip"
            disabled={busy === "archive"}
            onClick={() =>
              void act("archive", async (token) => {
                download(`${fileName(title)}.zip`, await bytesOf(await downloadArchive(token, workspaceId)), "application/zip");
                return null;
              })
            }
          >
            <path d="M12 4v12M7 11l5 5 5-5M4 16v3a1 1 0 0 0 1 1h14a1 1 0 0 0 1-1v-3" />
          </ToolButton>
        </div>
      </div>
      <input
        ref={picker}
        type="file"
        multiple
        hidden
        accept={ACCEPT}
        aria-label="Choose files to upload"
        onChange={(event) => {
          const list = Array.from(event.target.files ?? []);
          event.target.value = "";
          void upload(list, uploadFolder);
        }}
      />

      {creating && (
        <form onSubmit={create} className="space-y-1.5 border-b border-zinc-200 p-2 dark:border-zinc-800">
          <label htmlFor={`${ids}-new`} className="block text-xs font-medium text-zinc-700 dark:text-zinc-300">
            {creating === "folder" ? "New folder name" : "New file name"}
          </label>
          <input
            id={`${ids}-new`}
            autoFocus
            value={newPath}
            placeholder={creating === "folder" ? "figures" : "chapters/intro.tex"}
            onChange={(event) => setNewPath(event.target.value)}
            onKeyDown={(event) => {
              if (event.key === "Escape") setCreating(null);
            }}
            className={inputClass}
          />
          <p className="text-xs text-zinc-600 dark:text-zinc-400">Use / to put it in a folder.</p>
          <div className="flex gap-1.5">
            <button type="submit" className={smallButton} disabled={busy === "create"}>
              Create
            </button>
            <button type="button" className={smallButton} onClick={() => setCreating(null)}>
              Cancel
            </button>
          </div>
        </form>
      )}

      {(failure ?? notice) && (
        <div className="p-2">
          {failure ? <Alert tone="error">{failure}</Alert> : <Alert tone="success">{notice}</Alert>}
        </div>
      )}
      {busy === "upload" && (
        <p role="status" className="flex items-center gap-2 px-3 py-1 text-xs text-zinc-600 dark:text-zinc-400">
          <Spinner /> Uploading…
        </p>
      )}

      <div className={`min-h-0 flex-1 overflow-y-auto p-1.5 ${dropTarget === "" ? "bg-brand-50 ring-2 ring-brand-500 ring-inset dark:bg-brand-950" : ""}`}>
        <ul aria-label="Project files">
          <li>
            <div className="flex items-center gap-0.5">
              <button type="button" aria-current={openId === null} onClick={() => onOpen({ kind: "main" })} className={rowButton}>
                <span className="w-3 shrink-0" />
                <Icon d={mainIcon.d} className={mainIcon.color} />
                <span className="truncate">main.tex</span>
                <span className="sr-only"> (main document)</span>
              </button>
              {actionsButton("main", "main.tex")}
            </div>
            {menu === "main" &&
              panel(
                "main",
                <button type="button" className={smallButton} onClick={onDownloadMain}>
                  Download
                </button>,
              )}
          </li>
          {renderNodes(tree, 0)}
          {listing.output && (
            <li>
              <div className="flex items-center gap-0.5">
                <button type="button" onClick={() => onOpen({ kind: "output" })} className={rowButton}>
                  <span className="w-3 shrink-0" />
                  <Icon d={pdfIcon.d} className={pdfIcon.color} />
                  <span className="min-w-0">
                    <span className="block truncate">output.pdf</span>{" "}
                    <span className="block truncate text-xs font-normal text-zinc-600 dark:text-zinc-400" title={formatDateTime(listing.output.compiled_at)}>
                      Compiled {timeAgo(listing.output.compiled_at)}
                    </span>
                  </span>
                </button>
                {actionsButton("output", "output.pdf")}
              </div>
              {menu === "output" &&
                panel(
                  "output",
                  <button type="button" className={smallButton} onClick={onDownloadOutput}>
                    Download
                  </button>,
                )}
            </li>
          )}
        </ul>
      </div>

      <p className="border-t border-zinc-200 px-3 py-2 text-xs text-zinc-600 dark:border-zinc-800 dark:text-zinc-400">
        {formatBytes(listing.usage.bytes)} of {formatBytes(listing.usage.limit_bytes)} used · {listing.usage.entries} of {listing.usage.limit_entries} items
        {editable && <span className="block">Drop files here to upload them.</span>}
      </p>
    </div>
  );
}
