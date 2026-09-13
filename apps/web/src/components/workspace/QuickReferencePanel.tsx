"use client";

import { ArrowRight, Check, Circle } from "lucide-react";
import { cn } from "@/lib/utils";
import { countCitationMarkers, type JournalTemplate } from "@/components/workspace/ResearchTools";

/** A generic IMRaD-shaped checklist — not journal-specific, so it never
 *  claims compliance with any one publisher's required section names. */
const STRUCTURE_CHECKLIST = ["Abstract", "Introduction", "Methods", "Results", "Discussion", "Conclusion", "References"];

/** Text of the first section whose heading matches `headingMatch`, up to the
 *  next heading. Used to pull the abstract out of a single markdown body —
 *  documents have no separate `abstract` field (see `CollabDocument`). */
function extractSection(body: string, headingMatch: RegExp): string {
  const lines = body.split(/\r?\n/);
  let capturing = false;
  const collected: string[] = [];
  for (const line of lines) {
    const isHeading = /^#{1,4}\s+/.test(line);
    if (isHeading) {
      if (capturing) break;
      capturing = headingMatch.test(line);
      continue;
    }
    if (capturing) collected.push(line);
  }
  return collected.join(" ").replace(/\s+/g, " ").trim();
}

function barColor(ratio: number): string {
  if (ratio > 1) return "bg-notification";
  if (ratio > 0.85) return "bg-accent-amber";
  return "bg-accent-emerald";
}

export interface QuickReferenceStatus {
  /** Drives the collapsed dock rail's "abstract length" status dot. */
  abstractOverLimit: boolean;
  abstractNearLimit: boolean;
}

/** Pulled out so the collapsed dock rail (which doesn't render this panel's
 *  body) can still show an accurate status dot. */
export function computeQuickReferenceStatus(
  body: string,
  template: JournalTemplate | undefined,
): QuickReferenceStatus {
  const limit = template?.specs?.abstractCharLimit;
  if (!limit) return { abstractOverLimit: false, abstractNearLimit: false };
  const chars = extractSection(body, /abstract/i).length;
  return { abstractOverLimit: chars > limit, abstractNearLimit: chars > limit * 0.85 && chars <= limit };
}

/**
 * Live, honest reference panel: word count vs. the selected journal's limit,
 * abstract character count, reference count, a structure checklist —
 * computed from the real document body every render. No compliance score:
 * this mirrors the originality preflight's own stated rule (ResearchTools.tsx).
 */
export function QuickReferencePanel({
  documentBody,
  template,
  onOpenTemplates,
}: {
  documentBody: string;
  template: JournalTemplate | undefined;
  onOpenTemplates: () => void;
}) {
  const words = documentBody.trim() ? documentBody.trim().split(/\s+/).length : 0;
  const abstractChars = extractSection(documentBody, /abstract/i).length;
  const citations = countCitationMarkers(documentBody);
  const headings = (documentBody.match(/^#{1,4}\s+(.+)$/gm) ?? []).map((h) =>
    h.replace(/^#{1,4}\s+/, "").toLowerCase(),
  );

  const wordLimit = template?.specs?.wordLimit;
  const abstractLimit = template?.specs?.abstractCharLimit;

  return (
    <div className="flex flex-col">
      <div className="border-b border-border p-4">
        <div className="flex items-center gap-1.5">
          <span className={cn("h-2 w-2 shrink-0 rounded-full", template ? "bg-primary" : "bg-border")} />
          <span className="font-body text-[13px] font-semibold text-text-primary">
            {template ? template.label : "No journal selected"}
          </span>
        </div>
      </div>

      <div className="flex flex-col gap-3.5 border-b border-border p-4">
        <div>
          <div className="flex items-baseline justify-between">
            <span className="font-body text-[11.5px] text-text-secondary">Body length</span>
            <span className="mono text-[11px] text-text-muted">
              {wordLimit
                ? `${words.toLocaleString()} / ${wordLimit.toLocaleString()} words`
                : `${words.toLocaleString()} words`}
            </span>
          </div>
          {wordLimit ? (
            <div className="mt-1.5 h-[5px] overflow-hidden rounded-full bg-border">
              <div
                className={cn("h-full transition-all", barColor(words / wordLimit))}
                style={{ width: `${Math.min(100, (words / wordLimit) * 100)}%` }}
              />
            </div>
          ) : (
            <p className="mt-1 font-body text-[10.5px] text-text-muted">
              {template
                ? "No fixed word limit — this journal caps length by page count."
                : "Pick a journal to check length against its limit."}
            </p>
          )}
        </div>

        <div>
          <div className="flex items-baseline justify-between">
            <span className="font-body text-[11.5px] text-text-secondary">Abstract</span>
            <span
              className={cn(
                "mono text-[11px]",
                abstractLimit && abstractChars > abstractLimit
                  ? "text-notification"
                  : abstractLimit
                    ? "text-accent-amber"
                    : "text-text-muted",
              )}
            >
              {abstractLimit ? `${abstractChars} / ${abstractLimit} chars` : `${abstractChars} chars`}
            </span>
          </div>
          {abstractLimit && (
            <div className="mt-1.5 h-[5px] overflow-hidden rounded-full bg-border">
              <div
                className={cn("h-full transition-all", barColor(abstractChars / abstractLimit))}
                style={{ width: `${Math.min(100, (abstractChars / abstractLimit) * 100)}%` }}
              />
            </div>
          )}
        </div>

        <div className="flex items-baseline justify-between">
          <span className="font-body text-[11.5px] text-text-secondary">References</span>
          <span className="mono text-[11px] text-text-muted">
            {citations} citation{citations === 1 ? "" : "s"}
          </span>
        </div>
      </div>

      <div className="flex flex-col gap-2 border-b border-border p-4">
        <span className="mono text-[10px] font-semibold uppercase tracking-[0.07em] text-text-muted">Structure</span>
        {STRUCTURE_CHECKLIST.map((item) => {
          const present = headings.some((h) => h.includes(item.toLowerCase()));
          return (
            <div key={item} className="flex items-center gap-2">
              {present ? (
                <Check size={14} className="shrink-0 text-accent-emerald" strokeWidth={2.5} />
              ) : (
                <Circle size={9} className="ml-[2.5px] shrink-0 text-border" strokeWidth={2.5} />
              )}
              <span className={cn("font-body text-[12.5px]", present ? "text-text-primary" : "text-text-muted")}>
                {item}
              </span>
            </div>
          );
        })}
        <p className="mt-1 font-body text-[10.5px] leading-relaxed text-text-muted">
          Counts and structure only — not a fabricated compliance score.
        </p>
      </div>

      <div className="p-3.5">
        <button
          type="button"
          onClick={onOpenTemplates}
          className="flex items-center gap-1 font-body text-[11.5px] font-semibold text-primary hover:underline"
        >
          Browse all domains &amp; journals <ArrowRight size={12} />
        </button>
      </div>
    </div>
  );
}
