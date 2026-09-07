import { HTMLAttributes } from "react";
import { motion } from "framer-motion";
import { cn } from "@/lib/utils";
import { TRANSITION_FAST } from "@/lib/motion";

interface BadgeProps extends HTMLAttributes<HTMLSpanElement> {
  accentColor?: string;
}

/** Web port of `ScientificBadge` — 10%-alpha tinted pill, 40%-alpha border. */
export function Badge({ accentColor = "var(--primary)", className, style, children, ...props }: BadgeProps) {
  return (
    <span
      className={cn(
        "inline-flex items-center rounded-[4px] px-2 py-0.5 font-mono text-[11px] font-medium tracking-wide",
        className
      )}
      style={{
        backgroundColor: `color-mix(in srgb, ${accentColor} 10%, transparent)`,
        border: `0.5px solid color-mix(in srgb, ${accentColor} 40%, transparent)`,
        // Blend the accent toward the ink so small badge text clears WCAG AA
        // (raw accent-amber on its 10% tint measured ~4.3:1). Theme-safe:
        // --text-primary is dark in light mode, light in dark mode.
        color: `color-mix(in srgb, ${accentColor} 62%, var(--text-primary))`,
        ...style,
      }}
      {...props}
    >
      {children}
    </span>
  );
}

// See Button.tsx: framer-motion's motion.button overrides these handlers with
// gesture signatures that clash with React's DOM types when props are spread.
interface ChipProps
  extends Omit<
    HTMLAttributes<HTMLButtonElement>,
    "onDrag" | "onDragStart" | "onDragEnd" | "onAnimationStart" | "onAnimationEnd" | "onAnimationIteration"
  > {
  selected?: boolean;
}

/** Web port of the pill Chip spec — subtle bg by default, flips to primary when selected. */
export function Chip({ selected = false, className, children, ...props }: ChipProps) {
  return (
    <motion.button
      type="button"
      whileHover={{ scale: 1.05 }}
      whileTap={{ scale: 0.95 }}
      transition={TRANSITION_FAST}
      className={cn(
        "inline-flex h-8 cursor-pointer items-center rounded-full px-3 font-body text-[12px] font-medium transition-colors duration-[var(--motion-fast)]",
        selected ? "bg-primary text-text-on-primary" : "bg-surface-subtle text-primary",
        className
      )}
      style={{ transitionTimingFunction: "var(--ease-standard)" }}
      {...props}
    >
      {children}
    </motion.button>
  );
}
