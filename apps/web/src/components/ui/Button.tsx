"use client";

import { ButtonHTMLAttributes, forwardRef } from "react";
import { motion } from "framer-motion";
import { cn } from "@/lib/utils";
import { TRANSITION_FAST } from "@/lib/motion";

type Variant = "primary" | "outlined" | "ghost" | "text";

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
    const base =
      "relative inline-flex items-center justify-center gap-2 rounded-md font-body font-semibold cursor-pointer transition-[background-color,color,border-color,opacity] disabled:cursor-not-allowed focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-primary";

    const variants: Record<Variant, string> = {
      primary: cn(
        "h-12 text-[14px] shadow-card",
        error
          ? "bg-notification text-white disabled:opacity-50"
          : "bg-primary text-text-on-primary disabled:bg-surface-subtle disabled:text-text-muted disabled:shadow-none"
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
        whileHover={isInert ? undefined : { scale: 1.025 }}
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
        {loading ? <Spinner size={variant === "primary" ? 22 : 20} /> : children}
      </motion.button>
    );
  }
);
Button.displayName = "Button";
