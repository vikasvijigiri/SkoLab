import { useState } from "react";
import { Keys } from "./CommandBar";
import { SHORTCUTS } from "./shortcuts";

/** Every shortcut, grouped and searchable. Lives in a Sheet. */
export function ShortcutList() {
  const [query, setQuery] = useState("");
  const words = query.toLowerCase().split(/\s+/).filter(Boolean);
  const shown = SHORTCUTS.filter((shortcut) => words.every((word) => `${shortcut.label} ${shortcut.keys} ${shortcut.group}`.toLowerCase().includes(word)));
  const groups = [...new Set(shown.map((shortcut) => shortcut.group))];
  return (
    <div className="space-y-5">
      <label className="block">
        <span className="sr-only">Search shortcuts</span>
        <input
          data-autofocus
          type="search"
          value={query}
          onChange={(event) => setQuery(event.target.value)}
          placeholder="Search shortcuts"
          className="h-10 w-full rounded-lg border border-zinc-300 bg-white px-3 text-sm dark:border-zinc-700 dark:bg-zinc-900"
        />
      </label>
      {groups.map((group) => (
        <section key={group} aria-label={group}>
          <h3 className="mb-1.5 text-xs font-semibold tracking-wide text-zinc-500 uppercase dark:text-zinc-400">{group}</h3>
          <ul className="divide-y divide-zinc-100 dark:divide-zinc-900">
            {shown
              .filter((shortcut) => shortcut.group === group)
              .map((shortcut) => (
                <li key={shortcut.id} className="flex items-center justify-between gap-4 py-2 text-sm">
                  <span>{shortcut.label}</span>
                  <Keys keys={shortcut.keys} className="shrink-0" />
                </li>
              ))}
          </ul>
        </section>
      ))}
      {shown.length === 0 && <p className="text-sm text-zinc-500 dark:text-zinc-400">No shortcut matches “{query}”.</p>}
    </div>
  );
}
