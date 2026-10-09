import { characterCount, type LatexDocument, type Role, type Template, type Workspace } from "../api/editorTypes";

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

interface StoredWorkspace {
  workspace: Omit<Workspace, "role">;
  members: Map<string, Role>;
  document: Omit<LatexDocument, "workspace_id" | "role"> | null;
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

function summary(template: Template) {
  return Object.fromEntries(Object.entries(template).filter(([key]) => key !== "source"));
}

export function createFakeBackend(templates: readonly Template[]) {
  const workspaces = new Map<string, StoredWorkspace>();
  const replays = new Map<string, string>();
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
    return { workspace_id: stored.workspace.id, ...doc, role };
  }

  const backend = {
    requests,

    /** Adds a workspace owned by ownerUid, optionally shared and with a saved document. */
    seed(ownerUid: string, title: string, options: { members?: Record<string, Role>; source?: string; templateId?: string | null } = {}): string {
      const id = newId();
      workspaces.set(id, {
        workspace: { id, title, owner_id: ownerUid, created_at: now() },
        members: new Map(Object.entries(options.members ?? {})),
        document:
          options.source === undefined ? null : { source: options.source, template_id: options.templateId ?? null, version: 1, updated_at: now(), updated_by: ownerUid },
      });
      return id;
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
      if (role) stored.members.set(uid, role);
      else stored.members.delete(uid);
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
      const editorPath = /^\/api\/v1\/(workspaces|templates)(\/|$)/.test(path);
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
