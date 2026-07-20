"use client";

import { InputHTMLAttributes, forwardRef, useId, useState } from "react";
import { cn } from "@/lib/utils";

interface InputProps extends InputHTMLAttributes<HTMLInputElement> {
  error?: string;
  leadingIcon?: React.ReactNode;
  label?: string;
}

export const Input = forwardRef<HTMLInputElement, InputProps>(
  ({ error, leadingIcon, label, className, onBlur, id, ...props }, ref) => {
    const [touched, setTouched] = useState(false);
    const showError = touched && !!error;
    const generatedId = useId();
    const inputId = id ?? (label ? generatedId : undefined);

    return (
      <div className="w-full">
        {label && (
          <label htmlFor={inputId} className="mb-1.5 block font-body text-[12.5px] font-medium text-text-secondary">
            {label}
          </label>
        )}
        <div className="relative">
          {leadingIcon && (
            <span className="absolute left-3 top-1/2 -translate-y-1/2 text-text-muted">
              {leadingIcon}
            </span>
          )}
          <input
            ref={ref}
            id={inputId}
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
