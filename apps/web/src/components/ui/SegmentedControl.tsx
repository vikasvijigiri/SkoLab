"use client";

import { motion } from "framer-motion";
import { cn } from "@/lib/utils";

export interface SegmentedOption {
  value: string;
  label: string;
}

interface SegmentedControlProps {
  options: SegmentedOption[];
  value: string;
  onChange: (value: string) => void;
  /** Mono labels — use when the segments name data facets. Default: body font. */
  mono?: boolean;
  /** Unique per instance if two controls can mount together. */
  layoutId?: string;
  className?: string;
  "aria-label"?: string;
}

/**
 * Sliding-pill segmented control — extracted from the Discovery mode toggle so
 * every screen uses one implementation. The pill slide is the affordance; there
 * is no tap-scale (DESIGN.md: no `scale()` on interactive elements).
 */
export function SegmentedControl({
  options,
  value,
  onChange,
  mono = false,
  layoutId = "segmented-pill",
  className,
  "aria-label": ariaLabel,
}: SegmentedControlProps) {
  function move(delta: number) {
    const i = options.findIndex((o) => o.value === value);
    if (i === -1) return;
    const next = options[(i + delta + options.length) % options.length];
    if (next) onChange(next.value);
  }

  return (
    <div
      role="tablist"
      aria-label={ariaLabel}
      tabIndex={-1}
      onKeyDown={(e) => {
        if (e.key === "ArrowLeft") {
          e.preventDefault();
          move(-1);
        } else if (e.key === "ArrowRight") {
          e.preventDefault();
          move(1);
        }
      }}
      className={cn(
        "flex w-full gap-1 rounded-full bg-surface-subtle p-1 sm:w-auto",
        className,
      )}
    >
      {options.map((o) => {
        const selected = o.value === value;
        return (
          <button
            key={o.value}
            type="button"
            role="tab"
            aria-selected={selected}
            tabIndex={selected ? 0 : -1}
            onClick={() => onChange(o.value)}
            className={cn(
              "relative flex-1 rounded-full px-4 py-1.5 text-[13px] font-medium transition-colors duration-[var(--motion-fast)] sm:flex-none",
              mono ? "font-mono uppercase tracking-wide" : "font-body capitalize",
              selected
                ? "text-text-on-primary"
                : "text-text-secondary hover:bg-surface/60 hover:text-text-primary",
            )}
            style={{ transitionTimingFunction: "var(--ease-standard)" }}
          >
            {selected && (
              <motion.span
                layoutId={layoutId}
                className="absolute inset-0 rounded-full bg-primary"
                transition={{ type: "spring", stiffness: 400, damping: 32 }}
              />
            )}
            <span className="relative z-10">{o.label}</span>
          </button>
        );
      })}
    </div>
  );
}
