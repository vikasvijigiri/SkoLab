"use client";

import { motion } from "framer-motion";

interface Axis {
  label: string;
  value: number; // 0-100
  color: string;
}

/** 8-axis octagon radar — web port of MetricsRadarSection. Pure SVG, no chart library. */
export function RadarChart({ axes, size = 260 }: { axes: Axis[]; size?: number }) {
  const center = size / 2;
  const radius = size * 0.36;
  const n = axes.length;

  const pointFor = (i: number, r: number) => {
    const angle = (Math.PI * 2 * i) / n - Math.PI / 2;
    return [center + r * Math.cos(angle), center + r * Math.sin(angle)] as const;
  };

  const ringLevels = [0.25, 0.5, 0.75, 1];
  // A true 0 still gets a small visual floor (6% of radius) — otherwise several
  // zero axes collapse the polygon to a degenerate sliver that reads as "broken"
  // rather than "no data yet". The floor is purely visual; labels show the real number.
  const MIN_FRACTION = 0.06;
  const dataPoints = axes.map((a, i) => {
    const fraction = Math.max(0, Math.min(100, a.value)) / 100;
    return pointFor(i, radius * Math.max(fraction, MIN_FRACTION));
  });
  const dataPath = dataPoints.map((p) => p.join(",")).join(" ");

  return (
    <svg width={size} height={size} viewBox={`0 0 ${size} ${size}`}>
      <defs>
        <filter id="radar-glow" x="-30%" y="-30%" width="160%" height="160%">
          <feDropShadow dx="0" dy="0" stdDeviation="4" floodColor="var(--primary)" floodOpacity="0.35" />
        </filter>
      </defs>

      {ringLevels.map((level) => {
        const ringPoints = Array.from({ length: n }, (_, i) => pointFor(i, radius * level).join(",")).join(" ");
        return (
          <polygon
            key={level}
            points={ringPoints}
            fill="none"
            stroke="var(--border-color)"
            strokeWidth={1}
            opacity={0.6}
          />
        );
      })}

      {axes.map((_, i) => {
        const [x, y] = pointFor(i, radius);
        return (
          <line key={i} x1={center} y1={center} x2={x} y2={y} stroke="var(--border-color)" strokeWidth={1} opacity={0.6} />
        );
      })}

      <motion.polygon
        points={dataPath}
        fill="color-mix(in srgb, var(--primary) 18%, transparent)"
        stroke="var(--primary)"
        strokeWidth={1.5}
        filter="url(#radar-glow)"
        style={{ transformOrigin: `${center}px ${center}px` }}
        initial={{ scale: 0, opacity: 0 }}
        animate={{ scale: 1, opacity: 1 }}
        transition={{ duration: 0.7, ease: [0.22, 1, 0.36, 1] }}
      />
      {dataPoints.map(([x, y], i) => (
        <motion.circle
          key={i}
          cx={x}
          cy={y}
          r={3}
          fill={axes[i]?.color ?? "var(--primary)"}
          initial={{ opacity: 0, scale: 0 }}
          animate={{ opacity: 1, scale: 1 }}
          transition={{ duration: 0.3, delay: 0.5 + i * 0.05 }}
          style={{ transformOrigin: `${x}px ${y}px` }}
        />
      ))}

      {axes.map((a, i) => {
        const [x, y] = pointFor(i, radius * 1.28);
        return (
          <text
            key={a.label}
            x={x}
            y={y}
            textAnchor="middle"
            dominantBaseline="middle"
            fontSize="9.5"
            fontFamily="var(--font-body)"
            fill="var(--text-muted)"
          >
            {a.label}
          </text>
        );
      })}
    </svg>
  );
}
