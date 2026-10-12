import type { Outline } from "./latex/scan";
import { headingAt } from "./latex/scan";

/**
 * The paper's structure as you type: headings with their word counts, then
 * figures and tables. Clicking an entry moves the cursor to it.
 */
export function OutlinePanel({ outline, line, onJump }: { outline: Outline; line: number; onJump: (line: number) => void }) {
  const current = headingAt(outline.headings, line);
  const most = Math.max(1, ...outline.headings.map((heading) => heading.words));
  const base = Math.min(...outline.headings.map((heading) => heading.level), 1);
  const empty = outline.headings.length === 0 && outline.floats.length === 0;
  return (
    <nav aria-label="Outline" className="flex h-full min-h-0 flex-col">
      <div className="min-h-0 flex-1 overflow-y-auto px-2 py-2">
        {empty && <p className="px-2 py-3 text-sm text-zinc-500 dark:text-zinc-400">Add a \section to see the outline here.</p>}
        {outline.headings.length > 0 && (
          <ol className="space-y-px">
            {outline.headings.map((heading) => {
              const active = heading === current;
              return (
                <li key={heading.from}>
                  <button
                    type="button"
                    onClick={() => onJump(heading.line)}
                    aria-current={active ? "location" : undefined}
                    title={`Line ${heading.line} · ${heading.words.toLocaleString()} words`}
                    className={`group flex w-full cursor-pointer flex-col rounded-md py-1.5 pr-2 text-left text-sm transition-colors ${active ? "bg-brand-50 text-brand-800 dark:bg-brand-950/60 dark:text-brand-200" : "text-zinc-700 hover:bg-zinc-100 dark:text-zinc-300 dark:hover:bg-zinc-900"}`}
                    // Indentation by heading depth.
                    style={{ paddingLeft: `${0.5 + (heading.level - base) * 0.85}rem` }}
                  >
                    <span className="flex w-full items-baseline gap-2">
                      <span className={`min-w-0 flex-1 truncate ${heading.level <= 1 ? "font-semibold" : ""}`}>{heading.title}</span>
                      <span className="shrink-0 text-[11px] text-zinc-500 tabular-nums dark:text-zinc-400">{heading.words.toLocaleString()}</span>
                    </span>
                    <span aria-hidden="true" className="mt-1 h-0.5 w-full overflow-hidden rounded bg-zinc-200/70 dark:bg-zinc-800">
                      <span className="block h-full rounded bg-brand-500/70" style={{ width: `${Math.round((heading.words / most) * 100)}%` }} />
                    </span>
                  </button>
                </li>
              );
            })}
          </ol>
        )}
        {outline.floats.length > 0 && (
          <>
            <h3 className="mt-4 mb-1 px-2 text-[11px] font-semibold tracking-wide text-zinc-500 uppercase dark:text-zinc-400">Figures and tables</h3>
            <ul className="space-y-px">
              {outline.floats.map((item, index) => (
                <li key={item.from}>
                  <button
                    type="button"
                    onClick={() => onJump(item.line)}
                    className="flex w-full cursor-pointer items-center gap-2 rounded-md px-2 py-1.5 text-left text-sm text-zinc-700 hover:bg-zinc-100 dark:text-zinc-300 dark:hover:bg-zinc-900"
                  >
                    <span className="grid size-5 shrink-0 place-items-center rounded bg-zinc-100 text-[10px] font-bold text-zinc-600 dark:bg-zinc-800 dark:text-zinc-300" aria-hidden="true">
                      {item.kind === "table" ? "T" : "F"}
                    </span>
                    <span className="min-w-0 flex-1 truncate">
                      <span className="sr-only">{item.kind === "table" ? "Table" : "Figure"}: </span>
                      {item.caption || item.label || `${item.kind === "table" ? "Table" : "Figure"} ${index + 1}`}
                    </span>
                  </button>
                </li>
              ))}
            </ul>
          </>
        )}
      </div>
    </nav>
  );
}
