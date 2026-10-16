import { useCallback, useEffect, useId, useRef, useState, type ReactNode } from "react";
import { Link } from "react-router";
import { describe, listActivity, markActivitySeen, type ActivityItem } from "../api/activity";
import { timeAgo } from "../lib/dates";
import { BellIcon, CrownIcon, PencilIcon, ShieldIcon, UserMinusIcon, UserPlusIcon } from "./Icons";
import { Spinner } from "./Spinner";

const POLL_MS = 60_000;

type Feed = { state: "loading" } | { state: "error" } | { state: "ready"; items: ActivityItem[]; unread: number; next: string };

const KIND_LOOK: Record<ActivityItem["kind"], { icon: ReactNode; tone: string }> = {
  document_edited: { icon: <PencilIcon className="size-3" />, tone: "bg-sky-500" },
  member_joined: { icon: <UserPlusIcon className="size-3" />, tone: "bg-emerald-500" },
  member_left: { icon: <UserMinusIcon className="size-3" />, tone: "bg-zinc-500" },
  member_removed: { icon: <UserMinusIcon className="size-3" />, tone: "bg-rose-500" },
  role_changed: { icon: <ShieldIcon className="size-3" />, tone: "bg-violet-500" },
  ownership_transferred: { icon: <CrownIcon className="size-3" />, tone: "bg-amber-500" },
};

/**
 * The header's bell: what co-authors did, newest first. The count refreshes
 * every minute while the page is visible and when the reader comes back to
 * it. Opening the panel marks everything read; what was new stays
 * highlighted until it closes.
 */
export function Notifications({ token, me }: { token: () => Promise<string>; me: string }) {
  const [feed, setFeed] = useState<Feed>({ state: "loading" });
  const [open, setOpen] = useState(false);
  const [more, setMore] = useState(false);
  const box = useRef<HTMLDivElement>(null);
  const trigger = useRef<HTMLButtonElement>(null);
  const panel = useRef<HTMLDivElement>(null);
  const id = useId();

  const load = useCallback(
    async (signal?: AbortSignal) => {
      try {
        const page = await listActivity(await token(), { pageSize: 20, ...(signal ? { signal } : {}) });
        if (!signal?.aborted) setFeed({ state: "ready", items: page.items, unread: page.unread, next: page.next_page_token });
        return page;
      } catch {
        // Keep what is on screen; only a first load shows the error.
        if (!signal?.aborted) setFeed((current) => (current.state === "ready" ? current : { state: "error" }));
        return null;
      }
    },
    [token],
  );

  useEffect(() => {
    const controller = new AbortController();
    void (async () => {
      try {
        const page = await listActivity(await token(), { pageSize: 20, signal: controller.signal });
        if (!controller.signal.aborted) setFeed({ state: "ready", items: page.items, unread: page.unread, next: page.next_page_token });
      } catch {
        if (!controller.signal.aborted) setFeed({ state: "error" });
      }
    })();
    const refresh = () => {
      if (document.visibilityState === "visible") void load();
    };
    const timer = setInterval(refresh, POLL_MS);
    document.addEventListener("visibilitychange", refresh);
    return () => {
      controller.abort();
      clearInterval(timer);
      document.removeEventListener("visibilitychange", refresh);
    };
  }, [load, token]);

  async function show() {
    setOpen(true);
    const page = await load();
    if (page && page.unread > 0) {
      try {
        await markActivitySeen(await token());
        setFeed((current) => (current.state === "ready" ? { ...current, unread: 0 } : current));
      } catch {
        // Still unread; the next refresh shows the count again.
      }
    }
  }

  function close(refocus = true) {
    setOpen(false);
    // What was new has been seen now.
    setFeed((current) => (current.state === "ready" ? { ...current, items: current.items.map((item) => ({ ...item, unread: false })) } : current));
    if (refocus) requestAnimationFrame(() => trigger.current?.focus());
  }

  // The panel takes focus when it opens; a click outside closes it.
  const closeLatest = useRef(close);
  useEffect(() => {
    closeLatest.current = close;
  });
  useEffect(() => {
    if (!open) return;
    panel.current?.focus();
    const away = (event: PointerEvent) => {
      if (!box.current?.contains(event.target as Node)) closeLatest.current(false);
    };
    const escape = (event: KeyboardEvent) => {
      if (event.key === "Escape") closeLatest.current();
    };
    document.addEventListener("pointerdown", away);
    document.addEventListener("keydown", escape);
    return () => {
      document.removeEventListener("pointerdown", away);
      document.removeEventListener("keydown", escape);
    };
  }, [open]);

  async function loadMore() {
    if (feed.state !== "ready" || !feed.next) return;
    setMore(true);
    try {
      const page = await listActivity(await token(), { pageSize: 20, pageToken: feed.next });
      setFeed({ ...feed, items: [...feed.items, ...page.items], next: page.next_page_token });
    } catch {
      // The button stays; trying again is the remedy.
    } finally {
      setMore(false);
    }
  }

  const unread = feed.state === "ready" ? feed.unread : 0;
  const badge = unread > 99 ? "99+" : String(unread);

  return (
    <div ref={box} className="relative">
      <button
        ref={trigger}
        type="button"
        aria-label={unread > 0 ? `Notifications, ${unread > 99 ? "more than 99" : unread} unread` : "Notifications"}
        aria-expanded={open}
        aria-controls={open ? id : undefined}
        title="Notifications"
        onClick={() => (open ? close() : void show())}
        className="relative grid size-10 cursor-pointer place-items-center rounded-full text-zinc-600 transition-colors hover:bg-zinc-100 hover:text-zinc-900 dark:text-zinc-400 dark:hover:bg-zinc-800 dark:hover:text-zinc-100"
      >
        <BellIcon className="size-5" />
        {unread > 0 && (
          <span aria-hidden="true" className="absolute top-1 right-1 grid h-4.5 min-w-4.5 place-items-center rounded-full bg-rose-500 px-1 text-[10px] leading-none font-bold text-white ring-2 ring-white tabular-nums dark:ring-zinc-950">
            {badge}
          </span>
        )}
      </button>
      {open && (
        <div
          ref={panel}
          id={id}
          role="dialog"
          aria-label="Notifications"
          tabIndex={-1}
          className="fixed inset-x-3 top-16 z-40 flex max-h-[min(32rem,calc(100dvh-6rem))] animate-fade sm:absolute sm:inset-x-auto sm:top-auto sm:right-0 sm:mt-2 sm:w-96 flex-col overflow-hidden rounded-2xl border border-zinc-200 bg-white text-zinc-900 shadow-2xl shadow-zinc-900/15 outline-none dark:border-zinc-700 dark:bg-zinc-900 dark:text-zinc-100"
        >
          <div className="flex items-center justify-between border-b border-zinc-200 px-4 py-3 dark:border-zinc-800">
            <h2 className="text-sm font-semibold">Notifications</h2>
            {feed.state === "ready" && feed.items.some((item) => item.unread) && <span className="rounded-full bg-brand-50 px-2 py-0.5 text-xs font-semibold text-brand-700 dark:bg-brand-950 dark:text-brand-300">New</span>}
          </div>
          <div className="min-h-0 flex-1 overflow-y-auto">
            {feed.state === "loading" ? (
              <p role="status" className="flex items-center gap-2 px-4 py-8 text-sm text-zinc-500">
                <Spinner /> Loading…
              </p>
            ) : feed.state === "error" ? (
              <div className="px-4 py-8 text-center text-sm text-zinc-600 dark:text-zinc-400">
                <p>We couldn't load your notifications.</p>
                <button type="button" onClick={() => void load()} className="mt-2 cursor-pointer font-semibold text-brand-700 underline underline-offset-4 dark:text-brand-300">
                  Try again
                </button>
              </div>
            ) : feed.items.length === 0 ? (
              <div className="px-6 py-10 text-center">
                <span aria-hidden="true" className="mx-auto grid size-12 place-items-center rounded-2xl bg-brand-50 text-brand-600 dark:bg-brand-950 dark:text-brand-300">
                  <BellIcon className="size-6" />
                </span>
                <p className="mt-3 text-sm font-semibold">You're all caught up</p>
                <p className="mt-1 text-sm text-zinc-500 dark:text-zinc-400">When co-authors edit or join your documents, you'll see it here.</p>
              </div>
            ) : (
              <ul className="divide-y divide-zinc-100 dark:divide-zinc-800">
                {feed.items.map((item) => (
                  <li key={item.id}>
                    <Entry item={item} me={me} onOpen={() => close(false)} />
                  </li>
                ))}
              </ul>
            )}
          </div>
          {feed.state === "ready" && feed.next && (
            <button
              type="button"
              onClick={() => void loadMore()}
              disabled={more}
              className="flex cursor-pointer items-center justify-center gap-2 border-t border-zinc-200 py-2.5 text-sm font-semibold text-brand-700 hover:bg-zinc-50 disabled:opacity-60 dark:border-zinc-800 dark:text-brand-300 dark:hover:bg-zinc-800"
            >
              {more && <Spinner />} Show older
            </button>
          )}
        </div>
      )}
    </div>
  );
}

