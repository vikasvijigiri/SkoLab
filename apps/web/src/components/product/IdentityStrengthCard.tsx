"use client";

import Link from "next/link";
import { ArrowRight, BadgeCheck } from "lucide-react";
import { Card } from "@/components/ui/Card";
import { AnimatedCounter } from "@/components/ui/AnimatedCounter";
import {
  computeProfileStrength,
  type ProfileStrengthStep,
} from "@/lib/profile/strength";
import type { AuthorResponse, SkoLabUser } from "@/lib/types";

/**
 * Home's "who you are on SkoLab" card — one merged identity + profile-strength
 * card at the top of the right rail. Was two separate cards (an identity card,
 * and a profile-strength meter below it further down the rail); one card reads
 * better as a single "this is you" unit.
 *
 * Every number here is real: Disruption/Skill/Works are SkoLab's own computed
 * scores, the ring is the share of profile fields actually filled in, and the
 * reach row is the author's own OpenAlex totals (Works is shown once, in the
 * identity trio, not repeated in the reach row). SkoLab does not track profile
 * views or search appearances, so this card does not invent them.
 */

/** Presentational copy per step — the pure module owns label + weight. */
const STEP_META: Record<ProfileStrengthStep["key"], { detail: string; href: string }> = {
  link: {
    detail: "Unlocks your citations, h-index and reach.",
    href: "/profile",
  },
  focus: {
    detail: "So your feed and collaborators match your field.",
    href: "/profile",
  },
  status: {
    detail: "PhD student, postdoc, professor — pick one.",
    href: "/profile",
  },
  about: {
    detail: "Two lines on what you work on and what you need.",
    href: "/profile",
  },
  name: { detail: "The name your work is published under.", href: "/profile" },
  areas: {
    detail: "Confirm the topics and methods pulled from your papers.",
    href: "/profile",
  },
};

const RING_SIZE = 72;
const RING_STROKE = 7;
const RING_RADIUS = (RING_SIZE - RING_STROKE) / 2;
const RING_CIRCUMFERENCE = 2 * Math.PI * RING_RADIUS;

/** How many "Do next" rows to show before deferring the rest to /profile. */
const MAX_ACTIONS = 3;

