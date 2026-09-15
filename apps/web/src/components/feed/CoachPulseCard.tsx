"use client";

import Link from "next/link";
import { useId } from "react";
import { Activity, ArrowUpRight, Bookmark, Check, UserPlus } from "lucide-react";
import { cn, focusRing, shortOpenAlexId } from "@/lib/utils";
import type { CoachPulseImpact, CoachPulseTrackedActivity, CoachPulseWorthTracking, GrantMatch } from "@/lib/types";

function initials(name: string): string {
  const parts = name.trim().split(/\s+/).filter(Boolean);
  if (parts.length === 0) return "?";
  const first = parts[0]![0] ?? "";
  const last = parts.length > 1 ? (parts[parts.length - 1]![0] ?? "") : "";
  return (first + last).toUpperCase();
}

function relTime(iso: string): string {
  const ts = new Date(iso).getTime();
  if (Number.isNaN(ts)) return "";
  const days = Math.round((Date.now() - ts) / 86_400_000);
  if (days <= 0) return "today";
  if (days === 1) return "1d";
  if (days < 30) return `${days}d`;
  return `${Math.round(days / 30)}mo`;
}

/** A ring segment — real data only: `pct` is always a value the caller
 *  actually has (match score, never a decorative placeholder). */
function FitRing({ pct, color }: { pct: number; color: string }) {
  const r = 18;
  const c = 2 * Math.PI * r;
  const offset = c * (1 - Math.max(0, Math.min(100, pct)) / 100);
  return (
    <div className="relative h-[46px] w-[46px] shrink-0" aria-hidden="true">
      <svg width="46" height="46" viewBox="0 0 46 46">
        <circle cx="23" cy="23" r={r} fill="none" stroke="var(--border-color)" strokeWidth="4" />
        <circle
          cx="23"
          cy="23"
          r={r}
          fill="none"
          stroke={color}
          strokeWidth="4"
          strokeLinecap="round"
          strokeDasharray={c}
          strokeDashoffset={offset}
          transform="rotate(-90 23 23)"
        />
      </svg>
      <span
        className="data absolute inset-0 flex items-center justify-center text-[10.5px] font-semibold"
        style={{ color }}
      >
        {Math.round(pct)}%
      </span>
    </div>
  );
}

function Row({ children }: { children: React.ReactNode }) {
  return <div className="flex items-start gap-3 border-t border-border px-4 py-4 first:border-t-0">{children}</div>;
}

type RowKey = "impact" | "tracked" | "worth" | "grant";

// Career-stage weighting (onboarding's fixed STATUS_OPTIONS list, click-only —
// see app/onboarding/page.tsx). A PhD student's sharpest need is peer/
// collaborator awareness (no lab of their own yet); a postdoc's is the
// opportunity radar (grants/jobs, the fork every postdoc faces); faculty
// already have a network and funding rhythm, so their own body of work's
// impact leads instead. Unlisted/empty status keeps the original order.
const PEER_FOCUSED = new Set(["PhD Student", "Independent Researcher"]);
const OPPORTUNITY_FOCUSED = new Set(["Postdoc", "Research Scientist", "Industry Researcher"]);
const IMPACT_FOCUSED = new Set(["Assistant Professor", "Professor", "Lecturer"]);

const DEFAULT_ORDER: RowKey[] = ["impact", "tracked", "worth", "grant"];
const PEER_ORDER: RowKey[] = ["worth", "tracked", "impact", "grant"];
const OPPORTUNITY_ORDER: RowKey[] = ["grant", "impact", "tracked", "worth"];
const IMPACT_ORDER: RowKey[] = ["impact", "tracked", "grant", "worth"];

function rowOrderFor(careerStage: string | undefined): RowKey[] {
  if (careerStage && PEER_FOCUSED.has(careerStage)) return PEER_ORDER;
  if (careerStage && OPPORTUNITY_FOCUSED.has(careerStage)) return OPPORTUNITY_ORDER;
  if (careerStage && IMPACT_FOCUSED.has(careerStage)) return IMPACT_ORDER;
  return DEFAULT_ORDER;
}

