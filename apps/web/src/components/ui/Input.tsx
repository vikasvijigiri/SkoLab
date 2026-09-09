"use client";

import { InputHTMLAttributes, forwardRef, useId, useState } from "react";
import { AlertCircle } from "lucide-react";
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
          <label htmlFor={inputId} className="eyebrow mb-2 block">
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
              "h-10 w-full rounded-xs border bg-surface-input px-3 font-body text-body text-text-primary placeholder:text-text-muted outline-none transition-[border-color,box-shadow] duration-[var(--motion-fast)]",
              "focus:border-accent-signal focus:shadow-[var(--shadow-focus)]",
              showError ? "border-notification" : "border-border-input",
              leadingIcon && "pl-10",
              className
            )}
            style={{ transitionTimingFunction: "var(--ease-standard)" }}
            {...props}
          />
        </div>
        {showError && (
          <p className="mt-1 flex items-center gap-1 font-body text-[12px] text-notification">
            <AlertCircle size={12} aria-hidden />
            {error}
          </p>
        )}
      </div>
    );
  }
);
Input.displayName = "Input";
