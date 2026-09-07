import { AnimatedCounter } from "@/components/ui/AnimatedCounter";

/** One cell of the author stats quad (H-Index, i10, Works, Citations). */
export function StatTile({ label, value }: { label: string; value: number }) {
  return (
    <div className="flex-1 rounded-md bg-surface-subtle px-3 py-3 text-center">
      <p className="data text-[18px] font-semibold text-text-primary">
        <AnimatedCounter to={value} />
      </p>
      <p className="eyebrow mt-1">{label}</p>
    </div>
  );
}
