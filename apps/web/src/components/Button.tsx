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

interface ButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: Variant;
  loading?: boolean;
  icon?: ReactNode;
}

export function Button({ variant = "primary", loading = false, icon, disabled, className = "", children, ...rest }: ButtonProps) {
  return (
    <button
      type="button"
      {...rest}
      disabled={disabled || loading}
      aria-busy={loading || undefined}
      className={`relative inline-flex h-11 w-full cursor-pointer items-center justify-center gap-2.5 rounded-lg px-4 text-sm font-semibold transition-colors duration-150 disabled:cursor-not-allowed ${variants[variant]} ${className}`}
    >
      {loading ? <Spinner /> : icon}
      <span>{children}</span>
    </button>
  );
}
