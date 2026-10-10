import {
  characterCount,
  kindForPath,
  MAX_FILE_BYTES,
  MAX_PROJECT_BYTES,
  MAX_PROJECT_ENTRIES,
  MAX_TEXT_FILE_BYTES,
  MAX_UPLOAD_BYTES,
  pathProblem,
  type FileListing,
  type Insights,
  type Invite,
  type LatexDocument,
  type Member,
  type ProjectFile,
  type ProjectOutput,
  type Role,
  type Template,
  type Workspace,
} from "../api/editorTypes";
import { readZip, writeZip, type ZipEntry } from "./zip";

/**
 * An in-memory stand-in for the gateway's editor endpoints (workspaces,
 * templates, documents), with the same status codes and error codes as
 * services/backend-go. Shared by the unit tests (stubApi) and the browser
 * tests (page.route), so both exercise the app against one behaviour.
 * The caller is the uid in "Authorization: Bearer fake-token:<uid>", which
 * is what the fake auth service issues.
 */

export interface FakeAnswer {
  status: number;
  body?: unknown;
  headers?: Record<string, string>;
}

interface StoredFile {
  file: ProjectFile;
  /** Text files. */
  text: string | null;
  /** Binary files. */
  bytes: Uint8Array | null;
}

interface StoredWorkspace {
  workspace: Omit<Workspace, "role">;
  members: Map<string, Role>;
  since: Map<string, string>;
  document: Omit<LatexDocument, "workspace_id" | "role" | "insights"> | null;
  files: StoredFile[];
  output: { bytes: Uint8Array; info: ProjectOutput } | null;
}

/** One part of a multipart body that carried a file. */
export interface FakeUpload {
  field: string;
  name: string;
  type: string;
  bytes: Uint8Array;
}

/** A multipart/form-data body, as each test harness decodes it. */
export interface FakeForm {
  form: true;
  fields: Record<string, string>;
  files: FakeUpload[];
  /** Bytes of the whole body. */
  size: number;
}

export function isFakeForm(body: unknown): body is FakeForm {
  return typeof body === "object" && body !== null && (body as { form?: unknown }).form === true;
}

/** What POST /workspaces/:id/compile hands the compiler. */
export interface FakeCompileInput {
  latex_source: string;
  engine: "pdflatex";
  files: { path: string; content_base64: string }[];
}

/** A one-page PDF, enough to stand for a compile's output. */
export const TINY_PDF = new TextEncoder().encode(
  "%PDF-1.4\n1 0 obj<</Type/Catalog/Pages 2 0 R>>endobj\n2 0 obj<</Type/Pages/Kids[3 0 R]/Count 1>>endobj\n3 0 obj<</Type/Page/Parent 2 0 R/MediaBox[0 0 200 200]>>endobj\ntrailer<</Root 1 0 R>>\n%%EOF\n",
);

export function toBase64(bytes: Uint8Array): string {
  let binary = "";
  for (let i = 0; i < bytes.length; i += 0x8000) binary += String.fromCharCode(...bytes.subarray(i, i + 0x8000));
  return btoa(binary);
}

export function fromBase64(base64: string): Uint8Array {
  const binary = atob(base64);
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i += 1) bytes[i] = binary.charCodeAt(i);
  return bytes;
}

const utf8 = (text: string) => new TextEncoder().encode(text);

const CONTENT_TYPES: Record<string, string> = { png: "image/png", jpg: "image/jpeg", jpeg: "image/jpeg", pdf: "application/pdf", eps: "application/postscript" };

function contentTypeFor(path: string): string {
  const extension = path.slice(path.lastIndexOf(".") + 1).toLowerCase();
  return CONTENT_TYPES[extension] ?? "text/plain; charset=utf-8";
}

function startsWith(bytes: Uint8Array, prefix: number[]): boolean {
  return prefix.every((byte, index) => bytes[index] === byte);
}

/** The bytes look like what the extension says (PNG, JPEG, PDF, EPS). */
function magicMatches(path: string, bytes: Uint8Array): boolean {
  const extension = path.slice(path.lastIndexOf(".") + 1).toLowerCase();
  if (extension === "png") return startsWith(bytes, [0x89, 0x50, 0x4e, 0x47]);
  if (extension === "jpg" || extension === "jpeg") return startsWith(bytes, [0xff, 0xd8, 0xff]);
  if (extension === "pdf") return startsWith(bytes, [...utf8("%PDF-")]);
  return startsWith(bytes, [...utf8("%!PS")]);
}

/** UTF-8 text with no control characters but tab, CR and LF; null otherwise. */
function decodeText(bytes: Uint8Array): string | null {
  try {
    const text = new TextDecoder("utf-8", { fatal: true }).decode(bytes);
    // eslint-disable-next-line no-control-regex
    return /[\u0000-\u0008\u000B\u000C\u000E-\u001F\u007F]/.test(text) ? null : text;
  } catch {
    return null;
  }
}

const lower = (path: string) => path.toLowerCase();

interface StoredInvite {
  invite: Invite;
  workspaceId: string;
  token: string;
}

export interface FakeRequest {
  method: string;
  /** Path and query, e.g. /api/v1/workspaces?page_size=100 */
  url: string;
  headers: Record<string, string | undefined>;
  body: unknown;
}

const MAX_DOCUMENT = 100_000;

function error(status: number, code: string, message: string, extra: Record<string, unknown> = {}): FakeAnswer {
  return { status, body: { error: message, code, ...extra } };
}

const RANK: Record<Role, number> = { viewer: 0, commenter: 1, editor: 2, owner: 3 };
const GRANTABLE = ["editor", "commenter", "viewer"];
const EXPIRIES = [24, 168, 720];
const MAX_USES = [1, 10, null];

