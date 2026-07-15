"use client";

import { InputHTMLAttributes, forwardRef, useState } from "react";
import { cn } from "@/lib/utils";

interface InputProps extends InputHTMLAttributes<HTMLInputElement> {
  error?: string;
  leadingIcon?: React.ReactNode;
}

export const Input = forwardRef<HTMLInputElement, InputProps>(
  ({ error, leadingIcon, className, onBlur, ...props }, ref) => {
    const [touched, setTouched] = useState(false);
    const showError = touched && !!error;

    return (
      <div className="w-full">
        <div className="relative">
          {leadingIcon && (
            <span className="absolute left-3 top-1/2 -translate-y-1/2 text-text-muted">
              {leadingIcon}
            </span>
          )}
          <input
            ref={ref}
            onBlur={(e) => {
              setTouched(true);
              onBlur?.(e);
            }}
            className={cn(
              "h-13 w-full rounded-sm bg-surface-subtle px-4 font-body text-[14px] text-text-primary placeholder:text-text-muted outline-none transition-colors duration-[var(--motion-fast)]",
              "border focus:border-primary",
              showError ? "border-notification" : "border-transparent",
              leadingIcon && "pl-10",
              className
            )}
            style={{ transitionTimingFunction: "var(--ease-standard)" }}
            {...props}
          />
        </div>
        {showError && <p className="mt-1 font-body text-[12px] text-notification">{error}</p>}
      </div>
    );
  }
);
Input.displayName = "Input";
