"use client";

import { HTMLAttributes, forwardRef } from "react";
import { motion } from "framer-motion";
import { cn } from "@/lib/utils";
import { TRANSITION_NORMAL } from "@/lib/motion";

interface CardProps extends HTMLAttributes<HTMLDivElement> {
  /** Tints a top accent bar + hover glow with this color. */
  accentColor?: string;
  interactive?: boolean;
  /** Showcase mode (landing/feature cards): lifts + glows accentColor on hover. */
  glow?: boolean;
}

/**
 * Real elevation (soft layered shadows), not border-only depth — the web
 * app's own design language, independent of the Android app's flat style.
 */
export const Card = forwardRef<HTMLDivElement, CardProps>(
  ({ accentColor, interactive = false, glow = false, className, style, children, ...props }, ref) => {
    const sharedStyle = {
      border: "1px solid var(--border-color)",
      borderTop: accentColor ? `2px solid ${accentColor}` : undefined,
      transitionTimingFunction: "var(--ease-standard)",
      ...style,
    };

    if (glow) {
      return (
        <motion.div
          whileHover={{
            y: -5,
            boxShadow: accentColor
              ? `var(--shadow-elevated), 0 0 0 1px color-mix(in srgb, ${accentColor} 25%, transparent)`
              : "var(--shadow-elevated)",
          }}
          transition={TRANSITION_NORMAL}
          className={cn("rounded-lg bg-surface p-4 shadow-card", className)}
          style={sharedStyle}
          {...(props as React.ComponentProps<typeof motion.div>)}
        >
          {children}
        </motion.div>
      );
    }

    return (
      <div
        ref={ref}
        className={cn(
          "rounded-lg bg-surface p-4 shadow-card transition-[transform,box-shadow] duration-[var(--motion-fast)]",
          interactive && "cursor-pointer hover:shadow-card-hover active:scale-[0.99]",
          className
        )}
        style={sharedStyle}
        {...props}
      >
        {children}
      </div>
    );
  }
);
Card.displayName = "Card";
