export function Spinner({ className = "size-4" }: { className?: string }) {
  return (
    <svg viewBox="0 0 24 24" aria-hidden="true" className={`animate-spin-slow ${className}`}>
      <circle cx="12" cy="12" r="9" fill="none" stroke="currentColor" strokeOpacity="0.25" strokeWidth="3" />
      <path d="M21 12a9 9 0 0 0-9-9" fill="none" stroke="currentColor" strokeWidth="3" strokeLinecap="round" />
    </svg>
  );
}

/** Shown while the identity provider restores a session; announced to screen readers. */
export function FullPageLoader({ label = "Loading" }: { label?: string }) {
  return (
    <div role="status" className="grid min-h-dvh place-items-center text-brand-600 dark:text-brand-400">
      <Spinner className="size-7" />
      <span className="sr-only">{label}</span>
    </div>
  );
}
