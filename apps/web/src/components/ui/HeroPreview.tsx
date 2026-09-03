"use client";

import { motion } from "framer-motion";
import { EASE_STANDARD } from "@/lib/motion";

/**
 * Decorative product mock for the landing hero — a stylized "impact dashboard"
 * (browser chrome, mini rail, 8-axis radar, stat tiles, sparkline) built from
 * divs/SVG, no real data. Purely visual; hidden from assistive tech. Gives the
 * hero a concrete product anchor without shipping a real screenshot that would
 * rot as the app changes.
 */

const RADAR_AXES = 8;
// One sample "impact signature" — values 0..1 per axis, hand-tuned for a
// pleasing asymmetric polygon.
const RADAR_VALUES = [0.9, 0.62, 0.78, 0.55, 0.83, 0.48, 0.7, 0.6];

function radarPoint(i: number, r: number, cx: number, cy: number) {
  const angle = (Math.PI * 2 * i) / RADAR_AXES - Math.PI / 2;
  return [cx + Math.cos(angle) * r, cy + Math.sin(angle) * r] as const;
}

function RadarChart() {
  const cx = 90;
  const cy = 90;
  const maxR = 74;
  const rings = [0.25, 0.5, 0.75, 1];
  const poly = RADAR_VALUES.map((v, i) => radarPoint(i, v * maxR, cx, cy).join(",")).join(" ");

  return (
    <svg viewBox="0 0 180 180" className="h-full w-full" aria-hidden>
      {rings.map((rr) => (
        <polygon
          key={rr}
          points={Array.from({ length: RADAR_AXES }, (_, i) => radarPoint(i, rr * maxR, cx, cy).join(",")).join(" ")}
          fill="none"
          stroke="var(--border-color)"
          strokeWidth="1"
        />
      ))}
      {Array.from({ length: RADAR_AXES }, (_, i) => {
        const [x, y] = radarPoint(i, maxR, cx, cy);
        return <line key={i} x1={cx} y1={cy} x2={x} y2={y} stroke="var(--border-color)" strokeWidth="1" />;
      })}
      <motion.polygon
        points={poly}
        fill="color-mix(in srgb, var(--primary) 22%, transparent)"
        stroke="var(--primary)"
        strokeWidth="2"
        initial={{ opacity: 0, scale: 0.6 }}
        animate={{ opacity: 1, scale: 1 }}
        transition={{ duration: 0.8, delay: 0.5, ease: EASE_STANDARD }}
        style={{ transformOrigin: "center" }}
      />
    </svg>
  );
}

const STAT_TILES = [
  { label: "h-index", value: "47", accent: "var(--primary)" },
  { label: "Citations", value: "12.8k", accent: "var(--accent-teal)" },
  { label: "i10-index", value: "93", accent: "var(--accent-violet)" },
];

// A gentle upward sparkline.
const SPARK = "0,34 14,30 28,31 42,24 56,25 70,17 84,18 98,10 112,12 126,5";

export function HeroPreview() {
  return (
    <motion.div
      initial={{ opacity: 0, y: 28 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ duration: 0.7, delay: 0.35, ease: EASE_STANDARD }}
      className="relative mx-auto mt-14 w-full max-w-4xl"
      aria-hidden
    >
      {/* Glow bed */}
      <div
        className="pointer-events-none absolute -inset-8 rounded-[32px] opacity-70 blur-2xl"
        style={{ background: "var(--gradient-mesh)" }}
      />

      <div
        className="relative overflow-hidden rounded-[16px] border border-border bg-surface shadow-elevated"
        style={{ borderTop: "1px solid color-mix(in srgb, var(--primary) 30%, var(--border-color))" }}
      >
        {/* Browser chrome */}
        <div className="flex items-center gap-2 border-b border-border px-4 py-2.5">
          <span className="h-2.5 w-2.5 rounded-full bg-accent-rose/70" />
          <span className="h-2.5 w-2.5 rounded-full bg-accent-amber/70" />
          <span className="h-2.5 w-2.5 rounded-full bg-accent-emerald/70" />
          <span className="ml-3 rounded-md bg-surface-subtle px-3 py-1 font-mono text-[10px] text-text-muted">
            skolab.app/home
          </span>
        </div>

        <div className="flex">
          {/* Mini rail */}
          <div className="hidden w-40 shrink-0 flex-col gap-1 border-r border-border p-3 sm:flex">
            <div className="mb-2 h-6 w-20 rounded bg-surface-subtle" />
            {["Home", "Discovery", "Horizon", "Nexus", "Workspace"].map((n, i) => (
              <div
                key={n}
                className={`flex items-center gap-2 rounded-md px-2 py-1.5 font-body text-[11px] ${
                  i === 0 ? "bg-primary/10 text-primary" : "text-text-muted"
                }`}
              >
                <span
                  className="h-1.5 w-1.5 rounded-full"
                  style={{ background: i === 0 ? "var(--primary)" : "var(--text-muted)" }}
                />
                {n}
              </div>
            ))}
          </div>

          {/* Main panel */}
          <div className="min-w-0 flex-1 p-4 sm:p-5">
            <div className="mb-3 flex items-center justify-between">
              <div className="h-4 w-40 rounded bg-surface-subtle" />
              <div className="h-6 w-20 rounded-md bg-primary/15" />
            </div>

            <div className="grid grid-cols-1 gap-3 md:grid-cols-[180px_1fr]">
              {/* Radar card */}
              <div className="rounded-[12px] border border-border p-3">
                <p className="mb-1 font-body text-[10px] font-semibold uppercase tracking-wide text-text-muted">
                  Impact signature
                </p>
                <div className="mx-auto h-[150px] w-[150px]">
                  <RadarChart />
                </div>
              </div>

              {/* Right column: stat tiles + sparkline */}
              <div className="flex flex-col gap-3">
                <div className="grid grid-cols-3 gap-2">
                  {STAT_TILES.map((t) => (
                    <div key={t.label} className="rounded-[10px] border border-border p-2.5">
                      <p className="font-mono text-[16px] font-bold text-text-primary">{t.value}</p>
                      <p className="mt-0.5 font-body text-[9px] uppercase tracking-wide text-text-muted">
                        {t.label}
                      </p>
                      <span className="mt-1.5 block h-0.5 w-full rounded-full" style={{ background: t.accent }} />
                    </div>
                  ))}
                </div>

                <div className="flex-1 rounded-[12px] border border-border p-3">
                  <p className="mb-2 font-body text-[10px] font-semibold uppercase tracking-wide text-text-muted">
                    Citation velocity · 12 mo
                  </p>
                  <svg viewBox="0 0 126 40" className="h-16 w-full" preserveAspectRatio="none" aria-hidden>
                    <motion.polyline
                      points={SPARK}
                      fill="none"
                      stroke="var(--accent-teal)"
                      strokeWidth="2"
                      strokeLinecap="round"
                      strokeLinejoin="round"
                      initial={{ pathLength: 0 }}
                      animate={{ pathLength: 1 }}
                      transition={{ duration: 1.1, delay: 0.7, ease: EASE_STANDARD }}
                    />
                  </svg>
                </div>
              </div>
            </div>
          </div>
        </div>
      </div>
    </motion.div>
  );
}
