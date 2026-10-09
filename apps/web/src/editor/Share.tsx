import { useEffect, useId, useState } from "react";
import { ApiError } from "../api/client";
import { canInvite, ROLE_LABELS, type GrantableRole, type Invite, type InviteOptions, type Member, type Role } from "../api/editorTypes";
import {
  changeMemberRole,
  createInvite,
  getInviteOptions,
  inviteLink,
  listInvites,
  listMembers,
  removeMember,
  revokeInvite,
  type InviteChoice,
} from "../api/sharing";
import { useIdToken } from "../auth/useIdToken";
import { Alert } from "../components/Alert";
import { Button } from "../components/Button";
import { Spinner } from "../components/Spinner";
import { formatDate } from "../lib/dates";

const PALETTE = ["bg-violet-700", "bg-sky-700", "bg-emerald-700", "bg-amber-700", "bg-rose-700", "bg-teal-700", "bg-fuchsia-700", "bg-indigo-700"];

function initials(name: string): string {
  const parts = name.trim().split(/\s+/).filter(Boolean);
  const letters = parts.length > 1 ? [parts[0], parts.at(-1)] : [name.trim()];
  return (
    letters
      .map((part) => {
        const point = part?.codePointAt(0);
        return point === undefined ? "" : String.fromCodePoint(point);
      })
      .join("")
      .toUpperCase() || "?"
  );
}

/** A coloured circle with someone's initials; the colour follows their id. */
export function Avatar({ name, id, className = "size-9 text-sm" }: { name: string; id: string; className?: string }) {
  let hash = 0;
  for (const char of id) hash = (hash * 31 + (char.codePointAt(0) ?? 0)) >>> 0;
  return (
    <span aria-hidden="true" className={`grid shrink-0 place-items-center rounded-full font-semibold text-white ${PALETTE[hash % PALETTE.length] ?? PALETTE[0]} ${className}`}>
      {initials(name)}
    </span>
  );
}

function expiryLabel(hours: number): string {
  if (hours % 24 !== 0) return `${hours} hours`;
  const days = hours / 24;
  return days === 1 ? "1 day" : `${days} days`;
}

function usesLabel(uses: number | null): string {
  if (uses === null) return "Any number of people";
  return uses === 1 ? "One person" : `Up to ${uses} people`;
}

function sharingMessage(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.code === "network") return error.message;
    if (error.status === 429) return "Too many changes at once. Wait a moment and try again.";
    if (error.status === 403 || error.status === 404) return "You no longer have permission to do that.";
    if (error.code === "owner_immutable") return "The owner's access can't be changed.";
  }
  return "That didn't work. Try again.";
}

const selectClass =
  "h-10 w-full cursor-pointer rounded-lg border border-zinc-300 bg-white px-2.5 text-sm text-zinc-900 focus:border-brand-500 dark:border-zinc-700 dark:bg-zinc-900 dark:text-zinc-100";

type Loaded = { state: "loading" } | { state: "error" } | { state: "ready"; members: Member[]; invites: Invite[]; options: InviteOptions | null };

/**
 * Who can open the document, and invite links to add more people. Owners
 * and editors invite; the owner changes roles and removes people; anyone
 * else may leave.
 */
