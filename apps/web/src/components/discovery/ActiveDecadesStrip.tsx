/** One pip per decade from the first active decade to the last; filled = the
 *  researcher published in it. Answers "how long have they been at this". */
export function ActiveDecadesStrip({ decades }: { decades: number[] }) {
  if (decades.length === 0) return null;
  const start = decades[0]!;
  const end = decades[decades.length - 1]!;
  const all: number[] = [];
  for (let d = start; d <= end; d += 10) all.push(d);
  const active = new Set(decades);

  return (
    <span
      role="img"
      aria-label={`Active decades: ${decades.map((d) => `${d}s`).join(", ")}`}
      className="inline-flex items-center gap-1"
    >
      {all.map((d) => (
        <span
          key={d}
          className="h-1.5 w-1.5 rounded-full"
          style={{ backgroundColor: active.has(d) ? "var(--text-secondary)" : "var(--border-strong)" }}
        />
      ))}
    </span>
  );
}
