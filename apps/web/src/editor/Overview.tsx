import type { ReactNode } from "react";
import { ROLE_LABELS, type Insights, type Role } from "../api/editorTypes";
import { formatDateTime, timeAgo } from "../lib/dates";

/** A ring that fills with the manuscript's progress. */
export function ProgressRing({ percent, size = 28, stroke = 3.5 }: { percent: number; size?: number; stroke?: number }) {
  const radius = (size - stroke) / 2;
  const circumference = 2 * Math.PI * radius;
  const clamped = Math.min(Math.max(percent, 0), 100);
  return (
    <svg width={size} height={size} viewBox={`0 0 ${size} ${size}`} aria-hidden="true" className="-rotate-90">
      <circle cx={size / 2} cy={size / 2} r={radius} fill="none" strokeWidth={stroke} className="stroke-zinc-200 dark:stroke-zinc-800" />
      <circle
        cx={size / 2}
        cy={size / 2}
        r={radius}
        fill="none"
        strokeWidth={stroke}
        strokeLinecap="round"
        strokeDasharray={circumference}
        strokeDashoffset={circumference * (1 - clamped / 100)}
        className={`transition-[stroke-dashoffset] duration-500 ${clamped === 100 ? "stroke-emerald-500" : "stroke-brand-600 dark:stroke-brand-400"}`}
      />
    </svg>
  );
}

const STATS: { key: keyof Insights["stats"]; label: string }[] = [
  { key: "words", label: "Words" },
  { key: "sections", label: "Sections" },
  { key: "figures", label: "Figures" },
  { key: "tables", label: "Tables" },
  { key: "equations", label: "Equations" },
  { key: "citations", label: "Citations" },
  { key: "references", label: "References" },
];

export interface OverviewDetails {
  createdAt: string;
  updatedAt: string | null;
  /** Who saved last, by name when known. */
  updatedBy: string | null;
  version: number;
  template: string | null;
  role: Role;
}

/** The manuscript at a glance: how complete it is, what it holds, and its history. */
export function Overview({ insights, details }: { insights: Insights; details: OverviewDetails }) {
  const { percent, checks } = insights.progress;
  const remaining = checks.filter((check) => !check.done).length;
  return (
    <div className="space-y-7">
      <section aria-labelledby="overview-progress">
        <div className="flex items-center gap-4">
          <div className="relative grid place-items-center">
            <ProgressRing percent={percent} size={76} stroke={7} />
            <span className="absolute text-lg font-semibold tabular-nums">{percent}%</span>
          </div>
          <div>
            <h3 id="overview-progress" className="font-semibold">
              {percent === 100 ? "Ready to submit" : "Progress"}
            </h3>
            <p className="text-sm text-zinc-600 dark:text-zinc-400">
              {remaining === 0 ? "Every check below is met." : `${remaining} of ${checks.length} checks to go. Updates each time the document saves.`}
            </p>
          </div>
        </div>
        <ul className="mt-4 divide-y divide-zinc-100 rounded-xl border border-zinc-200 dark:divide-zinc-900 dark:border-zinc-800">
          {checks.map((check) => (
            <li key={check.id} className="flex items-center gap-3 px-3.5 py-2.5 text-sm">
              {check.done ? (
                <span className="grid size-5 shrink-0 place-items-center rounded-full bg-emerald-500 text-white">
                  <svg viewBox="0 0 24 24" aria-hidden="true" className="size-3.5" fill="none" stroke="currentColor" strokeWidth="3" strokeLinecap="round" strokeLinejoin="round">
                    <path d="M20 6 9 17l-5-5" />
                  </svg>
                </span>
              ) : (
                <span aria-hidden="true" className="size-5 shrink-0 rounded-full border-2 border-zinc-300 dark:border-zinc-700" />
              )}
              <span className={check.done ? "" : "text-zinc-600 dark:text-zinc-400"}>{check.label}</span>
              <span className="sr-only">{check.done ? "(done)" : "(to do)"}</span>
            </li>
          ))}
        </ul>
        {insights.unresolved_citations.length > 0 && (
          <p className="mt-3 rounded-lg bg-amber-50 px-3.5 py-2.5 text-sm text-amber-900 dark:bg-amber-950/50 dark:text-amber-200">
            No reference for: <span className="font-mono text-xs">{insights.unresolved_citations.join(", ")}</span>
          </p>
        )}
      </section>

      <section aria-labelledby="overview-contents">
        <h3 id="overview-contents" className="text-sm font-semibold">
          Contents
        </h3>
        <dl className="mt-3 grid grid-cols-3 gap-2 sm:grid-cols-4">
          {STATS.map(({ key, label }) => (
            <div key={key} className={`rounded-xl bg-zinc-50 px-3 py-2.5 dark:bg-zinc-900 ${key === "words" ? "col-span-2" : ""}`}>
              <dt className="text-xs text-zinc-600 dark:text-zinc-400">{label}</dt>
              <dd className="text-lg font-semibold tabular-nums">{insights.stats[key].toLocaleString()}</dd>
            </div>
          ))}
        </dl>
      </section>

      <section aria-labelledby="overview-details">
        <h3 id="overview-details" className="text-sm font-semibold">
          Details
        </h3>
        <dl className="mt-3 space-y-2.5 text-sm">
          <Row label="Created">{formatDateTime(details.createdAt)}</Row>
          <Row label="Last saved">
            {details.updatedAt ? (
              <>
                <time dateTime={details.updatedAt} title={formatDateTime(details.updatedAt)}>
                  {timeAgo(details.updatedAt)}
                </time>
                {details.updatedBy && ` by ${details.updatedBy}`}
              </>
            ) : (
              "Not saved yet"
            )}
          </Row>
          <Row label="Version">{details.version}</Row>
          <Row label="Template">{details.template ?? "Blank document"}</Row>
          <Row label="Your access">{ROLE_LABELS[details.role]}</Row>
        </dl>
      </section>
    </div>
  );
}

function Row({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="flex justify-between gap-4">
      <dt className="text-zinc-600 dark:text-zinc-400">{label}</dt>
      <dd className="text-right font-medium">{children}</dd>
    </div>
  );
}
