import { useId } from "react";

export function Logo({ className = "" }: { className?: string }) {
  // Unique per instance: a gradient defined inside a hidden copy (the
  // desktop brand panel on phones) would otherwise paint nothing.
  const gradient = useId();
  return (
    <span className={`inline-flex items-center gap-2.5 font-semibold tracking-tight ${className}`}>
      <svg viewBox="0 0 32 32" aria-hidden="true" className="size-8 shrink-0">
        <defs>
          <linearGradient id={gradient} x1="0" y1="0" x2="1" y2="1">
            <stop offset="0" stopColor="#818cf8" />
            <stop offset="1" stopColor="#4f46e5" />
          </linearGradient>
        </defs>
        <rect width="32" height="32" rx="9" fill={`url(#${gradient})`} />
        <path
          d="M20.5 11.2c-.9-1.3-2.5-2-4.4-2-2.8 0-4.6 1.5-4.6 3.6 0 4.6 9 2.9 9 6.9 0 1.6-1.6 2.9-4.2 2.9-2.1 0-3.7-.9-4.6-2.3"
          fill="none"
          stroke="white"
          strokeWidth="2.4"
          strokeLinecap="round"
        />
      </svg>
      <span className="text-lg">SkoLab</span>
    </span>
  );
}
