import type { ReactNode } from "react";

type Tone = "error" | "success" | "info";

const tones: Record<Tone, string> = {
  error: "border-red-200 bg-red-50 text-red-800 dark:border-red-900/60 dark:bg-red-950/50 dark:text-red-200",
  success: "border-emerald-200 bg-emerald-50 text-emerald-800 dark:border-emerald-900/60 dark:bg-emerald-950/50 dark:text-emerald-200",
  info: "border-brand-200 bg-brand-50 text-brand-900 dark:border-brand-900 dark:bg-brand-950/60 dark:text-brand-100",
};

const icons: Record<Tone, string> = {
  error: "M12 8v5m0 3h.01M10.3 3.9 2.4 17.6A2 2 0 0 0 4.1 20.6h15.8a2 2 0 0 0 1.7-3L13.7 3.9a2 2 0 0 0-3.4 0z",
  success: "M20 6 9 17l-5-5",
  info: "M12 16v-4m0-4h.01M22 12a10 10 0 1 1-20 0 10 10 0 0 1 20 0z",
};

/** Errors interrupt (role=alert); confirmations are announced politely. */
export function Alert({ tone, children, id }: { tone: Tone; children: ReactNode; id?: string }) {
  return (
    <div
      id={id}
      role={tone === "error" ? "alert" : "status"}
      className={`flex animate-enter items-start gap-3 rounded-lg border px-3.5 py-3 text-sm leading-5 ${tones[tone]}`}
    >
      <svg viewBox="0 0 24 24" aria-hidden="true" className="mt-0.5 size-4 shrink-0" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
        <path d={icons[tone]} />
      </svg>
      <div className="min-w-0">{children}</div>
    </div>
  );
}
