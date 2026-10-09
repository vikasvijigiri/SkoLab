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
