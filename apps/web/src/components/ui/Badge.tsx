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
        color: accentColor,
        ...style,
      }}
      {...props}
    >
      {children}
    </span>
  );
}

interface ChipProps extends HTMLAttributes<HTMLButtonElement> {
  selected?: boolean;
}

/** Web port of the pill Chip spec — subtle bg by default, flips to primary when selected. */
export function Chip({ selected = false, className, children, ...props }: ChipProps) {
  return (
    <motion.button
      type="button"
      whileHover={{ scale: 1.05, boxShadow: "0 4px 14px color-mix(in srgb, var(--primary) 25%, transparent)" }}
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
