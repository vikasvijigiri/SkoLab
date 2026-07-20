"use client";

import { ButtonHTMLAttributes, forwardRef, useState } from "react";
import { motion } from "framer-motion";
import { cn } from "@/lib/utils";
import { TRANSITION_FAST } from "@/lib/motion";

type Variant = "primary" | "outlined" | "ghost" | "text";

interface ButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: Variant;
  loading?: boolean;
  error?: boolean;
  fullWidth?: boolean;
}

const Spinner = ({ size = 18 }: { size?: number }) => (
  <span
    className="inline-block animate-spin rounded-full border-2 border-current border-t-transparent"
    style={{ width: size, height: size }}
    aria-hidden
  />
);

const HOVER_GLOW: Record<Variant, string> = {
  primary: "var(--shadow-glow-primary)",
  outlined: "0 6px 18px color-mix(in srgb, var(--primary) 20%, transparent)",
  ghost: "0 6px 18px color-mix(in srgb, var(--primary) 20%, transparent)",
  text: "none",
};

export const Button = forwardRef<HTMLButtonElement, ButtonProps>(
  (
    {
      variant = "primary",
      loading = false,
      error = false,
      fullWidth = true,
      disabled,
      className,
      children,
      ...props
    },
    ref
  ) => {
    const [hovered, setHovered] = useState(false);
    const base =
      "relative inline-flex items-center justify-center gap-2 rounded-md font-body font-semibold cursor-pointer transition-[background-color,color,border-color,opacity] disabled:cursor-not-allowed focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-primary";

    const variants: Record<Variant, string> = {
      primary: cn(
        "h-13 text-[14px] shadow-card",
        error
          ? "bg-notification text-white disabled:opacity-50"
          : "bg-primary text-text-on-primary disabled:opacity-30 disabled:text-text-on-primary/50"
      ),
      outlined: cn(
        "h-11 text-[13px] border bg-transparent",
        error
          ? "border-notification/80 text-notification"
          : "border-primary/50 text-text-primary",
        "disabled:opacity-50"
      ),
      ghost: cn(
        "h-11 text-[13px] border border-primary text-primary bg-transparent",
        "disabled:opacity-50"
      ),
      text: cn("h-9 text-[13px] text-primary bg-transparent px-2", "disabled:opacity-50"),
    };

    const isInert = disabled || loading;
    const showShimmer = variant !== "text" && !isInert;

    return (
      <motion.button
        ref={ref}
        disabled={isInert}
        onHoverStart={() => setHovered(true)}
        onHoverEnd={() => setHovered(false)}
        whileHover={isInert ? undefined : { scale: 1.025, boxShadow: HOVER_GLOW[variant] }}
        whileTap={isInert ? undefined : { scale: 0.97 }}
        transition={TRANSITION_FAST}
        className={cn(
          base,
          variants[variant],
          fullWidth && "w-full",
          variant !== "text" && "px-6",
          className
        )}
        {...props}
      >
        {/* Shimmer sweep, driven by the button's own hover state (can't self-trigger — this span ignores pointer events). */}
        {showShimmer && (
          <span aria-hidden className="pointer-events-none absolute inset-0 overflow-hidden rounded-md">
            <motion.span
              className="absolute inset-y-0 w-1/3"
              style={{ background: "linear-gradient(105deg, transparent 20%, rgba(255,255,255,0.16) 50%, transparent 80%)" }}
              animate={{ x: hovered ? "220%" : "-120%" }}
              transition={{ duration: 0.7, ease: "easeInOut" }}
            />
          </span>
        )}
        {loading ? <Spinner size={variant === "primary" ? 22 : 20} /> : children}
      </motion.button>
    );
  }
);
Button.displayName = "Button";
