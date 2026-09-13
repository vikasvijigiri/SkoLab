"use client";

import { useQuery } from "@tanstack/react-query";
import { Users2 } from "lucide-react";
import { Modal } from "@/components/ui/Modal";
import { collaboratorsQuery } from "@/lib/api/queries";
import { fieldStandingLabel, citationVelocity } from "@/lib/discovery/format";
import { MomentumSparkline } from "./MomentumSparkline";
import type { NetworkCollaborator, ResearcherResult } from "@/lib/types";

/** Collaborators whose institution reads differently from the researcher's
 *  own — the same real signal `AuthorDetailContent`'s "Suggested Connections"
 *  section already surfaces, just counted here. `null` while still loading. */
function crossInstitutionCount(home: string, collaborators: NetworkCollaborator[] | undefined): number | null {
  if (!collaborators) return null;
  const homeNorm = home.trim().toLowerCase();
  return collaborators.filter((c) => c.institution && c.institution.trim().toLowerCase() !== homeNorm).length;
}

function Tile({ value, label, color }: { value: React.ReactNode; label: string; color?: string }) {
  return (
    <div className="rounded-md bg-surface p-2.5 text-center">
      <p className="data text-[15px] font-semibold" style={{ color: color ?? "var(--text-primary)" }}>
        {value}
      </p>
      <p className="mt-0.5 font-body text-[10px] text-text-muted">{label}</p>
    </div>
  );
}

function ResearcherColumn({ r }: { r: ResearcherResult }) {
  const collabQ = useQuery(collaboratorsQuery(r.id, r.topics[0], r.display_name));
  const crossInst = crossInstitutionCount(r.institution, collabQ.data);
  const velocity = citationVelocity(r.signals.sparkline);

  return (
    <div className="flex flex-1 flex-col gap-3 rounded-md border border-border bg-surface-subtle/40 p-4">
      <div className="flex items-center gap-2.5">
        <div className="flex h-9 w-9 shrink-0 items-center justify-center rounded-full bg-surface-subtle font-display text-[13px] font-bold text-text-secondary">
          {r.display_name.slice(0, 1).toUpperCase()}
        </div>
        <div className="min-w-0">
          <p className="truncate font-body text-body-s font-semibold text-text-primary">{r.display_name}</p>
          <p className="truncate font-body text-[11px] text-text-secondary">{r.institution || "Unknown institution"}</p>
        </div>
      </div>

      <div className="grid grid-cols-2 gap-1.5">
        <Tile value={fieldStandingLabel(r.hIndex, r.signals.standingPercentile)} label="Field standing" />
        <Tile
          value={velocity != null ? `${velocity}×` : "—"}
          label="Citation velocity"
          color={velocity != null ? "var(--accent-live)" : undefined}
        />
        <Tile value={collabQ.isPending ? "…" : (crossInst ?? "—")} label="Cross-inst. co-authors" />
        <Tile value={`${r.signals.yearsActiveVisible} yrs`} label="Years active" />
      </div>

      <div>
        <p className="mb-1.5 font-body text-[10.5px] text-text-muted">Citations per year</p>
        {r.signals.sparkline.length >= 2 ? (
          <MomentumSparkline values={r.signals.sparkline} momentum={r.signals.momentum} width={220} height={36} />
        ) : (
          <p className="font-body text-[11px] text-text-muted">Not enough history yet.</p>
        )}
      </div>
    </div>
  );
}

/**
 * Casual side-by-side compare (decision 0021) — deliberately lightweight,
 * not an enterprise author-compare tool. Reuses the two `ResearcherResult`
 * rows Discovery already fetched for its grid; only the cross-institution
 * co-author count needs a fresh request per researcher.
 */
export function CompareModal({
  a,
  b,
  open,
  onClose,
}: {
  a: ResearcherResult;
  b: ResearcherResult;
  open: boolean;
  onClose: () => void;
}) {
  return (
    <Modal open={open} onClose={onClose} title="Compare researchers" widthClass="max-w-2xl">
      <div className="flex flex-col gap-3">
        <div className="flex items-center gap-2 font-body text-[11px] text-text-muted">
          <Users2 size={13} />
          A casual side-by-side — not a scored ranking of who&apos;s &quot;better&quot;.
        </div>
        <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
          <ResearcherColumn r={a} />
          <ResearcherColumn r={b} />
        </div>
      </div>
    </Modal>
  );
}
