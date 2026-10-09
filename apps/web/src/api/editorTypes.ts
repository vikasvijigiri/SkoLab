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
