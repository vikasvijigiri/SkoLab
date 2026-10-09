import { characterCount, type Insights, type Invite, type LatexDocument, type Member, type Role, type Template, type Workspace } from "../api/editorTypes";

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
  since: Map<string, string>;
  document: Omit<LatexDocument, "workspace_id" | "role" | "insights"> | null;
}

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
