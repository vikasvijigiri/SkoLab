import type { ButtonHTMLAttributes, ReactNode } from "react";
import { Spinner } from "./Spinner";

type Variant = "primary" | "secondary" | "ghost";

const variants: Record<Variant, string> = {
  primary:
    "bg-brand-600 text-white shadow-sm shadow-brand-900/10 hover:bg-brand-700 active:bg-brand-800 disabled:bg-brand-600/60 dark:hover:bg-brand-500 dark:active:bg-brand-700",
  secondary:
    "border border-zinc-300 bg-white text-zinc-900 shadow-sm hover:bg-zinc-50 active:bg-zinc-100 disabled:opacity-60 dark:border-zinc-700 dark:bg-zinc-900 dark:text-zinc-100 dark:hover:bg-zinc-800",
  ghost: "text-brand-700 hover:bg-brand-50 dark:text-brand-300 dark:hover:bg-brand-950",
};

type Size = "md" | "sm" | "xs";

// md fills its form row; sm is a toolbar button, as tall as the others there; xs fits a pane's header row.
const sizes: Record<Size, string> = {
  md: "h-11 w-full gap-2.5 px-4",
  sm: "h-8 w-auto gap-1.5 px-3",
  xs: "h-7 w-auto gap-1.5 px-2.5",
};

interface ButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: Variant;
  size?: Size;
  loading?: boolean;
  icon?: ReactNode;
}

export function Button({ variant = "primary", size = "md", loading = false, icon, disabled, className = "", children, ...rest }: ButtonProps) {
  return (
    <button
      type="button"
      {...rest}
      disabled={disabled || loading}
      aria-busy={loading || undefined}
      className={`relative inline-flex cursor-pointer items-center justify-center rounded-lg text-sm font-semibold transition-colors duration-150 disabled:cursor-not-allowed ${sizes[size]} ${variants[variant]} ${className}`}
    >
      {loading ? <Spinner /> : icon}
      <span>{children}</span>
    </button>
  );
}