function ActionLink({
  href,
  external,
  icon,
  label,
  tone = "quiet",
}: {
  href: string;
  external?: boolean;
  icon: React.ReactNode;
  label: string;
  /** "quiet" = outlined/neutral (Open, Save); "invite" = filled primary — the
   *  one action asking the viewer to add something new (Track). */
  tone?: "quiet" | "invite";
}) {
  const cls = cn(
    "inline-flex shrink-0 items-center gap-1.5 rounded-md font-body text-[11px] font-semibold transition-colors",
    tone === "invite"
      ? "bg-primary px-3 py-1.5 text-text-on-primary hover:bg-primary-dark"
      : "border border-border px-2.5 py-1.5 text-text-secondary hover:border-primary/40 hover:text-primary",
    focusRing,
  );
  return external ? (
    <a href={href} target="_blank" rel="noopener noreferrer" className={cls}>
      {label}
      {icon}
    </a>
  ) : (
    <Link href={href} className={cls}>
      {label}
      {icon}
    </Link>
  );
}

export function CoachPulseCard({
  impact,
  trackedActivity,
  worthTracking,
  topGrant,
  careerStage,
  loading,
  onTrack,
}: {
  impact: CoachPulseImpact | null | undefined;
  trackedActivity: CoachPulseTrackedActivity | null | undefined;
  worthTracking: CoachPulseWorthTracking | null | undefined;
  topGrant: GrantMatch | undefined;
  /** The user's onboarding `academicStatus` — weights which signal leads. */
  careerStage: string | undefined;
  loading: boolean;
  /** Fires the existing Track mutation (decisions/0021) — this card never
   *  owns tracked-state itself. */
  onTrack: (authorId: string, name: string) => void;
}) {
  const headingId = useId();
  const hasAny = Boolean(impact || trackedActivity || worthTracking || topGrant);

  return (
    <section aria-labelledby={headingId} className="overflow-hidden rounded-lg border border-border bg-surface shadow-card">
      <div className="flex items-center gap-2 bg-primary px-4 py-3">
        <Activity size={15} className="text-text-on-primary" aria-hidden="true" />
        <h2 id={headingId} className="font-mono text-[11px] font-semibold uppercase tracking-wide text-text-on-primary">
          Since You Were Here
        </h2>
        {hasAny && !loading && (
          <span
            aria-hidden="true"
            className="ml-auto h-1.5 w-1.5 rounded-full bg-accent-live"
            style={{ boxShadow: "0 0 0 3px color-mix(in srgb, var(--accent-live) 35%, transparent)" }}
          />
        )}
      </div>

      {loading ? (
        <div className="flex flex-col gap-3 p-4">
          {[0, 1, 2].map((i) => (
            <div key={i} className="h-[78px] animate-pulse rounded-md bg-surface-subtle" />
          ))}
        </div>
      ) : !hasAny ? (
        <div className="flex flex-col items-center gap-3 px-5 py-7 text-center">
          <span className="flex h-[52px] w-[52px] items-center justify-center rounded-full bg-accent-emerald/14">
            <Check size={20} className="text-accent-emerald" aria-hidden="true" />
          </span>
          <p className="font-display text-[14.5px] font-semibold text-text-primary">You&rsquo;re caught up.</p>
          <p className="max-w-[200px] font-body text-[11.5px] leading-relaxed text-text-secondary">
            Nothing new since your last visit. This refreshes as your tracked researchers publish, your papers pick
            up citations, or a new match appears.
          </p>
        </div>
      ) : (
        <>
          {rowOrderFor(careerStage).map((key) => {
            if (key === "impact" && impact) {
              return (
                <Row key={key}>
                  <div className="flex w-[46px] shrink-0 flex-col items-center pt-px">
                    <span className="data text-[28px] font-semibold leading-none text-accent-emerald">
                      {impact.new_citations}
                    </span>
                    <span className="mt-1 text-center font-mono text-[8.5px] font-semibold uppercase tracking-wide text-text-muted">
                      new {impact.new_citations === 1 ? "cite" : "cites"}
                    </span>
                  </div>
                  <div className="flex min-w-0 flex-1 flex-col gap-1.5">
                    <p className="font-display text-[13.5px] font-semibold leading-snug text-text-primary">
                      New citations on your paper
                    </p>
                    <p className="line-clamp-1 font-body text-[11.5px] leading-relaxed text-text-secondary">
                      &ldquo;{impact.paper_title}&rdquo;
                    </p>
                    <div className="mt-0.5 flex justify-end">
                      <ActionLink
                        href={`/paper/${encodeURIComponent(shortOpenAlexId(impact.paper_id))}`}
                        icon={<ArrowUpRight size={11} />}
                        label="Open"
                      />
                    </div>
                  </div>
                </Row>
              );
            }
            if (key === "tracked" && trackedActivity) {
              return (
                <Row key={key}>
                  <span
                    aria-hidden="true"
                    className="data flex h-[34px] w-[34px] shrink-0 items-center justify-center rounded-full bg-accent-violet text-[11px] font-semibold text-white"
                  >
                    {initials(trackedActivity.author_name)}
                  </span>
                  <div className="flex min-w-0 flex-1 flex-col gap-1.5">
                    <p className="font-display text-[13.5px] font-semibold leading-snug text-text-primary">
                      {trackedActivity.author_name} published new work
                    </p>
                    <p className="line-clamp-1 font-body text-[11.5px] italic leading-relaxed text-text-secondary">
                      &ldquo;{trackedActivity.work_title}&rdquo;
                    </p>
                    <div className="mt-0.5 flex items-center justify-between">
                      <span className="font-mono text-[10px] uppercase tracking-wide text-text-muted">
                        tracked · {relTime(trackedActivity.published_at)}
                      </span>
                      <ActionLink
                        href={`/paper/${encodeURIComponent(shortOpenAlexId(trackedActivity.work_id))}`}
                        icon={<ArrowUpRight size={11} />}
                        label="Open"
                      />
                    </div>
                  </div>
                </Row>
              );
            }
            if (key === "worth" && worthTracking) {
              return (
                <Row key={key}>
                  <span
                    aria-hidden="true"
                    className="data flex h-[34px] w-[34px] shrink-0 items-center justify-center rounded-full border-[1.5px] border-dashed border-border-strong text-[11px] font-semibold text-text-secondary"
                  >
                    {initials(worthTracking.author_name)}
                  </span>
                  <div className="flex min-w-0 flex-1 flex-col gap-1.5">
                    <p className="font-display text-[13.5px] font-semibold leading-snug text-text-primary">
                      {worthTracking.author_name}
                    </p>
                    <p className="line-clamp-1 font-body text-[11.5px] leading-relaxed text-text-secondary">
                      {worthTracking.institution || "Close to your field"}
                      {worthTracking.works_count > 0 && ` · ${worthTracking.works_count} works`}
                    </p>
                    <div className="mt-0.5 flex justify-end">
                      <button
                        type="button"
                        onClick={() => onTrack(worthTracking.author_id, worthTracking.author_name)}
                        className={cn(
                          "inline-flex shrink-0 items-center gap-1.5 rounded-md bg-primary px-3 py-1.5 font-body text-[11px] font-semibold text-text-on-primary transition-colors hover:bg-primary-dark",
                          focusRing,
                        )}
                      >
                        <UserPlus size={11} />
                        Track
                      </button>
                    </div>
                  </div>
                </Row>
              );
            }
            if (key === "grant" && topGrant) {
              return (
                <Row key={key}>
                  <FitRing pct={topGrant.match_score} color="var(--accent-orange)" />
                  <div className="flex min-w-0 flex-1 flex-col gap-1.5">
                    <p className="font-display text-[13.5px] font-semibold leading-snug text-text-primary">
                      {topGrant.title}
                    </p>
                    <p className="line-clamp-1 font-body text-[11.5px] leading-relaxed text-text-secondary">
                      {topGrant.agency}
                      {topGrant.amount && ` · ${topGrant.amount}`}
                    </p>
                    <div className="mt-0.5 flex items-center justify-between">
                      <span className="font-mono text-[10px] font-semibold uppercase tracking-wide text-accent-orange">
                        {topGrant.days_left != null ? `closes in ${topGrant.days_left}d` : "rolling"}
                      </span>
                      <ActionLink href={topGrant.url} external icon={<Bookmark size={11} />} label="Save" />
                    </div>
                  </div>
                </Row>
              );
            }
            return null;
          })}

          <div className="border-t border-border px-4 py-2.5 text-center">
            <span className="font-mono text-[10px] text-text-muted">That&rsquo;s everything since your last visit</span>
          </div>
        </>
      )}
    </section>
  );
}
