import { AnimatedCounter } from "@/components/ui/AnimatedCounter";

/** One cell of the author stats quad (H-Index, i10, Works, Citations). */
export function StatTile({ label, value }: { label: string; value: number }) {
  return (
    <div className="flex-1 rounded-[8px] bg-surface-subtle px-3 py-2.5 text-center">
      <p className="font-mono text-[18px] font-semibold tabular-nums text-text-primary">
        <AnimatedCounter to={value} />
      </p>
      <p className="mt-0.5 font-body text-[10.5px] font-medium uppercase tracking-wide text-text-muted">
        {label}
      </p>
    </div>
  );
}
