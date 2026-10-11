import { useEffect, useId, useMemo, useRef, useState, type ReactNode } from "react";
import { keyLabel } from "./shortcuts";

export interface Command {
  id: string;
  title: string;
  group: string;
  /** Shown on the right: a shortcut ("Mod+B") or a short note. */
  keys?: string;
  note?: string;
  /** Other words it is found by. */
  keywords?: string;
  run: () => void;
}

/** Keyboard keys as chips. */
export function Keys({ keys, className = "" }: { keys: string; className?: string }) {
  return (
    <span className={`inline-flex items-center gap-0.5 ${className}`}>
      {keyLabel(keys).map((key, index) => (
        <kbd
          key={index}
          className="min-w-5 rounded border border-zinc-300 bg-zinc-50 px-1 text-center font-sans text-[11px] leading-[18px] font-medium text-zinc-600 dark:border-zinc-700 dark:bg-zinc-900 dark:text-zinc-300"
        >
          {key}
        </kbd>
      ))}
    </span>
  );
}

/**
 * How well a query matches a title: every character in order, rewarding
 * matches at word starts and runs. -1 when it doesn't match.
 */
export function score(query: string, text: string): number {
  const q = query.toLowerCase().replace(/\s+/g, "");
  if (!q) return 0;
  const t = text.toLowerCase();
  if (t.includes(query.toLowerCase().trim())) return 1000 - t.indexOf(query.toLowerCase().trim());
  let total = 0;
  let at = 0;
  let run = 0;
  for (const char of q) {
    const found = t.indexOf(char, at);
    if (found < 0) return -1;
    const wordStart = found === 0 || /[\s/\\·:(-]/.test(t[found - 1] ?? "");
    run = found === at ? run + 1 : 0;
    total += 1 + (wordStart ? 8 : 0) + run * 3 - Math.min(found - at, 5);
    at = found + 1;
  }
  return total;
}

/**
 * The command bar: one search box for every action, section and file.
 * Arrow keys move, Enter runs, Escape closes.
 */
export function CommandBar({ commands, onPick, onClose, placeholder = "Search actions, sections and files…" }: { commands: readonly Command[]; onPick: (command: Command) => void; onClose: () => void; placeholder?: string }) {
  const [query, setQuery] = useState("");
  const [active, setActive] = useState(0);
  const listId = useId();
  const input = useRef<HTMLInputElement>(null);
  const list = useRef<HTMLDivElement>(null);

  const results = useMemo(() => {
    if (!query.trim()) return commands.slice(0, 60);
    return commands
      .map((command, order) => ({ command, order, rank: Math.max(score(query, command.title), score(query, `${command.group} ${command.keywords ?? ""}`) - 20) }))
      .filter((entry) => entry.rank >= 0)
      .sort((a, b) => b.rank - a.rank || a.order - b.order)
      .slice(0, 60)
      .map((entry) => entry.command);
  }, [commands, query]);

  const current = Math.min(active, Math.max(results.length - 1, 0));

  useEffect(() => {
    input.current?.focus();
  }, []);

  useEffect(() => {
    const row = list.current?.querySelector(`[data-index="${current}"]`);
    // Test browsers without layout lack scrollIntoView.
    if (row && "scrollIntoView" in row) row.scrollIntoView({ block: "nearest" });
  }, [current]);

  const pick = (command: Command | undefined) => {
    if (command) onPick(command);
  };

  let lastGroup = "";
  const rows: ReactNode[] = [];
  results.forEach((command, index) => {
    if (!query.trim() && command.group !== lastGroup) {
      rows.push(
        <div key={`group-${command.group}`} role="presentation" className="px-3 pt-3 pb-1 text-[11px] font-semibold tracking-wide text-zinc-500 uppercase dark:text-zinc-400">
          {command.group}
        </div>,
      );
      lastGroup = command.group;
    }
    const selected = index === current;
    rows.push(
      // The combobox owns the keyboard (arrows and Enter); options only take the pointer.
      // eslint-disable-next-line jsx-a11y/click-events-have-key-events
      <div
        key={command.id}
        id={`${listId}-${index}`}
        data-index={index}
        role="option"
        tabIndex={-1}
        aria-selected={selected}
        onMouseMove={() => setActive(index)}
        onMouseDown={(event) => event.preventDefault()}
        onClick={() => pick(command)}
        className={`mx-1.5 flex cursor-pointer items-center gap-3 rounded-lg px-3 py-2 text-sm ${selected ? "bg-brand-600 text-white" : "text-zinc-800 dark:text-zinc-200"}`}
      >
        <span className="min-w-0 flex-1 truncate">{command.title}</span>
        {query.trim() && <span className={`shrink-0 text-xs ${selected ? "text-brand-100" : "text-zinc-500 dark:text-zinc-400"}`}>{command.group}</span>}
        {command.note && <span className={`shrink-0 text-xs ${selected ? "text-brand-100" : "text-zinc-500 dark:text-zinc-400"}`}>{command.note}</span>}
        {command.keys && <Keys keys={command.keys} className={selected ? "opacity-90" : ""} />}
      </div>,
    );
  });

  return (
    <div className="fixed inset-0 z-50 flex items-start justify-center px-4 pt-[12vh]">
      <div aria-hidden="true" className="absolute inset-0 animate-fade bg-zinc-950/30 backdrop-blur-[2px]" onClick={onClose} />
      <div role="dialog" aria-modal="true" aria-label="Command bar" className="relative w-full max-w-xl animate-enter overflow-hidden rounded-2xl border border-zinc-200 bg-white shadow-2xl dark:border-zinc-800 dark:bg-zinc-950">
        <div className="flex items-center gap-3 border-b border-zinc-200 px-4 dark:border-zinc-800">
          <svg viewBox="0 0 24 24" aria-hidden="true" className="size-5 shrink-0 text-zinc-400" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round">
            <circle cx="11" cy="11" r="7" />
            <path d="m20 20-3.5-3.5" />
          </svg>
          <input
            ref={input}
            role="combobox"
            aria-expanded="true"
            aria-controls={listId}
            aria-activedescendant={results.length ? `${listId}-${current}` : undefined}
            aria-autocomplete="list"
            aria-label="Search commands"
            value={query}
            placeholder={placeholder}
            onChange={(event) => {
              setQuery(event.target.value);
              setActive(0);
            }}
            onKeyDown={(event) => {
              if (event.key === "ArrowDown" || (event.key === "n" && event.ctrlKey)) {
                event.preventDefault();
                setActive((current + 1) % Math.max(results.length, 1));
              } else if (event.key === "ArrowUp" || (event.key === "p" && event.ctrlKey)) {
                event.preventDefault();
                setActive((current - 1 + results.length) % Math.max(results.length, 1));
              } else if (event.key === "Home" && event.ctrlKey) {
                setActive(0);
              } else if (event.key === "End" && event.ctrlKey) {
                setActive(results.length - 1);
              } else if (event.key === "Enter") {
                event.preventDefault();
                pick(results[current]);
              } else if (event.key === "Escape") {
                event.preventDefault();
                event.stopPropagation();
                onClose();
              } else if (event.key === "Tab") {
                event.preventDefault();
              }
            }}
            className="h-14 min-w-0 flex-1 bg-transparent text-base outline-none placeholder:text-zinc-400 focus-visible:outline-none"
          />
          <Keys keys="Esc" />
        </div>
        <div ref={list} id={listId} role="listbox" aria-label="Commands" className="max-h-[min(60vh,26rem)] overflow-y-auto py-1.5">
          {rows}
        </div>
        {results.length === 0 && <p className="px-4 pt-2 pb-6 text-center text-sm text-zinc-500 dark:text-zinc-400">Nothing matches “{query}”.</p>}
      </div>
    </div>
  );
}
