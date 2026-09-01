"use client";

import { useEffect, useState } from "react";
import { Command } from "cmdk";
import { useRouter } from "next/navigation";
import { Search, Moon, LogOut, FolderPlus, Clock, UserRound, FileText } from "lucide-react";
import { NAV_ITEMS } from "@/lib/nav";
import { useAuth } from "@/lib/hooks/AuthProvider";
import { useDebounce } from "@/lib/hooks/useDebounce";
import { useRecentlyViewed } from "@/lib/hooks/useRecentlyViewed";
import { applyTheme, initialTheme, nextTheme } from "@/lib/theme";
import { getAuthorSuggestions } from "@/lib/api/endpoints";
import { MathText } from "@/components/ui/MathText";
import type { AuthorSuggestion, OpenAlexWork } from "@/lib/types";

const groupHeadingClass =
  "px-2 pb-1 pt-2 font-mono text-[10.5px] font-semibold uppercase tracking-wide text-text-muted [&_[cmdk-group-items]]:mt-1.5";
const itemClass =
  "flex cursor-pointer items-center gap-2.5 rounded-md px-2.5 py-2 font-body text-[13.5px] text-text-primary aria-selected:bg-surface-subtle";

export function CommandPalette({ open, onOpenChange }: { open: boolean; onOpenChange: (open: boolean) => void }) {
  const router = useRouter();
  const { signOut } = useAuth();
  const { recents } = useRecentlyViewed();

  const [query, setQuery] = useState("");
  const debouncedQuery = useDebounce(query, 250);
  const queryTooShort = debouncedQuery.trim().length < 3;
  const [fetchedAuthors, setFetchedAuthors] = useState<AuthorSuggestion[]>([]);
  const [fetchedPapers, setFetchedPapers] = useState<OpenAlexWork[]>([]);
  const [searching, setSearching] = useState(false);

  // Derived rather than reset via effect — stale fetched results just stay
  // hidden once the query drops below the search threshold, no extra setState.
  const authors = queryTooShort ? [] : fetchedAuthors;
  const papers = queryTooShort ? [] : fetchedPapers;

  useEffect(() => {
    if (queryTooShort) return;
    const q = debouncedQuery.trim();
    let cancelled = false;
    (async () => {
      setSearching(true);
      try {
        const [authorRes, paperRes] = await Promise.all([
          getAuthorSuggestions(q).catch(() => []),
          fetch(`/api/openalex/works?q=${encodeURIComponent(q)}`)
            .then((r) => (r.ok ? r.json() : []))
            .catch(() => []),
        ]);
        if (!cancelled) {
          setFetchedAuthors(authorRes.slice(0, 5));
          setFetchedPapers((Array.isArray(paperRes) ? paperRes : []).slice(0, 5));
        }
      } finally {
        if (!cancelled) setSearching(false);
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [debouncedQuery, queryTooShort]);

  function handleOpenChange(next: boolean) {
    if (!next) setQuery("");
    onOpenChange(next);
  }

  function go(href: string) {
    router.push(href);
    handleOpenChange(false);
  }

  function handleToggleTheme() {
    applyTheme(nextTheme(initialTheme()));
    handleOpenChange(false);
  }

  function handleSignOut() {
    signOut();
    handleOpenChange(false);
  }

  const showDefaultGroups = queryTooShort;

  return (
    <Command.Dialog
      open={open}
      onOpenChange={handleOpenChange}
      label="Command Menu"
      shouldFilter={false}
      overlayClassName="cmdk-overlay fixed inset-0 z-50 bg-black/40 backdrop-blur-[2px]"
      contentClassName="cmdk-content fixed left-1/2 top-[12vh] z-50 w-full max-w-lg overflow-hidden rounded-lg border border-border bg-surface shadow-elevated"
      className="flex flex-col"
    >
      <div className="flex items-center gap-2.5 border-b border-border px-4">
        <Search size={16} className="shrink-0 text-text-muted" />
        <Command.Input
          // A command palette is a modal dialog whose whole purpose is to type
          // into immediately on open — focusing its input is expected, accessible
          // behaviour here, not a focus trap.
          // eslint-disable-next-line jsx-a11y/no-autofocus
          autoFocus
          value={query}
          onValueChange={setQuery}
          placeholder="Search papers, researchers, or jump to a page..."
          className="h-13 w-full bg-transparent font-body text-[14px] text-text-primary placeholder:text-text-muted outline-none"
        />
        <kbd className="shrink-0 rounded border border-border px-1.5 py-0.5 font-mono text-[10.5px] text-text-muted">
          Esc
        </kbd>
      </div>

      <Command.List className="max-h-[60vh] overflow-y-auto p-2">
        <Command.Empty className="py-8 text-center font-body text-[13px] text-text-muted">
          {searching ? "Searching..." : "No results found."}
        </Command.Empty>

        {showDefaultGroups && (
          <Command.Group heading="Navigate" className={groupHeadingClass}>
            {NAV_ITEMS.map((item) => (
              <Command.Item key={item.href} onSelect={() => go(item.href)} className={itemClass}>
                <item.Icon size={15} className="text-text-muted" />
                {item.label}
              </Command.Item>
            ))}
          </Command.Group>
        )}

        {showDefaultGroups && recents.length > 0 && (
          <Command.Group heading="Recent" className={groupHeadingClass}>
            {recents.map((item) => (
              <Command.Item key={item.id} onSelect={() => go(item.href)} className={itemClass}>
                <Clock size={15} className="shrink-0 text-text-muted" />
                <span className="truncate"><MathText text={item.title} /></span>
              </Command.Item>
            ))}
          </Command.Group>
        )}

        {showDefaultGroups && (
          <Command.Group heading="Quick actions" className={groupHeadingClass}>
            <Command.Item onSelect={handleToggleTheme} className={itemClass}>
              <Moon size={15} className="text-text-muted" />
              Toggle theme
            </Command.Item>
            <Command.Item onSelect={() => go("/workspace")} className={itemClass}>
              <FolderPlus size={15} className="text-text-muted" />
              New CoLab project
            </Command.Item>
            <Command.Item
              onSelect={handleSignOut}
              className="flex cursor-pointer items-center gap-2.5 rounded-md px-2.5 py-2 font-body text-[13.5px] text-notification aria-selected:bg-notification/10"
            >
              <LogOut size={15} />
              Sign out
            </Command.Item>
          </Command.Group>
        )}

        {authors.length > 0 && (
          <Command.Group heading="Researchers" className={groupHeadingClass}>
            {authors.map((a) => (
              <Command.Item
                key={a.id}
                onSelect={() =>
                  go(
                    `/author/${encodeURIComponent(a.id)}?name=${encodeURIComponent(a.display_name)}&focus=${encodeURIComponent(a.field_of_study ?? "")}`
                  )
                }
                className={itemClass}
              >
                <UserRound size={15} className="shrink-0 text-text-muted" />
                <span className="truncate">{a.display_name}</span>
                {a.institution && (
                  <span className="truncate font-body text-[12px] text-text-muted">{a.institution}</span>
                )}
              </Command.Item>
            ))}
          </Command.Group>
        )}

        {papers.length > 0 && (
          <Command.Group heading="Papers" className={groupHeadingClass}>
            {papers.map((w) => {
              const shortId = w.id.split("/").pop() ?? w.id;
              return (
                <Command.Item key={w.id} onSelect={() => go(`/paper/${encodeURIComponent(shortId)}`)} className={itemClass}>
                  <FileText size={15} className="shrink-0 text-text-muted" />
                  <span className="truncate"><MathText text={w.display_name} /></span>
                </Command.Item>
              );
            })}
          </Command.Group>
        )}
      </Command.List>
    </Command.Dialog>
  );
}
