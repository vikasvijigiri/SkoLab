/**
 * The editor API's shapes (services/backend-go/api/openapi.yaml), with no
 * imports so the browser tests' fake backend can share them.
 */

export type Role = "owner" | "editor" | "commenter" | "viewer";

export interface Workspace {
  id: string;
  title: string;
  owner_id: string;
  role: Role;
  created_at: string;
}

export const DOMAINS = ["physics", "chemistry", "mathematics", "biology"] as const;
export type Domain = (typeof DOMAINS)[number];

export const DOMAIN_LABELS: Record<Domain, string> = {
  physics: "Physics",
  chemistry: "Chemistry",
  mathematics: "Mathematics",
  biology: "Biology",
};

export interface TemplateSummary {
  id: string;
  name: string;
  domain: Domain;
  /** The journals the template is the official one for. */
  journals: string;
  publisher: string;
  description: string;
  /** The LaTeX document class. */
  class: string;
  license: string;
  source_url: string;
  /** Bytes of LaTeX source. */
  size: number;
}

export interface Template extends TemplateSummary {
  source: string;
}

export interface LatexDocument {
  workspace_id: string;
  source: string;
  template_id: string | null;
  /** 0 until the first save. */
  version: number;
  updated_at: string | null;
  updated_by: string | null;
  role: Role;
  insights: Insights;
}

export type CheckId = "title" | "authors" | "abstract" | "introduction" | "conclusion" | "references" | "citations" | "length";

/** What the gateway reads from the source on every load and save. */
export interface Insights {
  stats: {
    /** Running text of the body, without math, floats or the bibliography. */
    words: number;
    sections: number;
    figures: number;
    tables: number;
    equations: number;
    /** Distinct keys cited. */
    citations: number;
    /** \bibitem entries. */
    references: number;
  };
  progress: {
    percent: number;
    checks: { id: CheckId; label: string; done: boolean }[];
  };
  /** Cited keys with no matching \bibitem (at most 20). */
  unresolved_citations: string[];
}

/** Roles an owner or editor can hand out; ownership moves only by transfer. */
export type GrantableRole = Exclude<Role, "owner">;

export const ROLE_LABELS: Record<Role, string> = {
  owner: "Owner",
  editor: "Can edit",
  commenter: "Can comment",
  viewer: "Can view",
};

export interface Member {
  user_id: string;
  display_name: string;
  role: Role;
  since: string;
}

export interface Invite {
  id: string;
  role: GrantableRole;
  created_by: string;
  created_at: string;
  expires_at: string;
  max_uses: number | null;
  uses: number;
  /** Only in the answer that created it. */
  token?: string;
}

export interface InviteOptions {
  roles: GrantableRole[];
  expires_in_hours: number[];
  max_uses: (number | null)[];
  defaults: { role?: GrantableRole; expires_in_hours?: number; max_uses?: number | null };
}

export interface InvitePreview {
  workspace_id: string;
  workspace_title: string;
  role: GrantableRole;
  expires_at: string;
}

export interface Membership {
  workspace_id: string;
  role: Role;
  /** False when the caller already had this access or more. */
  changed: boolean;
}

/** Owners and editors share; the gateway enforces the same rule. */
export function canInvite(role: Role): boolean {
  return canEdit(role);
}

/** The document limit the API enforces, in characters (Unicode code points). */
export const MAX_DOCUMENT_CHARACTERS = 100_000;

/** Characters as the API counts them: code points, not UTF-16 units. */
export function characterCount(text: string): number {
  // A surrogate pair is one character.
  return text.replace(/[\uD800-\uDBFF][\uDC00-\uDFFF]/g, "_").length;
}

export function canEdit(role: Role): boolean {
  return role === "owner" || role === "editor";
}

// Project files: main.tex plus the files and folders beside it, and the last compiled PDF.

export type FileKind = "folder" | "text" | "binary";

