"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { motion } from "framer-motion";
import { ArrowDownRight, ArrowRight, ArrowUpRight, BadgeCheck, FolderPlus } from "lucide-react";
import { Card } from "@/components/ui/Card";
import { Badge } from "@/components/ui/Badge";
import { cn, focusRing, shortOpenAlexId } from "@/lib/utils";
import { DURATION_NORMAL, EASE_STANDARD } from "@/lib/motion";
import { useAuth } from "@/lib/hooks/AuthProvider";
import type { CareerStage, MomentumState, ResearcherResult } from "@/lib/types";
import { StatusDot } from "./StatusDot";
import { MomentumSparkline } from "./MomentumSparkline";
import { ActiveDecadesStrip } from "./ActiveDecadesStrip";

const ACCENT_BY_ACTIVITY = {
  active: "var(--accent-live)",
  winding_down: "var(--warning)",
  dormant: "var(--text-muted)",
} as const;

const CAREER_LABEL: Record<CareerStage, string> = {
  emerging: "Emerging",
  established: "Established",
  senior: "Senior",
};

const MOMENTUM: Record<MomentumState, { icon: typeof ArrowRight; color: string; label: string }> = {
  rising: { icon: ArrowUpRight, color: "var(--accent-live)", label: "Rising" },
  steady: { icon: ArrowRight, color: "var(--text-muted)", label: "Steady" },
  cooling: { icon: ArrowDownRight, color: "var(--accent-orange)", label: "Cooling" },
};

function MetaRow({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className="flex items-center justify-between gap-2 font-body text-[11px]">
      <span className="text-text-muted">{label}</span>
      <span className="flex items-center gap-1.5 text-text-secondary">{children}</span>
    </div>
  );
}

export function ResearcherCard({ r, index }: { r: ResearcherResult; index: number }) {
  const { signals } = r;
  const { user } = useAuth();
  const router = useRouter();
  const accent = r.deceased ? "var(--text-muted)" : ACCENT_BY_ACTIVITY[signals.activity];
  const mo = MOMENTUM[signals.momentum];
  const MoIcon = mo.icon;

  const standing =
    signals.standingPercentile != null
      ? `top ${Math.max(1, 100 - signals.standingPercentile)}%`
      : `H-${r.hIndex}`;
  const focusPct = Math.round(signals.topicalFocus * 100);

  function startProject(e: React.MouseEvent) {
    e.preventDefault();
    const params = new URLSearchParams({ withResearcher: r.id, withResearcherName: r.display_name });
    router.push(`/workspace?${params.toString()}`);
  }

  return (
    <motion.div
      initial={{ opacity: 0, y: 12 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ duration: DURATION_NORMAL, delay: Math.min(index * 0.04, 0.24), ease: EASE_STANDARD }}
    >
      <Card glow accentColor={accent} accentSide="left" className="flex h-full flex-col gap-2.5">
        <Link
          href={`/author/${encodeURIComponent(shortOpenAlexId(r.id))}?name=${encodeURIComponent(r.display_name)}`}
          className={cn("flex flex-1 flex-col gap-2.5 rounded-md", focusRing)}
        >
          {/* identity */}
          <div className="flex items-start gap-2.5">
            <div className="flex h-9 w-9 shrink-0 items-center justify-center rounded-full bg-surface-subtle font-display text-[13px] font-bold text-text-secondary">
              {r.display_name.slice(0, 1).toUpperCase()}
            </div>
            <div className="min-w-0 flex-1">
              <div className="flex items-center gap-1.5">
                <p className="truncate font-body text-body-s font-semibold text-text-primary">
                  {r.display_name}
                </p>
                {r.orcid && (
                  <BadgeCheck
                    size={13}
                    className="shrink-0 text-primary"
                    aria-label="ORCID-verified identity"
                  />
                )}
              </div>
              <p className="truncate font-body text-[11px] text-text-secondary">
                {r.institution || "Unknown institution"}
                {r.country ? ` · ${r.country}` : ""}
              </p>
            </div>
            <div className="shrink-0 pt-0.5">
              <StatusDot activity={signals.activity} deceased={r.deceased} />
            </div>
          </div>

          {/* fit + momentum */}
          <div className="flex items-center gap-2">
            {r.fit && (
              <Badge accentColor="var(--primary)" title={r.fit.why || undefined}>
                Fit {r.fit.score}
              </Badge>
            )}
            <span
              className="inline-flex items-center gap-0.5 font-mono text-[10px] uppercase tracking-wide"
              style={{ color: mo.color }}
            >
              <MoIcon size={12} /> {mo.label}
            </span>
            <span className="ml-auto">
              <MomentumSparkline values={signals.sparkline} momentum={signals.momentum} />
            </span>
          </div>

          {/* signals */}
          <div className="flex flex-col gap-1 border-t border-border/60 pt-2">
            <MetaRow label="Works on this field">
              <span className="relative h-1.5 w-16 overflow-hidden rounded-full bg-surface-subtle">
                <span
                  className="absolute inset-y-0 left-0 rounded-full"
                  style={{ width: `${focusPct}%`, backgroundColor: "var(--primary)" }}
                />
              </span>
              {focusPct}%
            </MetaRow>
            <MetaRow label="Field standing">
              <span title={`h-index ${r.hIndex}`}>{standing}</span>
            </MetaRow>
            <MetaRow label="Career">
              {CAREER_LABEL[signals.careerStage]} · ~{signals.yearsActiveVisible} yrs
            </MetaRow>
            {signals.activeDecades.length > 0 && (
              <MetaRow label="Active">
                <ActiveDecadesStrip decades={signals.activeDecades} />
              </MetaRow>
            )}
          </div>

          {r.fit?.why && (
            <p className="line-clamp-2 font-body text-[11px] leading-snug text-text-muted">
              Why you: {r.fit.why}
            </p>
          )}

          {r.openToCollaboration && (
            <Badge accentColor="var(--accent-live)" className="self-start">
              Open to collaboration
            </Badge>
          )}
        </Link>

        {user && !r.deceased && (
          <button
            type="button"
            onClick={startProject}
            className={cn(
              "mt-1 flex items-center justify-center gap-1.5 rounded-md border border-border py-1.5 font-body text-[11.5px] font-medium text-text-secondary transition-colors hover:border-primary/40 hover:text-primary",
              focusRing
            )}
          >
            <FolderPlus size={13} />
            Start a project with {r.display_name.split(" ")[0]}
          </button>
        )}
      </Card>
    </motion.div>
  );
}
