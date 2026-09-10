import type { MomentumState } from "@/lib/types";

const LABEL: Record<MomentumState, string> = {
  rising: "rising",
  steady: "steady",
  cooling: "cooling",
};

/** Citations-per-year as a tiny inline polyline. Static — DESIGN.md forbids
 *  animating data values. Colour from the influence data-viz role (`--primary`). */
export function MomentumSparkline({
  values,
  momentum,
  width = 64,
  height = 18,
}: {
  values: number[];
  momentum: MomentumState;
  width?: number;
  height?: number;
}) {
  if (values.length < 2) return null;
  const max = Math.max(...values);
  const min = Math.min(...values);
  const span = max - min || 1;
  const pad = 1.5;
  const points = values
    .map((v, i) => {
      const x = (i / (values.length - 1)) * width;
      const y = pad + (height - 2 * pad) - ((v - min) / span) * (height - 2 * pad);
      return `${x.toFixed(1)},${y.toFixed(1)}`;
    })
    .join(" ");

  return (
    <svg
      width={width}
      height={height}
      viewBox={`0 0 ${width} ${height}`}
      role="img"
      aria-label={`Citations per year — ${LABEL[momentum]}`}
      className="shrink-0"
    >
      <polyline
        points={points}
        fill="none"
        stroke="var(--primary)"
        strokeWidth={2}
        strokeLinecap="round"
        strokeLinejoin="round"
      />
    </svg>
  );
}
