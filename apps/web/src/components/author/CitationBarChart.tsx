import type { CitationHeatmap } from "@/lib/types";

export function CitationBarChart({ data }: { data: CitationHeatmap }) {
  const max = Math.max(...data.citations, 1);

  return (
    <div className="flex items-end gap-2" style={{ height: 120 }}>
      {data.years.map((year, i) => {
        const h = Math.max(4, (data.citations[i] / max) * 100);
        return (
          <div key={year} className="flex flex-1 flex-col items-center gap-1.5">
            <div className="flex h-24 w-full items-end">
              <div
                className="w-full rounded-t-[4px] transition-[height] duration-[var(--motion-normal)]"
                style={{
                  height: `${h}%`,
                  background: "var(--gradient-hero)",
                  transitionTimingFunction: "var(--ease-standard)",
                }}
                title={`${data.citations[i]} citations in ${year}`}
              />
            </div>
            <span className="font-mono text-[10px] text-text-muted">{String(year).slice(2)}</span>
          </div>
        );
      })}
    </div>
  );
}
