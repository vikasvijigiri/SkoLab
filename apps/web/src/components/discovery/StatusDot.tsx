import type { ActivityState } from "@/lib/types";

/** Activity → semantic token (DESIGN.md: `--accent-live` for live/positive,
 *  `--warning` for caution, `--text-muted` for inert). Deceased is neutral grey
 *  plus an "in memoriam" mark — never `--danger`. */
const DOT: Record<ActivityState, { color: string; label: string }> = {
  active: { color: "var(--accent-live)", label: "Active — published within the last 2 years" },
  winding_down: { color: "var(--warning)", label: "Winding down — last published 3–5 years ago" },
  dormant: { color: "var(--text-muted)", label: "Dormant — no publications in 5+ years" },
};

export function StatusDot({
  activity,
  deceased,
}: {
  activity: ActivityState;
  deceased?: { year: number };
}) {
  if (deceased) {
    return (
      <span
        className="inline-flex items-center gap-1 font-mono text-[10px] font-medium uppercase tracking-wide text-text-muted"
        title={`Deceased ${deceased.year}`}
      >
        <span aria-hidden="true">✦</span> in memoriam
      </span>
    );
  }
  const d = DOT[activity];
  return (
    <span
      role="img"
      aria-label={d.label}
      title={d.label}
      className="inline-block h-2 w-2 shrink-0 rounded-full"
      style={{ backgroundColor: d.color }}
    />
  );
}
