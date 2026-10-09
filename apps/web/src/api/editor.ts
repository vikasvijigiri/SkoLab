import { apiRequest, ApiError } from "./client";
import type { LatexDocument, Template, TemplateSummary, Workspace } from "./editorTypes";

/**
 * The editor's API: workspaces (one LaTeX document each), the journal
 * template catalog and the document itself. Shapes follow
 * services/backend-go/api/openapi.yaml.
 */

export * from "./editorTypes";

/** An answer that isn't the shape the contract promises (a proxy's error page, say). */
function unexpected() {
  return new ApiError(502, "unexpected_response", "SkoLab sent an answer we couldn't read.");
}

/** The workspaces the caller owns or belongs to, newest first. Follows pages up to 500. */
export async function listWorkspaces(token: string, signal?: AbortSignal): Promise<Workspace[]> {
  const all: Workspace[] = [];
  let pageToken = "";
  for (let page = 0; page < 5; page += 1) {
    const query = new URLSearchParams({ page_size: "100" });
    if (pageToken) query.set("page_token", pageToken);
    const answer = await apiRequest<{ workspaces: Workspace[]; next_page_token: string }>(`/api/v1/workspaces?${query.toString()}`, {
      token,
      ...(signal ? { signal } : {}),
    });
    if (!Array.isArray(answer.workspaces)) throw unexpected();
    all.push(...answer.workspaces);
    pageToken = answer.next_page_token;
    if (!pageToken) break;
  }
  return all;
}

export function getWorkspace(token: string, id: string, signal?: AbortSignal): Promise<Workspace> {
  return apiRequest<Workspace>(`/api/v1/workspaces/${encodeURIComponent(id)}`, { token, ...(signal ? { signal } : {}) });
}

/** Creates a workspace. The key makes a retry of the same click return the same workspace. */
export function createWorkspace(token: string, title: string, idempotencyKey: string): Promise<Workspace> {
  return apiRequest<Workspace>("/api/v1/workspaces", {
    method: "POST",
    token,
    body: { title },
    headers: { "Idempotency-Key": idempotencyKey },
  });
}

export function renameWorkspace(token: string, id: string, title: string): Promise<Workspace> {
  return apiRequest<Workspace>(`/api/v1/workspaces/${encodeURIComponent(id)}`, { method: "PATCH", token, body: { title } });
}

export function deleteWorkspace(token: string, id: string): Promise<void> {
  return apiRequest<undefined>(`/api/v1/workspaces/${encodeURIComponent(id)}`, { method: "DELETE", token });
}

// The catalog only changes with a deploy, so one fetch per page load is enough.
let catalog: Promise<TemplateSummary[]> | null = null;

export function listTemplates(token: string): Promise<TemplateSummary[]> {
  catalog ??= apiRequest<{ templates: TemplateSummary[] }>("/api/v1/templates", { token }).then(
    (answer) => {
      if (!Array.isArray(answer.templates)) throw unexpected();
      return answer.templates;
    },
    (error: unknown) => {
      catalog = null; // let the next caller retry
      throw error;
    },
  );
  return catalog;
}

/** For tests: forget the cached catalog. */
export function resetTemplateCache() {
  catalog = null;
}

export function getTemplate(token: string, id: string): Promise<Template> {
  return apiRequest<Template>(`/api/v1/templates/${encodeURIComponent(id)}`, { token });
}

export function getDocument(token: string, workspaceId: string, signal?: AbortSignal): Promise<LatexDocument> {
  return apiRequest<LatexDocument>(`/api/v1/workspaces/${encodeURIComponent(workspaceId)}/document`, {
    token,
    ...(signal ? { signal } : {}),
  });
}

export interface DocumentSave {
  source: string;
  /** The version this edit started from; 0 for the first save. */
  base_version: number;
  template_id?: string;
}

/** Saves the document. A stale base_version fails with VersionConflictError. */
export async function saveDocument(token: string, workspaceId: string, save: DocumentSave): Promise<LatexDocument> {
  try {
    return await apiRequest<LatexDocument>(`/api/v1/workspaces/${encodeURIComponent(workspaceId)}/document`, {
      method: "PUT",
      token,
      body: save,
    });
  } catch (error) {
    if (error instanceof ApiError && error.code === "version_conflict") {
      const current = error.details.current_version;
      throw new VersionConflictError(typeof current === "number" ? current : 0);
    }
    throw error;
  }
}

/** Someone saved the document after this copy was loaded. */
export class VersionConflictError extends Error {
  readonly currentVersion: number;

  constructor(currentVersion: number) {
    super("version_conflict");
    this.name = "VersionConflictError";
    this.currentVersion = currentVersion;
  }
}
