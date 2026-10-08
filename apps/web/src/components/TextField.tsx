import { useId, type InputHTMLAttributes, type ReactNode, type Ref } from "react";

export interface TextFieldProps extends Omit<InputHTMLAttributes<HTMLInputElement>, "id"> {
  label: string;
  error?: string | null | undefined;
  hint?: ReactNode;
  /** Rendered at the right edge of the label row (e.g. "Forgot password?"). */
  labelAside?: ReactNode;
  /** Rendered inside the input's right edge (e.g. a show/hide toggle). */
  trailing?: ReactNode;
  ref?: Ref<HTMLInputElement>;
}

export function TextField({ label, error, hint, labelAside, trailing, className = "", ref, ...input }: TextFieldProps) {
  const id = useId();
  const hintId = hint ? `${id}-hint` : undefined;
  const errorId = error ? `${id}-error` : undefined;
  const describedBy = [errorId, hintId].filter(Boolean).join(" ") || undefined;

  return (
    <div className={className}>
      <div className="mb-1.5 flex items-baseline justify-between gap-3">
        <label htmlFor={id} className="text-sm font-medium text-zinc-800 dark:text-zinc-200">
          {label}
        </label>
        {labelAside}
      </div>
      <div className="relative">
        <input
          ref={ref}
          id={id}
          aria-invalid={error ? true : undefined}
          aria-describedby={describedBy}
          {...input}
          className={`block h-11 w-full rounded-lg border bg-white px-3.5 text-[15px] text-zinc-900 shadow-xs transition-[border-color,box-shadow] duration-150 placeholder:text-zinc-400 focus:outline-none focus:ring-4 dark:bg-zinc-900 dark:text-zinc-100 dark:placeholder:text-zinc-500 ${
            trailing ? "pr-12" : ""
          } ${
            error
              ? "border-red-500 focus:border-red-500 focus:ring-red-500/15 dark:border-red-500"
              : "border-zinc-300 focus:border-brand-500 focus:ring-brand-500/15 dark:border-zinc-700 dark:focus:border-brand-400"
          }`}
        />
        {trailing ? <div className="absolute inset-y-0 right-1 flex items-center">{trailing}</div> : null}
      </div>
      {error ? (
        <p id={errorId} className="mt-1.5 animate-enter text-[13px] leading-5 text-red-700 dark:text-red-400">
          {error}
        </p>
      ) : null}
      {hint ? (
        <div id={hintId} className="mt-1.5 text-[13px] leading-5 text-zinc-500 dark:text-zinc-400">
          {hint}
        </div>
      ) : null}
    </div>
  );
}
