import { useOutletContext } from "react-router";
import type { Domain, TemplateSummary, WorkspaceCard } from "../api/editor";

export type Load<T> = { state: "loading" } | { state: "ready"; items: T[] } | { state: "error"; message: string };

export type UploadKind = "zip" | "folder" | "files";

/** What the signed-in shell (Home) hands its pages: data it loads once, and the ways to start a document. */
export interface Shell {
  name: string;
  token: () => Promise<string>;
  documents: Load<WorkspaceCard>;
  templates: Load<TemplateSummary>;
  retry: () => void;
  /** A blank document is being made. */
  creating: boolean;
  startBlank: () => void;
  /** The template a document is being started from. */
  starting: string | null;
  startTemplate: (template: TemplateSummary) => void;
  importing: boolean;
  upload: (kind: UploadKind) => void;
  remove: (doc: WorkspaceCard) => void;
}

export const useShell = () => useOutletContext<Shell>();

/** The ?field= filter on the templates page. */
export const isDomain = (value: string | null, domains: readonly Domain[]): value is Domain => domains.includes(value as Domain);
