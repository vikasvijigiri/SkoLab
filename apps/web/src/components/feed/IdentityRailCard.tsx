"use client";

import Link from "next/link";
import { Card } from "@/components/ui/Card";
import { AnimatedCounter } from "@/components/ui/AnimatedCounter";
import type { AuthorResponse } from "@/lib/types";

function Stat({ label, value, accent }: { label: string; value: number; accent: string }) {
  return (
    <div className="flex-1 text-center">
      <p className="font-mono text-[15px] font-medium tabular-nums" style={{ color: accent }}>
        <AnimatedCounter to={value} />
      </p>
      <p className="mt-0.5 font-body text-[9.5px] font-medium uppercase tracking-wide text-text-muted">
        {label}
      </p>
    </div>
  );
}

/**
 * Left-rail identity widget — the LinkedIn "who you are" card. Absorbs the stat
 * trio the old FrontierPulseCard showed in the main column (Disruption / Skill
 * Index / Works), where it was static content eating the prime slot.
 */
export function IdentityRailCard({
  name,
  status,
  author,
  loading,
  unresolved,
}: {
  name: string;
  status?: string;
  author: AuthorResponse | null;
  loading: boolean;
  /** No OpenAlex match yet — show a connect-your-work prompt instead of zeros. */
  unresolved?: boolean;
}) {
  if (loading) {
    return (
      <Card className="animate-pulse">
        <div className="h-24 rounded-[8px] bg-surface-subtle" />
      </Card>
    );
  }

  return (
    <Card className="flex flex-col gap-3">
      <div className="flex items-center gap-2.5">
        <span
          className="flex h-10 w-10 shrink-0 items-center justify-center rounded-full font-display text-[15px] font-bold text-white shadow-card"
          style={{ background: "var(--primary)" }}
        >
          {(name.trim()[0] ?? "?").toUpperCase()}
        </span>
        <div className="min-w-0">
          <p className="truncate font-display text-[14px] font-semibold text-text-primary">{name}</p>
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
    </Card>
  );
}