export interface ProjectFile {
  id: string;
  /** "/"-separated, e.g. "chapters/intro.tex". */
  path: string;
  kind: FileKind;
  /** Bytes; 0 for a folder. */
  size: number;
  /** "" for a folder. */
  content_type: string;
  version: number;
  created_at: string;
  updated_at: string;
  updated_by: string | null;
}

/** A text file with its content. */
export interface TextFile extends ProjectFile {
  content: string;
}

export interface ProjectOutput {
  size: number;
  compiled_at: string;
  compiled_by: string | null;
  status: "compiled";
}

export interface FileListing {
  workspace_id: string;
  role: Role;
  main: { path: "main.tex"; version: number; size: number; updated_at: string | null };
  /** Sorted by path. */
  files: ProjectFile[];
  output: ProjectOutput | null;
  usage: { bytes: number; limit_bytes: number; entries: number; limit_entries: number };
}

/** POST /workspaces/import: the new workspace, and what the zip held that it left out. */
export interface ImportedWorkspace extends Workspace {
  skipped: string[];
}

export const MAIN_FILE = "main.tex";
export const OUTPUT_FILE = "output.pdf";
export const MAX_FILE_BYTES = 5 * 1024 * 1024;
export const MAX_TEXT_FILE_BYTES = 1024 * 1024;
export const MAX_PROJECT_BYTES = 10 * 1024 * 1024;
export const MAX_PROJECT_ENTRIES = 200;
/** Upload and import requests. */
export const MAX_UPLOAD_BYTES = 12 * 1024 * 1024;

export const TEXT_EXTENSIONS = ["tex", "bib", "bst", "cls", "sty", "txt", "md", "csv", "dat", "bbx", "cbx", "lbx", "def", "cfg", "clo", "ist", "tikz"] as const;
export const BINARY_EXTENSIONS = ["png", "jpg", "jpeg", "pdf", "eps"] as const;

/** The lower-case extension of a path's last segment ("" when it has none). */
export function extensionOf(path: string): string {
  const name = path.slice(path.lastIndexOf("/") + 1);
  const dot = name.lastIndexOf(".");
  return dot > 0 ? name.slice(dot + 1).toLowerCase() : "";
}

/** How the API stores a file with this name, or null when it refuses the type. */
export function kindForPath(path: string): "text" | "binary" | null {
  const extension = extensionOf(path);
  if ((TEXT_EXTENSIONS as readonly string[]).includes(extension)) return "text";
  if ((BINARY_EXTENSIONS as readonly string[]).includes(extension)) return "binary";
  return null;
}

const SEGMENT = /^[A-Za-z0-9 _.,()+-]{1,80}$/;

/** Why the API would refuse this path, in words, or null when it is fine. */
export function pathProblem(path: string): string | null {
  if (!path) return "Enter a name.";
  if (new TextEncoder().encode(path).length > 200) return "Paths can be up to 200 characters.";
  const segments = path.split("/");
  if (segments.length > 6) return "Files can sit at most 5 folders deep.";
  for (const segment of segments) {
    if (!segment) return "Leave no empty folder names, and no slash at the start or end.";
    if (segment.startsWith(".")) return "Names can't start with a dot.";
    if (!SEGMENT.test(segment)) return "Names can use letters, digits, spaces and _ . , ( ) + - only, up to 80 characters each.";
  }
  if (segments.length === 1 && [MAIN_FILE, OUTPUT_FILE].includes(path.toLowerCase())) return `${path} is reserved for the project.`;
  return null;
}

/** "1.2 MB", "340 KB", "12 bytes". */
export function formatBytes(bytes: number): string {
  if (bytes < 1024) return bytes === 1 ? "1 byte" : `${bytes} bytes`;
  if (bytes < 1024 * 1024) return `${Math.round(bytes / 1024).toLocaleString()} KB`;
  const megabytes = bytes / (1024 * 1024);
  return `${megabytes >= 10 ? Math.round(megabytes).toLocaleString() : (Math.round(megabytes * 10) / 10).toLocaleString()} MB`;
}
