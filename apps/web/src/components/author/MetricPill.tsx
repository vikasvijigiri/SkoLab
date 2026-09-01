/** A single labelled metric chip; dims to 50% opacity when the value is zero. */
export function MetricPill({ label, value, color }: { label: string; value: number; color: string }) {
  const isZero = value === 0;
  return (
    <div
      className="flex items-center gap-1.5 rounded-full border border-border px-2.5 py-1"
      style={{ opacity: isZero ? 0.5 : 1 }}
    >
      <span
        className="h-1.5 w-1.5 rounded-full"
        style={{ backgroundColor: isZero ? "var(--text-muted)" : color }}
      />
      <span className="font-body text-[11px] text-text-secondary">{label}</span>
      <span className="font-mono text-[11px] font-semibold text-text-primary">{value.toFixed(0)}</span>
    </div>
  );
}
