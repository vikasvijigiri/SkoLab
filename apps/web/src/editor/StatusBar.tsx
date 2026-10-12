import { Keys } from "./CommandBar";
import type { ProblemCounts } from "./latex/extensions";

interface StatusBarProps {
  words: number;
  section: string | null;
  problems: ProblemCounts;
  line: number;
  column: number;
  autoCompile: boolean;
  editable: boolean;
  onNextProblem: () => void;
  onAutoCompile: (on: boolean) => void;
  onCommands: () => void;
  onShortcuts: () => void;
}

function plural(count: number, one: string, many: string) {
  return `${count.toLocaleString()} ${count === 1 ? one : many}`;
}

/** A slim footer: words, where you are, problems, auto-compile, and the two ways to find everything else. */
export function StatusBar({ words, section, problems, line, column, autoCompile, editable, onNextProblem, onAutoCompile, onCommands, onShortcuts }: StatusBarProps) {
  const total = problems.errors + problems.warnings;
  return (
    <footer aria-label="Status" className="flex h-8 shrink-0 items-center gap-1 border-t border-zinc-200 bg-white/80 px-2 text-xs text-zinc-600 backdrop-blur dark:border-zinc-800 dark:bg-zinc-950/80 dark:text-zinc-400">
      <span className="px-1.5 tabular-nums" title="Words of running text, as the overview counts them">
        {plural(words, "word", "words")}
      </span>
      {section && (
        <>
          <span aria-hidden="true">·</span>
          <span className="max-w-56 truncate px-1.5" title="Current section">
            {section}
          </span>
        </>
      )}
      <button
        type="button"
        onClick={onNextProblem}
        disabled={total === 0}
        title={total ? "Go to the next problem (F8)" : "No problems found"}
        className={`ml-1 flex h-6 cursor-pointer items-center gap-1.5 rounded-md px-1.5 font-medium disabled:cursor-default ${problems.errors ? "text-red-700 hover:bg-red-50 dark:text-red-300 dark:hover:bg-red-950/50" : problems.warnings ? "text-amber-800 hover:bg-amber-50 dark:text-amber-300 dark:hover:bg-amber-950/40" : "text-emerald-700 dark:text-emerald-400"}`}
      >
        <span aria-hidden="true" className={`size-2 rounded-full ${problems.errors ? "bg-red-500" : problems.warnings ? "bg-amber-500" : "bg-emerald-500"}`} />
        {total === 0 ? "No problems" : [problems.errors && plural(problems.errors, "error", "errors"), problems.warnings && plural(problems.warnings, "warning", "warnings")].filter(Boolean).join(", ")}
      </button>
      <span className="ml-auto hidden px-1.5 tabular-nums sm:inline">
        Ln {line}, Col {column}
      </span>
      {editable && (
        <label className="hidden h-6 cursor-pointer items-center gap-1.5 rounded-md px-1.5 hover:bg-zinc-100 sm:flex dark:hover:bg-zinc-900" title="Compile a moment after you stop typing">
          <input type="checkbox" checked={autoCompile} onChange={(event) => onAutoCompile(event.target.checked)} className="size-3.5 accent-brand-600" />
          Auto-compile
        </label>
      )}
      <button type="button" onClick={onCommands} className="hidden h-6 cursor-pointer items-center gap-1.5 rounded-md px-1.5 hover:bg-zinc-100 md:flex dark:hover:bg-zinc-900">
        Commands <Keys keys="Mod+K" />
      </button>
      <button type="button" onClick={onShortcuts} className="hidden h-6 cursor-pointer items-center gap-1.5 rounded-md px-1.5 hover:bg-zinc-100 md:flex dark:hover:bg-zinc-900">
        Shortcuts <Keys keys="?" />
      </button>
    </footer>
  );
}
