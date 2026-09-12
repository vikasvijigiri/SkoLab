"use client";

import { ButtonHTMLAttributes, forwardRef } from "react";
import { motion } from "framer-motion";
import { cn } from "@/lib/utils";
import { Spinner } from "./Spinner";

// `signal` = the one primary action on a surface (warm --accent-signal fill).
// `primary` stays for brand / navigation actions.
type Variant = "signal" | "primary" | "outlined" | "ghost" | "text";

// framer-motion's `motion.button` redefines the drag/animation event handlers
// with its own (gesture) signatures, which collide with React's DOM types when
// props are spread through. Drop the conflicting handlers from the public API —
// none of them are used here.
type MotionSafeButtonAttributes = Omit<
  ButtonHTMLAttributes<HTMLButtonElement>,
  "onDrag" | "onDragStart" | "onDragEnd" | "onAnimationStart" | "onAnimationEnd" | "onAnimationIteration"
>;

interface ButtonProps extends MotionSafeButtonAttributes {
  variant?: Variant;
  /** `lg` (h-14) is for marketing / hero surfaces only. */
  size?: "md" | "lg";
  loading?: boolean;
  error?: boolean;
  fullWidth?: boolean;
}

export const Button = forwardRef<HTMLButtonElement, ButtonProps>(
  (
    {
      variant = "primary",
      size = "md",
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
    const base =
      "relative inline-flex items-center justify-center gap-2 rounded-md font-body font-semibold cursor-pointer transition-[background-color,color,border-color,opacity,transform] disabled:cursor-not-allowed focus-visible:outline-[3px] focus-visible:outline-offset-2 focus-visible:outline-accent-signal";

    const variants: Record<Variant, string> = {
      signal: cn(
        "h-12 text-[14px] shadow-card",
        error
          ? "bg-notification text-white disabled:opacity-50"
          : "bg-accent-signal text-text-on-primary hover:bg-accent-signal-dark disabled:bg-surface-subtle disabled:text-text-muted disabled:shadow-none"
      ),
      primary: cn(
        "h-12 text-[14px] shadow-card",
        error
          ? "bg-notification text-white disabled:opacity-50"
          : "bg-primary text-text-on-primary hover:bg-primary-dark disabled:bg-surface-subtle disabled:text-text-muted disabled:shadow-none"
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

    return (
      <motion.button
        ref={ref}
        disabled={isInert}
        className={cn(
          base,
          variants[variant],
          size === "lg" && "h-14! text-[15px]!",
          // Colour + a 1px lift on hover — never a scale-pop (DESIGN.md).
          !isInert && "hover:-translate-y-px active:translate-y-0",
          fullWidth && "w-full",
          variant !== "text" && "px-6",
          className
        )}
        {...props}
      >
        {loading ? <Spinner size={variant === "primary" || variant === "signal" ? 22 : 20} /> : children}
      </motion.button>
    );
  }
);
Button.displayName = "Button";