export function IdentityStrengthCard({
  name,
  status,
  firestoreProfile,
  author,
  unresolved = false,
  loading = false,
}: {
  name: string;
  status?: string;
  firestoreProfile: SkoLabUser | null;
  author: AuthorResponse | null;
  unresolved?: boolean;
  loading?: boolean;
}) {
  if (loading) {
    return (
      <Card accentColor="var(--primary)" className="animate-pulse">
        <div className="h-64 rounded-md bg-surface-subtle" />
      </Card>
    );
  }

  const resolved = Boolean(author) && !unresolved;
  const { percent, tier, remaining } = computeProfileStrength({
    name,
    hasLinkedAuthor: resolved || Boolean(firestoreProfile?.openAlexId?.trim()),
    researchFocus: firestoreProfile?.researchFocus,
    academicStatus: firestoreProfile?.academicStatus,
    about: firestoreProfile?.about,
    expertiseCount: author?.expertise?.length ?? 0,
    skillsCount: author?.skills?.length ?? 0,
  });

  const complete = remaining.length === 0;
  const shown = remaining.slice(0, MAX_ACTIONS);
  const hiddenCount = remaining.length - shown.length;
  const dashOffset = RING_CIRCUMFERENCE * (1 - percent / 100);

  return (
    <Card accentColor="var(--primary)" className="flex flex-col gap-4">
      {/* ── Identity ──────────────────────────────────────────────────── */}
      <div className="flex items-center gap-3">
        <span
          className="flex h-10 w-10 shrink-0 items-center justify-center rounded-full font-display text-h3 font-bold text-text-on-primary shadow-card"
          style={{ background: "var(--primary)" }}
        >
          {(name.trim()[0] ?? "?").toUpperCase()}
        </span>
        <div className="min-w-0">
          <p className="truncate font-display text-body font-semibold text-text-primary">{name}</p>
          {status && (
            <p className="truncate font-body text-[11.5px] text-text-secondary">{status}</p>
          )}
        </div>
      </div>

      {author && !unresolved ? (
        <div className="flex items-center gap-2 border-t border-border pt-3">
          <Stat label="Disruption" value={Math.round(author.disruption_score)} accent="var(--accent-orange)" />
          <Stat label="Skill" value={Math.round(author.average_skill_score)} accent="var(--primary)" />
          <Stat label="Works" value={author.works_count} accent="var(--accent-teal)" />
        </div>
      ) : (
        <p className="border-t border-border pt-3 font-body text-[11.5px] leading-relaxed text-text-secondary">
          <Link href="/profile" className="font-medium text-primary">
            Add your name or ORCID
          </Link>{" "}
          to unlock your impact metrics.
        </p>
      )}

      {/* ── Profile strength ──────────────────────────────────────────── */}
      <div className="flex items-center gap-4 border-t border-border/70 pt-4">
        <svg
          width={RING_SIZE}
          height={RING_SIZE}
          viewBox={`0 0 ${RING_SIZE} ${RING_SIZE}`}
          className="shrink-0"
          role="img"
          aria-label={`Profile strength ${percent} percent, ${tier}`}
        >
          <circle
            cx={RING_SIZE / 2}
            cy={RING_SIZE / 2}
            r={RING_RADIUS}
            fill="none"
            stroke="var(--border-color)"
            strokeWidth={RING_STROKE}
          />
          <circle
            cx={RING_SIZE / 2}
            cy={RING_SIZE / 2}
            r={RING_RADIUS}
            fill="none"
            stroke="var(--primary)"
            strokeWidth={RING_STROKE}
            strokeLinecap="round"
            strokeDasharray={RING_CIRCUMFERENCE}
            strokeDashoffset={dashOffset}
            transform={`rotate(-90 ${RING_SIZE / 2} ${RING_SIZE / 2})`}
          />
          <text
            x="50%"
            y="50%"
            textAnchor="middle"
            dominantBaseline="central"
            fill="var(--text-primary)"
            fontFamily="var(--font-mono)"
            fontSize="15"
            fontWeight="600"
          >
            {percent}%
          </text>
        </svg>

        <div className="min-w-0">
          <p className="eyebrow text-primary">Profile strength</p>
          <h2 className="mt-1 font-display text-h3 font-semibold text-text-primary">{tier}</h2>
          <p className="mt-0.5 font-body text-[12px] leading-relaxed text-text-muted">
            {complete
              ? "Collaborators see the full picture of your work."
              : "A complete profile is what turns a search result into a message."}
          </p>
        </div>
      </div>

      {complete ? (
        <div className="flex items-center gap-2 rounded-md border border-accent-emerald/20 bg-accent-emerald/5 px-3 py-2.5">
          <BadgeCheck size={15} className="shrink-0 text-accent-emerald" />
          <p className="font-body text-[12px] leading-relaxed text-text-secondary">
            Your profile is complete. Keep it current from{" "}
            <Link href="/profile" className="font-medium text-primary hover:underline">
              your profile
            </Link>
            .
          </p>
        </div>
      ) : (
        <div className="flex flex-col gap-1">
          <p className="eyebrow text-text-muted">Do next</p>
          <ul className="flex flex-col divide-y divide-border/60">
            {shown.map((step) => (
              <li key={step.key}>
                <Link
                  href={STEP_META[step.key].href}
                  className="group flex items-start gap-3 py-2.5"
                >
                  <ArrowRight
                    size={14}
                    className="mt-1 shrink-0 text-text-muted transition-transform group-hover:translate-x-0.5 group-hover:text-primary"
                  />
                  <span className="min-w-0 flex-1">
                    <span className="font-display text-body-s font-medium text-text-primary group-hover:text-primary">
                      {step.label}
                    </span>
                    <span className="mt-0.5 block font-body text-[11.5px] leading-relaxed text-text-muted">
                      {STEP_META[step.key].detail}
                    </span>
                  </span>
                  <span className="shrink-0 font-mono text-[11px] font-medium tabular-nums text-accent-emerald">
                    +{step.weight}%
                  </span>
                </Link>
              </li>
            ))}
          </ul>
          {hiddenCount > 0 && (
            <Link
              href="/profile"
              className="mt-1 font-body text-[12px] font-medium text-primary hover:underline"
            >
              {hiddenCount} more in your profile →
            </Link>
          )}
        </div>
      )}

      {/* Works already appears in the identity trio above, so the reach row
          only adds what isn't shown yet: citations and h-index. */}
      <div className="border-t border-border/70 pt-3">
        {resolved && author ? (
          <>
            <p className="eyebrow mb-2 text-text-muted">Your work on OpenAlex</p>
            <div className="flex items-center gap-2">
              <ReachStat label="Citations" value={author.cited_by_count} accent="var(--accent-teal)" />
              <ReachStat label="h-index" value={author.h_index} accent="var(--accent-orange)" />
            </div>
          </>
        ) : (
          <p className="font-body text-[12px] leading-relaxed text-text-secondary">
            <Link href="/profile" className="font-medium text-primary hover:underline">
              Link your published work
            </Link>{" "}
            to see how far it reaches.
          </p>
        )}
      </div>
    </Card>
  );
}

function Stat({ label, value, accent }: { label: string; value: number; accent: string }) {
  return (
    <div className="flex-1 text-center">
      <p className="font-mono text-[15px] font-medium tabular-nums" style={{ color: accent }}>
        <AnimatedCounter to={value} />
      </p>
      <p className="mt-1 font-body text-[11px] font-medium uppercase tracking-wide text-text-muted">
        {label}
      </p>
    </div>
  );
}

function ReachStat({ label, value, accent }: { label: string; value: number; accent: string }) {
  return (
    <div className="flex-1 rounded-md bg-surface-subtle px-3 py-2.5 text-center">
      <p className="font-mono text-[15px] font-semibold tabular-nums" style={{ color: accent }}>
        <AnimatedCounter to={value} />
      </p>
      <p className="mt-1 font-body text-[10.5px] uppercase tracking-wide text-text-muted">
        {label}
      </p>
    </div>
  );
}