function Entry({ item, me, onOpen }: { item: ActivityItem; me: string; onOpen: () => void }) {
  const look = KIND_LOOK[item.kind];
  const [lead, tail] = describe(item, me);
  const who = item.actor?.display_name || "Someone";
  const initial = who.trim()[0]?.toUpperCase() ?? "?";
  // Removed from it, nothing to open.
  const reachable = !(item.kind === "member_removed" && item.subject?.id === me);
  const body = (
    <>
      <span className="relative shrink-0" aria-hidden="true">
        <span className="grid size-9 place-items-center rounded-full bg-gradient-to-br from-zinc-200 to-zinc-300 text-sm font-semibold text-zinc-700 dark:from-zinc-700 dark:to-zinc-800 dark:text-zinc-200">{initial}</span>
        <span className={`absolute -right-1 -bottom-1 grid size-4.5 place-items-center rounded-full text-white ring-2 ring-white dark:ring-zinc-900 ${look.tone}`}>{look.icon}</span>
      </span>
      <span className="min-w-0 flex-1">
        <span className="block text-sm leading-snug text-zinc-700 dark:text-zinc-300">
          {lead} <span className="font-semibold text-zinc-900 dark:text-zinc-100">{item.workspace.title}</span>
          {tail}
        </span>
        <span className="mt-0.5 block text-xs text-zinc-500 dark:text-zinc-400">
          {item.kind === "document_edited" && item.count > 1 ? `${item.count} saves · ` : ""}
          <time dateTime={item.occurred_at}>{timeAgo(item.occurred_at)}</time>
        </span>
      </span>
      {item.unread && <span aria-label="New" className="mt-1.5 size-2 shrink-0 rounded-full bg-brand-500" />}
    </>
  );
  const row = `flex items-start gap-3 px-4 py-3 ${item.unread ? "bg-brand-50/60 dark:bg-brand-950/40" : ""}`;
  return reachable ? (
    <Link to={`/editor/${item.workspace.id}`} onClick={onOpen} className={`${row} transition-colors hover:bg-zinc-50 dark:hover:bg-zinc-800/60`}>
      {body}
    </Link>
  ) : (
    <div className={row}>{body}</div>
  );
}
