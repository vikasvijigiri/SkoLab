import { apiBlob, apiRequest, ApiError } from "./client";
import type { CompileResult } from "./compile";
import { VersionConflictError } from "./editor";
import type { FileListing, ImportedWorkspace, ProjectFile, TextFile } from "./editorTypes";

/**
 * A project's files beside main.tex: folders, text files (.tex, .bib, …),
 * figures, the whole project as a zip, and the last compiled PDF.
 * main.tex itself stays on the document API (src/api/editor.ts).
 */

const workspacePath = (id: string) => `/api/v1/workspaces/${encodeURIComponent(id)}`;
const filePath = (id: string, fileId: string) => `${workspacePath(id)}/files/${encodeURIComponent(fileId)}`;
const withSignal = (signal?: AbortSignal) => (signal ? { signal } : {});

// Uploads and compiles carry more than an ordinary call.
const LONG_MS = 60_000;

export function listFiles(token: string, workspaceId: string, signal?: AbortSignal): Promise<FileListing> {
  return apiRequest<FileListing>(`${workspacePath(workspaceId)}/files`, { token, ...withSignal(signal) });
}

/** Creates an empty folder or a text file. Missing parent folders are created too. */
export function createFile(token: string, workspaceId: string, entry: { path: string; kind: "text" | "folder"; content?: string }): Promise<ProjectFile & { content?: string }> {
  return apiRequest(`${workspacePath(workspaceId)}/files`, { method: "POST", token, body: entry });
}

/** Uploads files into a folder ("" is the top of the project). replace overwrites files with the same path. */
export async function uploadFiles(token: string, workspaceId: string, files: readonly File[], options: { folder?: string; replace?: boolean } = {}): Promise<ProjectFile[]> {
  const form = new FormData();
  for (const file of files) form.append("file", file, file.name);
  if (options.folder) form.append("folder", options.folder);
  if (options.replace) form.append("replace", "true");
  const answer = await apiRequest<{ files: ProjectFile[] }>(`${workspacePath(workspaceId)}/files/upload`, { method: "POST", token, body: form, timeoutMs: LONG_MS });
  return answer.files;
}

/** A file's details, with its content when it is a text file. */
export function getFile(token: string, workspaceId: string, fileId: string, signal?: AbortSignal): Promise<TextFile> {
  return apiRequest<TextFile>(filePath(workspaceId, fileId), { token, ...withSignal(signal) });
}

/** A file's bytes, for images, PDFs and downloads. */
export function fileBlob(token: string, workspaceId: string, fileId: string, signal?: AbortSignal): Promise<Blob> {
  return apiBlob(`${filePath(workspaceId, fileId)}/raw`, { token, timeoutMs: LONG_MS, ...withSignal(signal) });
}

/** Saves a text file. A stale base_version fails with VersionConflictError. */
export async function saveFile(token: string, workspaceId: string, fileId: string, save: { content: string; base_version: number }): Promise<TextFile> {
  try {
    return await apiRequest<TextFile>(filePath(workspaceId, fileId), { method: "PUT", token, body: save });
  } catch (error) {
    if (error instanceof ApiError && error.code === "version_conflict") {
      const current = error.details.current_version;
      throw new VersionConflictError(typeof current === "number" ? current : 0);
    }
    throw error;
  }
}

/** Renames or moves a file or folder (a folder takes its contents along). */
export function moveFile(token: string, workspaceId: string, fileId: string, path: string): Promise<ProjectFile> {
  return apiRequest<ProjectFile>(filePath(workspaceId, fileId), { method: "PATCH", token, body: { path } });
}

/** Deletes a file, or a folder and everything in it. */
export function deleteFile(token: string, workspaceId: string, fileId: string): Promise<void> {
  return apiRequest<undefined>(filePath(workspaceId, fileId), { method: "DELETE", token });
}

/** The whole project as a zip: main.tex, every file and folder, and output.pdf when there is one. */
export function downloadArchive(token: string, workspaceId: string): Promise<Blob> {
  return apiBlob(`${workspacePath(workspaceId)}/archive`, { token, timeoutMs: LONG_MS });
}

/**
 * Compiles main.tex with every file of the project, so \input, \includegraphics
 * and bibliographies work. Charges the caller's quota; a "compiled" answer
 * becomes the project's output.pdf.
 */
export function compileProject(token: string, workspaceId: string, signal?: AbortSignal): Promise<CompileResult> {
  return apiRequest<CompileResult>(`${workspacePath(workspaceId)}/compile`, { method: "POST", token, body: {}, timeoutMs: LONG_MS, ...withSignal(signal) });
}

/** The PDF the last successful compile stored (404 when there is none). */
export function outputPdf(token: string, workspaceId: string, signal?: AbortSignal): Promise<Blob> {
  return apiBlob(`${workspacePath(workspaceId)}/output.pdf`, { token, timeoutMs: LONG_MS, ...withSignal(signal) });
}

/** Makes a new workspace from a .zip of a LaTeX project. */
export async function importProject(token: string, zip: File, title?: string): Promise<ImportedWorkspace> {
  const form = new FormData();
  form.append("file", zip, zip.name);
  if (title) form.append("title", title);
  const created = await apiRequest<ImportedWorkspace>("/api/v1/workspaces/import", { method: "POST", token, body: form, timeoutMs: LONG_MS });
  return { ...created, skipped: Array.isArray(created.skipped) ? created.skipped : [] };
}