export function Share({
  workspaceId,
  role,
  me,
  onMembers,
  onLeft,
}: {
  workspaceId: string;
  role: Role;
  me: string;
  onMembers?: (members: Member[]) => void;
  onLeft: () => void;
}) {
  const idToken = useIdToken();
  const inviter = canInvite(role);
  const isOwner = role === "owner";
  const [loaded, setLoaded] = useState<Loaded>({ state: "loading" });
  const [attempt, setAttempt] = useState(0);
  const [choice, setChoice] = useState<InviteChoice>({ role: "editor", expires_in_hours: 168, max_uses: null });
  const [created, setCreated] = useState<{ link: string; invite: Invite } | null>(null);
  const [copied, setCopied] = useState(false);
  const [busy, setBusy] = useState<string | null>(null);
  const [confirming, setConfirming] = useState<string | null>(null);
  const [failure, setFailure] = useState<string | null>(null);
  const ids = { role: useId(), expiry: useId(), uses: useId(), link: useId() };

  useEffect(() => {
    const stale = new AbortController();
    void (async () => {
      try {
        const token = await idToken();
        const [members, invites, options] = await Promise.all([
          listMembers(token, workspaceId),
          inviter ? listInvites(token, workspaceId) : Promise.resolve([]),
          inviter ? getInviteOptions(token, workspaceId) : Promise.resolve(null),
        ]);
        if (stale.signal.aborted) return;
        setLoaded({ state: "ready", members, invites, options });
        if (options) {
          setChoice({
            role: options.defaults.role ?? options.roles[0] ?? "editor",
            expires_in_hours: options.defaults.expires_in_hours ?? options.expires_in_hours[0] ?? 168,
            max_uses: options.defaults.max_uses === undefined ? (options.max_uses[0] ?? null) : options.defaults.max_uses,
          });
        }
      } catch {
        if (!stale.signal.aborted) setLoaded({ state: "error" });
      }
    })();
    return () => stale.abort();
  }, [idToken, workspaceId, inviter, attempt]);

  useEffect(() => {
    if (loaded.state === "ready") onMembers?.(loaded.members);
  }, [loaded, onMembers]);

  async function act(key: string, action: (token: string) => Promise<void>) {
    setBusy(key);
    setFailure(null);
    try {
      await action(await idToken());
    } catch (error) {
      setFailure(sharingMessage(error));
    } finally {
      setBusy(null);
      setConfirming(null);
    }
  }

  if (loaded.state === "loading") {
    return (
      <div role="status" className="flex items-center gap-2 text-sm text-zinc-600 dark:text-zinc-400">
        <Spinner /> Loading who has access…
      </div>
    );
  }
  if (loaded.state === "error") {
    return (
      <div className="space-y-3">
        <Alert tone="error">We couldn't load who has access.</Alert>
        <Button variant="secondary" className="!w-auto" onClick={() => setAttempt((count) => count + 1)}>
          Try again
        </Button>
      </div>
    );
  }

  const { members, invites, options } = loaded;
  type Ready = Extract<Loaded, { state: "ready" }>;
  // Functional, so changes that finish out of order don't undo each other.
  const update = (change: (ready: Ready) => Partial<Ready>) => setLoaded((previous) => (previous.state === "ready" ? { ...previous, ...change(previous) } : previous));

  return (
    <div className="space-y-8">
      {failure && <Alert tone="error">{failure}</Alert>}

      {inviter && options && (
        <section aria-labelledby="share-invite" className="space-y-3">
          <h3 id="share-invite" className="text-sm font-semibold">
            Invite co-authors
          </h3>
          <div className="grid grid-cols-1 gap-2 sm:grid-cols-3">
            <div>
              <label htmlFor={ids.role} className="mb-1 block text-xs font-medium text-zinc-600 dark:text-zinc-400">
                Access
              </label>
              <select id={ids.role} className={selectClass} value={choice.role} onChange={(event) => setChoice({ ...choice, role: event.target.value as GrantableRole })}>
                {options.roles.map((value) => (
                  <option key={value} value={value}>
                    {ROLE_LABELS[value]}
                  </option>
                ))}
              </select>
            </div>
            <div>
              <label htmlFor={ids.expiry} className="mb-1 block text-xs font-medium text-zinc-600 dark:text-zinc-400">
                Link works for
              </label>
              <select id={ids.expiry} className={selectClass} value={choice.expires_in_hours} onChange={(event) => setChoice({ ...choice, expires_in_hours: Number(event.target.value) })}>
                {options.expires_in_hours.map((hours) => (
                  <option key={hours} value={hours}>
                    {expiryLabel(hours)}
                  </option>
                ))}
              </select>
            </div>
            <div>
              <label htmlFor={ids.uses} className="mb-1 block text-xs font-medium text-zinc-600 dark:text-zinc-400">
                Who can use it
              </label>
              <select
                id={ids.uses}
                className={selectClass}
                value={choice.max_uses ?? "any"}
                onChange={(event) => setChoice({ ...choice, max_uses: event.target.value === "any" ? null : Number(event.target.value) })}
              >
                {options.max_uses.map((uses) => (
                  <option key={uses ?? "any"} value={uses ?? "any"}>
                    {usesLabel(uses)}
                  </option>
                ))}
              </select>
            </div>
          </div>
          <Button
            loading={busy === "create"}
            onClick={() =>
              void act("create", async (token) => {
                const invite = await createInvite(token, workspaceId, choice);
                setCreated({ link: inviteLink(invite.token), invite });
                setCopied(false);
                // The list never holds a token, as the gateway never lists one.
                const listed = Object.fromEntries(Object.entries(invite).filter(([key]) => key !== "token")) as unknown as Invite;
                update((ready) => ({ invites: [listed, ...ready.invites] }));
              })
            }
          >
            Create invite link
          </Button>
          {created && (
            <div className="animate-enter space-y-2 rounded-xl border border-brand-200 bg-brand-50 p-3.5 dark:border-brand-900 dark:bg-brand-950/60">
              <label htmlFor={ids.link} className="block text-sm font-medium">
                Send this link to your co-author
              </label>
              <div className="flex gap-2">
                <input
                  id={ids.link}
                  readOnly
                  value={created.link}
                  onFocus={(event) => event.target.select()}
                  className="h-10 min-w-0 flex-1 rounded-lg border border-zinc-300 bg-white px-2.5 font-mono text-xs dark:border-zinc-700 dark:bg-zinc-900"
                />
                <Button
                  variant="secondary"
                  className="!h-10 !w-auto"
                  onClick={() =>
                    void navigator.clipboard.writeText(created.link).then(
                      () => setCopied(true),
                      () => setFailure("Copying didn't work. Select the link and copy it yourself."),
                    )
                  }
                >
                  {copied ? "Copied" : "Copy"}
                </Button>
              </div>
              <p className="text-xs text-zinc-700 dark:text-zinc-300" role="status">
                {copied ? "Link copied. " : ""}
                They'll join as “{ROLE_LABELS[created.invite.role].toLowerCase()}” once signed in. The link works until {formatDate(created.invite.expires_at)}, and this is the only time
                it's shown.
              </p>
            </div>
          )}
        </section>
      )}

      <section aria-labelledby="share-people">
        <h3 id="share-people" className="text-sm font-semibold">
          People with access <span className="font-normal text-zinc-500">({members.length})</span>
        </h3>
        <ul className="mt-3 space-y-1">
          {members.map((member) => {
            const you = member.user_id === me;
            const name = member.display_name || "Unnamed";
            return (
              <li key={member.user_id} className="flex items-center gap-3 rounded-lg py-1.5">
                <Avatar name={name} id={member.user_id} />
                <div className="min-w-0 flex-1">
                  <p className="truncate text-sm font-medium">
                    {name}
                    {you && <span className="font-normal text-zinc-500"> (you)</span>}
                  </p>
                  <p className="text-xs text-zinc-500">Since {formatDate(member.since)}</p>
                </div>
                {member.role === "owner" ? (
                  <span className="text-sm text-zinc-600 dark:text-zinc-400">Owner</span>
                ) : isOwner ? (
                  <div className="flex items-center gap-1">
                    <select
                      aria-label={`Access for ${name}`}
                      className={`${selectClass} !h-9 !w-auto`}
                      value={member.role}
                      disabled={busy === member.user_id}
                      onChange={(event) => {
                        const next = event.target.value as GrantableRole;
                        void act(member.user_id, async (token) => {
                          await changeMemberRole(token, workspaceId, member.user_id, next);
                          update((ready) => ({ members: ready.members.map((entry) => (entry.user_id === member.user_id ? { ...entry, role: next } : entry)) }));
                        });
                      }}
                    >
                      {(options?.roles ?? (["editor", "commenter", "viewer"] as const)).map((value) => (
                        <option key={value} value={value}>
                          {ROLE_LABELS[value]}
                        </option>
                      ))}
                    </select>
                    <button
                      type="button"
                      aria-label={confirming === member.user_id ? `Confirm removing ${name}` : `Remove ${name}`}
                      disabled={busy === member.user_id}
                      onClick={() => {
                        if (confirming !== member.user_id) {
                          setConfirming(member.user_id);
                          return;
                        }
                        void act(member.user_id, async (token) => {
                          await removeMember(token, workspaceId, member.user_id);
                          update((ready) => ({ members: ready.members.filter((entry) => entry.user_id !== member.user_id) }));
                        });
                      }}
                      className={`h-9 cursor-pointer rounded-lg px-2.5 text-sm font-semibold ${confirming === member.user_id ? "bg-red-600 text-white hover:bg-red-700" : "text-zinc-600 hover:bg-zinc-100 dark:text-zinc-400 dark:hover:bg-zinc-900"}`}
                    >
                      {confirming === member.user_id ? "Remove?" : "Remove"}
                    </button>
                  </div>
                ) : you ? (
                  <button
                    type="button"
                    disabled={busy === member.user_id}
                    onClick={() => {
                      if (confirming !== member.user_id) {
                        setConfirming(member.user_id);
                        return;
                      }
                      void act(member.user_id, async (token) => {
                        await removeMember(token, workspaceId, member.user_id);
                        onLeft();
                      });
                    }}
                    className={`h-9 cursor-pointer rounded-lg px-2.5 text-sm font-semibold ${confirming === member.user_id ? "bg-red-600 text-white hover:bg-red-700" : "text-zinc-600 hover:bg-zinc-100 dark:text-zinc-400 dark:hover:bg-zinc-900"}`}
                  >
                    {confirming === member.user_id ? "Leave this document?" : "Leave"}
                  </button>
                ) : (
                  <span className="text-sm text-zinc-600 dark:text-zinc-400">{ROLE_LABELS[member.role]}</span>
                )}
              </li>
            );
          })}
        </ul>
      </section>

      {inviter && invites.length > 0 && (
        <section aria-labelledby="share-links">
          <h3 id="share-links" className="text-sm font-semibold">
            Open invite links
          </h3>
          <ul className="mt-3 space-y-2">
            {invites.map((invite) => (
              <li key={invite.id} className="flex items-center gap-3 rounded-xl border border-zinc-200 px-3.5 py-2.5 dark:border-zinc-800">
                <div className="min-w-0 flex-1 text-sm">
                  <p className="font-medium">{ROLE_LABELS[invite.role]}</p>
                  <p className="text-xs text-zinc-500">
                    Until {formatDate(invite.expires_at)} · used {invite.uses}
                    {invite.max_uses === null ? "" : ` of ${invite.max_uses}`}
                  </p>
                </div>
                <button
                  type="button"
                  disabled={busy === invite.id}
                  onClick={() =>
                    void act(invite.id, async (token) => {
                      await revokeInvite(token, workspaceId, invite.id);
                      update((ready) => ({ invites: ready.invites.filter((entry) => entry.id !== invite.id) }));
                      if (created?.invite.id === invite.id) setCreated(null);
                    })
                  }
                  className="h-9 cursor-pointer rounded-lg px-2.5 text-sm font-semibold text-red-700 hover:bg-red-50 dark:text-red-300 dark:hover:bg-red-950/50"
                >
                  Revoke
                </button>
              </li>
            ))}
          </ul>
        </section>
      )}

      {!inviter && <p className="text-sm text-zinc-600 dark:text-zinc-400">Only the owner and editors can invite people.</p>}
    </div>
  );
}
