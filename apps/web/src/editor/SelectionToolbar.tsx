import type { ImproveGoal } from "../api/editorTypes";
import { SparkIcon } from "../components/Icons";
import type { CursorInfo } from "./LatexEditor";

const QUICK: { goal: ImproveGoal; label: string }[] = [
  { goal: "grammar", label: "Grammar" },
  { goal: "clarity", label: "Clarity" },
  { goal: "concise", label: "Concise" },
  { goal: "academic", label: "Academic" },
  { goal: "flow", label: "Flow" },
];

const HEIGHT = 40;

/**
 * A small bar above the text selected in the source: one click improves the
 * selection for a goal. Buttons keep the editor's focus (and selection) by
 * not taking it on mousedown.
 */
export function SelectionToolbar({ cursor, onPick, onOpen }: { cursor: CursorInfo | null; onPick: (goal: ImproveGoal) => void; onOpen: () => void }) {
  const selection = cursor?.selection;
  if (!cursor?.focused || !selection || selection.to - selection.from < 3) return null;
  const above = selection.top - HEIGHT - 8;
  const top = above > 56 ? above : selection.bottom + 8;
  const left = Math.max(8, Math.min(selection.left, window.innerWidth - 470));
  const keep = (event: React.MouseEvent) => event.preventDefault();
  return (
    <div
      role="toolbar"
      aria-label="Improve the selection with AI"
      style={{ top, left }}
      className="fixed z-30 flex animate-fade items-center gap-0.5 rounded-xl border border-zinc-200 bg-white/95 p-1 shadow-xl shadow-zinc-900/10 backdrop-blur dark:border-zinc-700 dark:bg-zinc-900/95"
    >
      <button
        type="button"
        onMouseDown={keep}
        onClick={onOpen}
        title="Open the AI writing assistant for this selection"
        className="flex h-8 cursor-pointer items-center gap-1.5 rounded-lg bg-gradient-to-r from-brand-600 to-violet-600 px-2.5 text-xs font-semibold text-white hover:from-brand-700 hover:to-violet-700"
      >
        <SparkIcon className="size-3.5" />
        Improve
      </button>
      {QUICK.map((item) => (
        <button
          key={item.goal}
          type="button"
          onMouseDown={keep}
          onClick={() => onPick(item.goal)}
          title={`Improve the selection: ${item.label.toLowerCase()}`}
          className="h-8 cursor-pointer rounded-lg px-2.5 text-xs font-medium text-zinc-700 hover:bg-zinc-100 hover:text-zinc-900 dark:text-zinc-300 dark:hover:bg-zinc-800 dark:hover:text-white"
        >
          {item.label}
        </button>
      ))}
    </div>
  );
}