/**
 * A small stand-in for the gateway's reading of the source (internal/manuscript):
 * enough structure for the UI to show, not the real heuristics.
 */
export function fakeInsights(source: string): Insights {
  const body = /\\begin\{document\}([\s\S]*?)(?:\\end\{document\}|$)/.exec(source)?.[1] ?? source;
  const prose = body.replace(/%.*$/gm, " ").replace(/\\[a-zA-Z@]+\*?/g, " ").replace(/[{}$]/g, " ");
  const words = prose.match(/[\p{L}\p{N}][\p{L}\p{N}'-]*/gu)?.length ?? 0;
  const count = (pattern: RegExp) => body.match(pattern)?.length ?? 0;
  const has = (pattern: RegExp) => pattern.test(source);
  const checks: Insights["progress"]["checks"] = [
    { id: "title", label: "Title", done: has(/\\title\{[^}\s]/) },
    { id: "authors", label: "Authors", done: has(/\\author\{[^}\s]/) },
    { id: "abstract", label: "Abstract of at least 50 words", done: (/\\begin\{abstract\}([\s\S]*?)\\end\{abstract\}/.exec(source)?.[1]?.split(/\s+/).filter(Boolean).length ?? 0) >= 50 },
    { id: "introduction", label: "Introduction section", done: has(/\\section\*?\{Intro/i) },
    { id: "conclusion", label: "Conclusion or discussion section", done: has(/\\section\*?\{(Conclu|Discussion|Summary)/i) },
    { id: "references", label: "Reference list", done: has(/\\bibitem/) },
    { id: "citations", label: "Every citation has a reference", done: has(/\\cite/) && has(/\\bibitem/) },
    { id: "length", label: "Full length (2,500 words)", done: words >= 2500 },
  ];
  const score = checks.reduce((sum, check) => sum + (check.done ? 1 : check.id === "length" ? words / 2500 : 0), 0);
  return {
    stats: {
      words,
      sections: count(/\\section\*?\{/g),
      figures: count(/\\begin\{figure\*?\}/g),
      tables: count(/\\begin\{table\*?\}/g),
      equations: count(/\\begin\{(?:equation|align)\*?\}/g),
      citations: count(/\\cite\{/g),
      references: count(/\\bibitem/g),
    },
    progress: { percent: Math.floor((score / checks.length) * 100), checks },
    unresolved_citations: [],
  };
}

function summary(template: Template) {
  return Object.fromEntries(Object.entries(template).filter(([key]) => key !== "source"));
}

export function createFakeBackend(templates: readonly Template[]) {
  const workspaces = new Map<string, StoredWorkspace>();
  const replays = new Map<string, string>();
  const invites = new Map<string, StoredInvite>();
  const names = new Map<string, string>();
  const requests: FakeRequest[] = [];
  let clock = Date.parse("2026-10-09T08:00:00Z");
  let ids = 0;
  const failures: { method: string; path: RegExp; answer: FakeAnswer }[] = [];

  function now() {
    clock += 1000;
    return new Date(clock).toISOString();
  }

  function newId() {
    ids += 1;
    return `00000000-0000-4000-8000-${ids.toString().padStart(12, "0")}`;
  }

  function roleOf(stored: StoredWorkspace, uid: string): Role | null {
    return stored.workspace.owner_id === uid ? "owner" : (stored.members.get(uid) ?? null);
  }

  function view(stored: StoredWorkspace, role: Role): Workspace {
    return { ...stored.workspace, role };
  }

  function documentView(stored: StoredWorkspace, role: Role): LatexDocument {
    const doc = stored.document ?? { source: "", template_id: null, version: 0, updated_at: null, updated_by: null };
    return { workspace_id: stored.workspace.id, ...doc, role, insights: fakeInsights(doc.source) };
  }

  function shareRoute(id: string, collection: string, item: string | undefined, method: string, uid: string, body: unknown): FakeAnswer {
    const stored = workspaces.get(id);
    const role = stored ? roleOf(stored, uid) : null;
    if (!stored || !role) return error(404, "not_found", "Not found, or you do not have access to it");
    const inviter = role === "owner" || role === "editor";

    if (collection === "members") {
      if (!item && method === "GET") {
        const list: Member[] = [stored.workspace.owner_id, ...stored.members.keys()].map((member) => ({
          user_id: member,
          display_name: names.get(member) ?? member,
          role: roleOf(stored, member) ?? "viewer",
          since: stored.since.get(member) ?? stored.workspace.created_at,
        }));
        return { status: 200, body: { members: list } };
      }
      const target = item ?? "";
      if (method === "PATCH") {
        if (role !== "owner") return error(403, "owner_required", "Only the workspace owner may do this");
        if (target === stored.workspace.owner_id) return error(409, "owner_immutable", "The owner's role cannot be changed or removed");
        const next = (body as { role?: unknown } | null)?.role;
        if (typeof next !== "string" || !GRANTABLE.includes(next)) return error(400, "invalid_role", "Choose a role from invite-options");
        if (!stored.members.has(target)) return error(404, "not_found", "Not found");
        stored.members.set(target, next as Role);
        return { status: 200, body: { user_id: target, role: next } };
      }
      if (method === "DELETE") {
        if (target === stored.workspace.owner_id) return error(409, target === uid ? "owner_cannot_leave" : "owner_immutable", "The owner cannot leave");
        if (role !== "owner" && target !== uid) return error(403, "owner_required", "Only the workspace owner may do this");
        if (!stored.members.delete(target)) return error(404, "not_found", "Not found");
        return { status: 204 };
      }
      return error(405, "method_not_allowed", "Method not allowed");
    }

    if (!inviter) return error(403, "invite_forbidden", "Only the owner or an editor may manage invites");
    if (collection === "invite-options") {
      return { status: 200, body: { roles: GRANTABLE, expires_in_hours: EXPIRIES, max_uses: MAX_USES, defaults: { role: "editor", expires_in_hours: 168, max_uses: null } } };
    }
    if (item) {
      const found = invites.get(item);
      if (method !== "DELETE" || !found || found.workspaceId !== id) return error(404, "not_found", "Not found");
      invites.delete(item);
      return { status: 204 };
    }
    if (method === "GET") {
      const list = [...invites.values()].filter((entry) => entry.workspaceId === id && Date.parse(entry.invite.expires_at) > clock).map((entry) => entry.invite);
      return { status: 200, body: { invites: list.reverse() } };
    }
    const choice = body as { role?: unknown; expires_in_hours?: unknown; max_uses?: unknown } | null;
    if (typeof choice?.role !== "string" || !GRANTABLE.includes(choice.role)) return error(400, "invalid_role", "Choose a role from invite-options");
    if (typeof choice.expires_in_hours !== "number" || !EXPIRIES.includes(choice.expires_in_hours)) return error(400, "invalid_expiry", "Choose an expiry from invite-options");
    if (!MAX_USES.includes(choice.max_uses as number | null)) return error(400, "invalid_max_uses", "Choose max uses from invite-options");
    const token = backend.invite(id, choice.role as Exclude<Role, "owner">, {
      expiresAt: new Date(clock + choice.expires_in_hours * 3600 * 1000).toISOString(),
      maxUses: choice.max_uses as number | null,
    });
    const created = [...invites.values()].find((entry) => entry.token === token);
    if (created) created.invite.created_by = uid;
    return { status: 201, body: { ...created?.invite, token } };
  }

  let compiler: (input: FakeCompileInput, uid: string) => FakeAnswer = () => ({ status: 200, body: { status: "compiled", pdf_base64: toBase64(TINY_PDF), log: "" } });

  const find = (stored: StoredWorkspace, path: string) => stored.files.find((entry) => lower(entry.file.path) === lower(path));
  const sizeOf = (entry: StoredFile) => (entry.text !== null ? utf8(entry.text).length : (entry.bytes?.length ?? 0));

  function listing(stored: StoredWorkspace, role: Role): FileListing {
    const doc = stored.document;
    const files = [...stored.files].sort((a, b) => (a.file.path < b.file.path ? -1 : a.file.path > b.file.path ? 1 : 0)).map((entry) => entry.file);
    return {
      workspace_id: stored.workspace.id,
      role,
      main: { path: "main.tex", version: doc?.version ?? 0, size: utf8(doc?.source ?? "").length, updated_at: doc?.updated_at ?? null },
      files,
      output: stored.output?.info ?? null,
      usage: { bytes: stored.files.reduce((sum, entry) => sum + sizeOf(entry), 0), limit_bytes: MAX_PROJECT_BYTES, entries: stored.files.length, limit_entries: MAX_PROJECT_ENTRIES },
    };
  }

  /** The folders a new path needs that don't exist yet, or why it can't be made. */
  function parentsFor(stored: StoredWorkspace, path: string, ignore: readonly StoredFile[] = []): string[] | FakeAnswer {
    const segments = path.split("/");
    const missing: string[] = [];
    for (let depth = 1; depth < segments.length; depth += 1) {
      const parent = segments.slice(0, depth).join("/");
      const existing = find(stored, parent);
      if (!existing || ignore.includes(existing)) missing.push(parent);
      else if (existing.file.kind !== "folder") return error(409, "file_exists", `${existing.file.path} is a file, not a folder`);
    }
    return missing;
  }

  function checkPath(path: unknown): FakeAnswer | null {
    if (typeof path !== "string") return error(400, "invalid_path", "path is required");
    const problem = pathProblem(path);
    if (!problem) return null;
    return error(400, problem.endsWith("is reserved for the project.") ? "reserved_path" : "invalid_path", problem);
  }

  function fits(stored: StoredWorkspace, addedBytes: number, addedEntries: number): FakeAnswer | null {
    const now = listing(stored, "owner").usage;
    if (now.entries + addedEntries > MAX_PROJECT_ENTRIES) return error(413, "project_full", `A project can hold up to ${MAX_PROJECT_ENTRIES} files and folders`);
    if (now.bytes + addedBytes > MAX_PROJECT_BYTES) return error(413, "project_full", "A project can hold up to 10 MiB of files");
    return null;
  }

  function addEntry(stored: StoredWorkspace, path: string, kind: ProjectFile["kind"], content: { text?: string; bytes?: Uint8Array }, uid: string): StoredFile {
    const at = now();
    const entry: StoredFile = {
      file: { id: newId(), path, kind, size: 0, content_type: kind === "folder" ? "" : contentTypeFor(path), version: 1, created_at: at, updated_at: at, updated_by: uid },
      text: content.text ?? null,
      bytes: content.bytes ?? null,
    };
    entry.file.size = sizeOf(entry);
    stored.files.push(entry);
    return entry;
  }

  function withContent(entry: StoredFile) {
    return entry.text !== null ? { ...entry.file, content: entry.text } : entry.file;
  }

  /** Checks one file's name and bytes; returns its text for a text file. */
  function checkUpload(path: string, bytes: Uint8Array): { kind: "text" | "binary"; text?: string } | FakeAnswer {
    const kind = kindForPath(path);
    if (!kind) return error(415, "unsupported_file_type", `${path}: this type of file can't be added to a project`);
    if (bytes.length > MAX_FILE_BYTES) return error(413, "file_too_large", `${path} is over 5 MiB`);
    if (kind === "binary") return magicMatches(path, bytes) ? { kind } : error(415, "unsupported_file_type", `${path} is not the kind of file its name says`);
    if (bytes.length > MAX_TEXT_FILE_BYTES) return error(413, "file_too_large", `${path} is over 1 MiB of text`);
    const text = decodeText(bytes);
    return text === null ? error(400, "invalid_text", `${path} is not UTF-8 text`) : { kind, text };
  }

  function filesRoute(stored: StoredWorkspace, role: Role, uid: string, route: string, item: string | undefined, raw: boolean, method: string, body: unknown): FakeAnswer {
    const writer = role === "owner" || role === "editor";
    const forbidden = () => error(403, "edit_forbidden", "You can view this project but not change it");

    if (route === "archive" && method === "GET") {
      const entries: ZipEntry[] = [{ name: "main.tex", data: utf8(stored.document?.source ?? "") }];
      for (const entry of listing(stored, role).files.map((file) => stored.files.find((candidate) => candidate.file === file))) {
        if (!entry) continue;
        entries.push({ name: entry.file.kind === "folder" ? `${entry.file.path}/` : entry.file.path, data: entry.text !== null ? utf8(entry.text) : (entry.bytes ?? new Uint8Array()) });
      }
      if (stored.output) entries.push({ name: "output.pdf", data: stored.output.bytes });
      const title = stored.workspace.title.replace(/[^\w.-]+/g, "-").replace(/^-+|-+$/g, "") || "project";
      return { status: 200, body: writeZip(entries), headers: { "Content-Type": "application/zip", "Content-Disposition": `attachment; filename="${title}.zip"` } };
    }
    if (route === "output.pdf" && method === "GET") {
      if (!stored.output) return error(404, "not_found", "This project has not compiled yet");
      return { status: 200, body: stored.output.bytes, headers: { "Content-Type": "application/pdf", "Content-Disposition": "attachment; filename=\"output.pdf\"" } };
    }
    if (route === "compile" && method === "POST") {
      const files = [...stored.files]
        .sort((a, b) => (a.file.path < b.file.path ? -1 : 1))
        .filter((entry) => entry.file.kind !== "folder")
        .map((entry) => ({ path: entry.file.path, content_base64: toBase64(entry.text !== null ? utf8(entry.text) : (entry.bytes ?? new Uint8Array())) }));
      const answer = compiler({ latex_source: stored.document?.source ?? "", engine: "pdflatex", files }, uid);
      const result = answer.body as { status?: unknown; pdf_base64?: unknown } | undefined;
      if (answer.status === 200 && result?.status === "compiled" && typeof result.pdf_base64 === "string") {
        const bytes = fromBase64(result.pdf_base64);
        stored.output = { bytes, info: { size: bytes.length, compiled_at: now(), compiled_by: uid, status: "compiled" } };
      }
      return answer;
    }
    if (route !== "files") return error(405, "method_not_allowed", "Method not allowed");

    if (!item) {
      if (method === "GET") return { status: 200, body: listing(stored, role) };
      if (method !== "POST") return error(405, "method_not_allowed", "Method not allowed");
      if (!writer) return forbidden();
      const request = body as { path?: unknown; kind?: unknown; content?: unknown } | null;
      const invalid = checkPath(request?.path);
      if (invalid) return invalid;
      const path = request?.path as string;
      if (request?.kind !== "text" && request?.kind !== "folder") return error(400, "invalid_body", "kind must be text or folder");
      if (request.content !== undefined && (typeof request.content !== "string" || request.kind === "folder")) return error(400, "invalid_body", "content must be a string, for a text file");
      if (find(stored, path)) return error(409, "file_exists", `${path} already exists`);
      let text: string | undefined;
      if (request.kind === "text") {
        if (kindForPath(path) !== "text") return error(415, "unsupported_file_type", "A text file needs a text extension such as .tex or .bib");
        const checked = checkUpload(path, utf8(typeof request.content === "string" ? request.content : ""));
        if ("status" in checked) return checked;
        text = checked.text;
      }
      const parents = parentsFor(stored, path);
      if (!Array.isArray(parents)) return parents;
      const full = fits(stored, text === undefined ? 0 : utf8(text).length, parents.length + 1);
      if (full) return full;
      for (const parent of parents) addEntry(stored, parent, "folder", {}, uid);
      const created = addEntry(stored, path, request.kind, text === undefined ? {} : { text }, uid);
      return { status: 201, body: withContent(created) };
    }

    if (item === "upload") {
      if (method !== "POST") return error(405, "method_not_allowed", "Method not allowed");
      if (!writer) return forbidden();
      if (!isFakeForm(body)) return error(400, "invalid_body", "Send multipart/form-data");
      if (body.size > MAX_UPLOAD_BYTES) return error(413, "body_too_large", "Uploads can be up to 12 MiB at once");
      const folder = body.fields.folder ?? "";
      const replace = body.fields.replace === "true";
      if (folder) {
        const invalid = checkPath(folder);
        if (invalid) return invalid;
      }
      const uploads = body.files.filter((part) => part.field === "file");
      if (uploads.length === 0) return error(400, "invalid_body", "Add at least one file");
      const planned: { path: string; kind: "text" | "binary"; text?: string; bytes: Uint8Array; existing?: StoredFile }[] = [];
      for (const upload of uploads) {
        const path = folder ? `${folder}/${upload.name}` : upload.name;
        const invalid = checkPath(path);
        if (invalid) return invalid;
        const checked = checkUpload(path, upload.bytes);
        if ("status" in checked) return checked;
        const existing = find(stored, path);
        if (existing && (!replace || existing.file.kind !== checked.kind)) return error(409, "file_exists", `${existing.file.path} already exists`, { path: existing.file.path });
        if (planned.some((entry) => lower(entry.path) === lower(path))) return error(409, "file_exists", `${path} is in the upload twice`);
        planned.push({ path, kind: checked.kind, bytes: upload.bytes, ...(checked.text === undefined ? {} : { text: checked.text }), ...(existing ? { existing } : {}) });
      }
      const parents = folder ? parentsFor(stored, `${folder}/x`) : [];
      if (!Array.isArray(parents)) return parents;
      const added = planned.reduce((sum, entry) => sum + entry.bytes.length - (entry.existing ? sizeOf(entry.existing) : 0), 0);
      const full = fits(stored, added, parents.length + planned.filter((entry) => !entry.existing).length);
      if (full) return full;
      for (const parent of parents) addEntry(stored, parent, "folder", {}, uid);
      const files = planned.map((entry) => {
        const content = entry.kind === "text" ? { text: entry.text ?? "" } : { bytes: entry.bytes };
        if (!entry.existing) return addEntry(stored, entry.path, entry.kind, content, uid).file;
        entry.existing.text = content.text ?? null;
        entry.existing.bytes = content.bytes ?? null;
        Object.assign(entry.existing.file, { size: sizeOf(entry.existing), version: entry.existing.file.version + 1, updated_at: now(), updated_by: uid });
        return entry.existing.file;
      });
      return { status: 201, body: { files } };
    }

    const entry = stored.files.find((candidate) => candidate.file.id === item);
    if (!entry) return error(404, "not_found", "No such file");
    if (raw) {
      if (method !== "GET") return error(405, "method_not_allowed", "Method not allowed");
      if (entry.file.kind === "folder") return error(400, "not_a_file", "A folder has no content");
      const image = entry.file.content_type.startsWith("image/");
      const name = entry.file.path.slice(entry.file.path.lastIndexOf("/") + 1);
      return {
        status: 200,
        body: entry.text !== null ? utf8(entry.text) : (entry.bytes ?? new Uint8Array()),
        headers: { "Content-Type": entry.file.content_type, "X-Content-Type-Options": "nosniff", "Content-Disposition": `${image ? "inline" : "attachment"}; filename="${name}"` },
      };
    }
    if (method === "GET") return { status: 200, body: withContent(entry) };
    if (!writer) return forbidden();
    if (method === "PUT") {
      if (entry.file.kind !== "text") return error(400, "not_a_text_file", "Only text files can be edited");
      const save = body as { content?: unknown; base_version?: unknown } | null;
      if (typeof save?.content !== "string" || typeof save.base_version !== "number") return error(400, "invalid_body", "Send content and base_version");
      const checked = checkUpload(entry.file.path, utf8(save.content));
      if ("status" in checked) return checked;
      if (save.base_version !== entry.file.version) return error(409, "version_conflict", "The file changed since base_version", { current_version: entry.file.version });
      const full = fits(stored, utf8(save.content).length - sizeOf(entry), 0);
      if (full) return full;
      entry.text = save.content;
      Object.assign(entry.file, { size: sizeOf(entry), version: entry.file.version + 1, updated_at: now(), updated_by: uid });
      return { status: 200, body: withContent(entry) };
    }
    if (method === "PATCH") {
      const target = (body as { path?: unknown } | null)?.path;
      const invalid = checkPath(target);
      if (invalid) return invalid;
      const path = target as string;
      const from = entry.file.path;
      const children = entry.file.kind === "folder" ? stored.files.filter((candidate) => lower(candidate.file.path).startsWith(`${lower(from)}/`)) : [];
      if (entry.file.kind === "folder" && lower(path).startsWith(`${lower(from)}/`)) return error(400, "invalid_path", "A folder can't move into itself");
      if (entry.file.kind !== "folder" && kindForPath(path) !== entry.file.kind) return error(415, "unsupported_file_type", "Keep the file's type when renaming it");
      const moving = [entry, ...children];
      const clash = find(stored, path);
      if (clash && clash !== entry) return error(409, "file_exists", `${clash.file.path} already exists`);
      for (const child of children) {
        const problem = checkPath(path + child.file.path.slice(from.length));
        if (problem) return problem;
      }
      const parents = parentsFor(stored, path, moving);
      if (!Array.isArray(parents)) return parents;
      const full = fits(stored, 0, parents.length);
      if (full) return full;
      for (const parent of parents) addEntry(stored, parent, "folder", {}, uid);
      const at = now();
      for (const moved of moving) Object.assign(moved.file, { path: path + moved.file.path.slice(from.length), updated_at: at, updated_by: uid });
      return { status: 200, body: entry.file };
    }
    if (method === "DELETE") {
      const prefix = `${lower(entry.file.path)}/`;
      stored.files = stored.files.filter((candidate) => candidate !== entry && !(entry.file.kind === "folder" && lower(candidate.file.path).startsWith(prefix)));
      return { status: 204 };
    }
    return error(405, "method_not_allowed", "Method not allowed");
  }

  function importRoute(uid: string, body: unknown): FakeAnswer {
    if (!isFakeForm(body)) return error(400, "invalid_body", "Send multipart/form-data");
    const zip = body.files.find((part) => part.field === "file");
    if (!zip) return error(400, "invalid_body", "Add the project's .zip as file");
    if (body.size > MAX_UPLOAD_BYTES) return error(413, "body_too_large", "A project zip can be up to 12 MiB");
    let entries: ZipEntry[];
    try {
      entries = readZip(zip.bytes);
    } catch {
      return error(400, "invalid_archive", "That isn't a zip file we can read");
    }
    if (entries.length > 500) return error(413, "project_full", "A project zip can hold up to 500 entries");
    if (entries.reduce((sum, entry) => sum + entry.data.length, 0) > MAX_PROJECT_BYTES) return error(413, "project_full", "A project can unpack to at most 10 MiB");
    const roots = entries.filter((entry) => !entry.name.includes("/") && entry.name.toLowerCase().endsWith(".tex"));
    const main =
      roots.find((entry) => entry.name === "main.tex") ??
      (() => {
        const candidates = roots.filter((entry) => (decodeText(entry.data) ?? "").includes("\\documentclass"));
        return candidates.length === 1 ? candidates[0] : undefined;
      })();
    const source = main ? decodeText(main.data) : null;
    if (!main || source === null) return error(400, "no_main_file", "The zip needs a main.tex, or one .tex file with \\documentclass at its top level");
    const title = body.fields.title?.trim() || zip.name.replace(/\.zip$/i, "") || "Imported project";
    const id = backend.seed(uid, title, { source });
    const stored = workspaces.get(id);
    if (!stored) return error(503, "unavailable", "lost");
    const skipped: string[] = [];
    for (const entry of entries) {
      if (entry === main) continue;
      const folder = entry.name.endsWith("/");
      const path = folder ? entry.name.slice(0, -1) : entry.name;
      const hidden = path.split("/").some((segment) => segment.startsWith(".") || segment === "__MACOSX");
      if (hidden || pathProblem(path) || find(stored, path)) {
        if (!folder) skipped.push(entry.name);
        continue;
      }
      if (folder) {
        const parents = parentsFor(stored, path);
        if (Array.isArray(parents)) for (const parent of [...parents, path]) addEntry(stored, parent, "folder", {}, uid);
        continue;
      }
      const checked = checkUpload(path, entry.data);
      const parents = parentsFor(stored, path);
      if ("status" in checked || !Array.isArray(parents) || fits(stored, entry.data.length, parents.length + 1)) {
        skipped.push(entry.name);
        continue;
      }
      for (const parent of parents) addEntry(stored, parent, "folder", {}, uid);
      addEntry(stored, path, checked.kind, checked.kind === "text" ? { text: checked.text ?? "" } : { bytes: entry.data }, uid);
    }
    return { status: 201, body: { ...view(stored, "owner"), skipped } };
  }

  const backend = {
    requests,

    /** Adds a workspace owned by ownerUid, optionally shared and with a saved document. */
    seed(ownerUid: string, title: string, options: { members?: Record<string, Role>; source?: string; templateId?: string | null } = {}): string {
      const id = newId();
      const created = now();
      const members = new Map(Object.entries(options.members ?? {}));
      workspaces.set(id, {
        workspace: { id, title, owner_id: ownerUid, created_at: created },
        members,
        since: new Map([ownerUid, ...members.keys()].map((uid) => [uid, created])),
        document:
          options.source === undefined ? null : { source: options.source, template_id: options.templateId ?? null, version: 1, updated_at: now(), updated_by: ownerUid },
        files: [],
        output: null,
      });
      return id;
    },

    /** Adds a text file (string) or binary file (bytes) to a project, with its folders. Returns its id. */
    addFile(id: string, path: string, content: string | Uint8Array | null, uid?: string): string {
      const stored = workspaces.get(id);
      if (!stored) throw new Error(`no workspace ${id}`);
      const by = uid ?? stored.workspace.owner_id;
      const parents = parentsFor(stored, path);
      if (!Array.isArray(parents)) throw new Error(`can't add ${path}`);
      for (const parent of parents) addEntry(stored, parent, "folder", {}, by);
      if (content === null) return addEntry(stored, path, "folder", {}, by).file.id;
      return addEntry(stored, path, typeof content === "string" ? "text" : "binary", typeof content === "string" ? { text: content } : { bytes: content }, by).file.id;
    },

    /** Someone else saves a text file (moves its version on). */
    saveFileAs(uid: string, id: string, path: string, content: string) {
      const entry = workspaces.get(id)?.files.find((candidate) => candidate.file.path === path);
      if (!entry) throw new Error(`no file ${path}`);
      entry.text = content;
      Object.assign(entry.file, { size: utf8(content).length, version: entry.file.version + 1, updated_at: now(), updated_by: uid });
    },

    /** The project's files by path: text content, bytes, or null for a folder. */
    files(id: string): Record<string, string | Uint8Array | null> {
      const stored = workspaces.get(id);
      return Object.fromEntries((stored?.files ?? []).map((entry) => [entry.file.path, entry.file.kind === "folder" ? null : (entry.text ?? entry.bytes)]));
    },

    /** The stored output.pdf, if any. */
    output(id: string): Uint8Array | null {
      return workspaces.get(id)?.output?.bytes ?? null;
    },

    /** What POST /workspaces/:id/compile answers (default: a compiled one-page PDF). */
    setCompiler(next: (input: FakeCompileInput, uid: string) => FakeAnswer) {
      compiler = next;
    },

    /** Someone else saves the document (moves its version on). */
    saveAs(uid: string, id: string, source: string) {
      const stored = workspaces.get(id);
      if (!stored?.document) throw new Error(`no document ${id}`);
      stored.document = { ...stored.document, source, version: stored.document.version + 1, updated_at: now(), updated_by: uid };
    },

    setRole(id: string, uid: string, role: Role | null) {
      const stored = workspaces.get(id);
      if (!stored) throw new Error(`no workspace ${id}`);
      if (role) {
        stored.members.set(uid, role);
        if (!stored.since.has(uid)) stored.since.set(uid, now());
      } else stored.members.delete(uid);
    },

    /** The display name the members list shows for uid (default: the uid). */
    setName(uid: string, name: string) {
      names.set(uid, name);
    },

    /** Roles by uid, owner included. */
    members(id: string): Record<string, Role> {
      const stored = workspaces.get(id);
      if (!stored) return {};
      return { [stored.workspace.owner_id]: "owner", ...Object.fromEntries(stored.members) };
    },

    /** An invite as if someone created it, returning its token. */
    invite(id: string, role: Exclude<Role, "owner">, options: { expiresAt?: string; maxUses?: number | null } = {}): string {
      const inviteId = `inv-${(invites.size + 1).toString()}`;
      const token = `inv_${inviteId}_${Math.random().toString(36).slice(2)}`;
      const stored = workspaces.get(id);
      invites.set(inviteId, {
        workspaceId: id,
        token,
        invite: {
          id: inviteId,
          role,
          created_by: stored?.workspace.owner_id ?? "",
          created_at: now(),
          expires_at: options.expiresAt ?? new Date(clock + 7 * 24 * 3600 * 1000).toISOString(),
          max_uses: options.maxUses ?? null,
          uses: 0,
        },
      });
      return token;
    },

    remove(id: string) {
      workspaces.delete(id);
    },

    document(id: string) {
      return workspaces.get(id)?.document ?? null;
    },

    titles(): string[] {
      return [...workspaces.values()].map((stored) => stored.workspace.title);
    },

    /** The next request matching method and path gets this answer instead. */
    failNext(method: string, path: RegExp, answer: FakeAnswer) {
      failures.push({ method, path, answer });
    },

    /** Answers a request to an editor endpoint, or returns null for anything else. */
    handle(request: FakeRequest): FakeAnswer | null {
      const url = new URL(request.url, "http://fake");
      const path = url.pathname;
      const method = request.method.toUpperCase();
      const editorPath = /^\/api\/v1\/(workspaces|templates|invites)(\/|$)/.test(path);
      if (!editorPath) return null;
      requests.push(request);

      const failure = failures.findIndex((entry) => entry.method === method && entry.path.test(path));
      if (failure >= 0) return failures.splice(failure, 1)[0]?.answer ?? null;

      const auth = request.headers.authorization ?? request.headers.Authorization ?? "";
      const uid = /^Bearer fake-token:(.+)$/.exec(auth)?.[1];
      if (!uid) return error(401, "token_missing", "Sign in");

      if (path === "/api/v1/templates" && method === "GET") {
        const domain = url.searchParams.get("domain");
        const list = templates.filter((template) => !domain || template.domain === domain).map(summary);
        return { status: 200, body: { templates: list } };
      }
      const templateMatch = /^\/api\/v1\/templates\/([^/]+)$/.exec(path);
      if (templateMatch && method === "GET") {
        const template = templates.find((entry) => entry.id === templateMatch[1]);
        return template ? { status: 200, body: template } : error(404, "not_found", "No such template");
      }

      if (path === "/api/v1/workspaces" && method === "GET") {
        const mine = [...workspaces.values()]
          .map((stored) => ({ stored, role: roleOf(stored, uid) }))
          .filter((entry): entry is { stored: StoredWorkspace; role: Role } => entry.role !== null)
          .map(({ stored, role }) => view(stored, role))
          .reverse();
        return { status: 200, body: { workspaces: mine, next_page_token: "" } };
      }
      if (path === "/api/v1/workspaces" && method === "POST") {
        const title = (request.body as { title?: unknown } | null)?.title;
        if (typeof title !== "string" || !title.trim()) return error(400, "invalid_body", "title is required");
        const key = request.headers["idempotency-key"] ?? request.headers["Idempotency-Key"];
        const replayed = key ? replays.get(`${uid}:${key}`) : undefined;
        const existing = replayed ? workspaces.get(replayed) : undefined;
        if (existing) return { status: 201, body: view(existing, "owner"), headers: { "Idempotent-Replayed": "true" } };
        const id = backend.seed(uid, title.trim());
        if (key) replays.set(`${uid}:${key}`, id);
        const stored = workspaces.get(id);
        return stored ? { status: 201, body: view(stored, "owner") } : error(503, "unavailable", "lost");
      }

      if ((path === "/api/v1/invites/preview" || path === "/api/v1/invites/accept") && method === "POST") {
        const token = (request.body as { token?: unknown } | null)?.token;
        const found = [...invites.values()].find((entry) => entry.token === token);
        const target = found ? workspaces.get(found.workspaceId) : undefined;
        const usable =
          found && target && Date.parse(found.invite.expires_at) > clock && (found.invite.max_uses === null || found.invite.uses < found.invite.max_uses);
        if (!found || !target || !usable) return error(404, "invite_invalid", "This invite link is invalid or has expired");
        if (path.endsWith("/preview")) {
          return {
            status: 200,
            body: { workspace_id: found.workspaceId, workspace_title: target.workspace.title, role: found.invite.role, expires_at: found.invite.expires_at },
          };
        }
        const current = roleOf(target, uid);
        if (current && RANK[current] >= RANK[found.invite.role]) return { status: 200, body: { workspace_id: found.workspaceId, role: current, changed: false } };
        target.members.set(uid, found.invite.role);
        target.since.set(uid, now());
        found.invite.uses += 1;
        return { status: 200, body: { workspace_id: found.workspaceId, role: found.invite.role, changed: true } };
      }

      if (path === "/api/v1/workspaces/import" && method === "POST") return importRoute(uid, request.body);
      const project = /^\/api\/v1\/workspaces\/([^/]+)\/(files|archive|compile|output\.pdf)(?:\/([^/]+)(\/raw)?)?$/.exec(path);
      if (project) {
        const stored = workspaces.get(project[1] ?? "");
        const role = stored ? roleOf(stored, uid) : null;
        if (!stored || !role) return error(404, "not_found", "Workspace not found");
        return filesRoute(stored, role, uid, project[2] ?? "", project[3], Boolean(project[4]), method, request.body);
      }

      const sharing = /^\/api\/v1\/workspaces\/([^/]+)\/(members|invites|invite-options)(?:\/([^/]+))?$/.exec(path);
      if (sharing) return shareRoute(sharing[1] ?? "", sharing[2] ?? "", sharing[3], method, uid, request.body);

      const match = /^\/api\/v1\/workspaces\/([^/]+)(\/document)?$/.exec(path);
      if (!match) return error(404, "not_found", "Not found");
      const id = match[1] ?? "";
      const stored = workspaces.get(id);
      const role = stored ? roleOf(stored, uid) : null;
      if (!stored || !role) return error(404, "not_found", "Workspace not found");

      if (!match[2]) {
        if (method === "GET") return { status: 200, body: view(stored, role) };
        if (role !== "owner") return error(403, "forbidden", "Only the owner can do that");
        if (method === "DELETE") {
          workspaces.delete(id);
          return { status: 204 };
        }
        if (method === "PATCH") {
          const title = (request.body as { title?: unknown } | null)?.title;
          if (typeof title !== "string" || !title.trim()) return error(400, "invalid_body", "title is required");
          stored.workspace.title = title.trim();
          return { status: 200, body: view(stored, role) };
        }
        return error(405, "method_not_allowed", "Method not allowed");
      }

      if (method === "GET") return { status: 200, body: documentView(stored, role), headers: { ETag: `"v${stored.document?.version ?? 0}"` } };
      if (method !== "PUT") return error(405, "method_not_allowed", "Method not allowed");
      const body = request.body as { source?: unknown; base_version?: unknown; template_id?: unknown } | null;
      if (typeof body?.source !== "string" || typeof body.base_version !== "number" || !Number.isInteger(body.base_version)) {
        return error(400, "invalid_body", "Request body must be JSON with source and base_version");
      }
      if (characterCount(body.source) > MAX_DOCUMENT) return error(413, "document_too_large", "The document must be at most 100,000 characters");
      if (body.template_id !== undefined && !templates.some((template) => template.id === body.template_id)) {
        return error(400, "unknown_template", "template_id does not name a template in the catalog");
      }
      if (role !== "owner" && role !== "editor") return error(403, "edit_forbidden", "You can view this document but not edit it");
      const current = stored.document?.version ?? 0;
      if (body.base_version !== current) {
        return error(409, "version_conflict", "The document changed since base_version", { current_version: current });
      }
      stored.document = {
        source: body.source,
        template_id: typeof body.template_id === "string" ? body.template_id : (stored.document?.template_id ?? null),
        version: current + 1,
        updated_at: now(),
        updated_by: uid,
      };
      return { status: 200, body: documentView(stored, role), headers: { ETag: `"v${current + 1}"` } };
    },
  };
  return backend;
}

export type FakeBackend = ReturnType<typeof createFakeBackend>;

/** A small catalog for tests: one or two templates per field. */
export const TEST_TEMPLATES: Template[] = [
  {
    id: "aps-physical-review",
    name: "APS Physical Review (REVTeX 4.2)",
    domain: "physics",
    journals: "Physical Review Letters, Physical Review A–E, X",
    publisher: "American Physical Society",
    description: "REVTeX 4.2 two-column article.",
    class: "revtex4-2",
    license: "LPPL 1.3c",
    source_url: "https://ctan.org/pkg/revtex",
    size: 60,
    source: "\\documentclass[aps,prl,twocolumn]{revtex4-2}\n\\begin{document}\nPhysics\n\\end{document}\n",
  },
  {
    id: "acs-jacs",
    name: "ACS Journal (achemso)",
    domain: "chemistry",
    journals: "Journal of the American Chemical Society",
    publisher: "American Chemical Society",
    description: "The achemso class for ACS journals.",
    class: "achemso",
    license: "LPPL 1.3c",
    source_url: "https://ctan.org/pkg/achemso",
    size: 60,
    source: "\\documentclass[journal=jacsat]{achemso}\n\\begin{document}\nChemistry\n\\end{document}\n",
  },
  {
    id: "ams-article",
    name: "AMS Journal Article (amsart)",
    domain: "mathematics",
    journals: "Journal of the AMS, Transactions of the AMS",
    publisher: "American Mathematical Society",
    description: "The AMS article class.",
    class: "amsart",
    license: "LPPL 1.3c",
    source_url: "https://ctan.org/pkg/amscls",
    size: 60,
    source: "\\documentclass{amsart}\n\\begin{document}\nMathematics\n\\end{document}\n",
  },
  {
    id: "nature",
    name: "Nature",
    domain: "biology",
    journals: "Nature",
    publisher: "Nature Portfolio (community class)",
    description: "A Nature-style submission.",
    class: "nature",
    license: "LPPL 1.3c",
    source_url: "https://ctan.org/pkg/nature",
    size: 60,
    source: "\\documentclass{nature}\n\\begin{document}\nBiology\n\\end{document}\n",
  },
];
