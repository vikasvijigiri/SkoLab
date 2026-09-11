"use client";

import Link from "next/link";
import { motion } from "framer-motion";
import { ArrowUpRight } from "lucide-react";
import { Card } from "@/components/ui/Card";
import { cn, focusRing, shortOpenAlexId } from "@/lib/utils";
import type { ResearcherResult } from "@/lib/types";

/**
 * Top rising researchers, ranked by momentum magnitude — the same signal the
 * fit-grid's `momentum` sort already uses, promoted into its own short
 * highlight strip per decisions/0015's "trending persons" follow-up. Renders
 * nothing when nobody in the current scope is rising (never pads with a
 * "steady" researcher to fill the row).
 */
export function TrendingResearchersStrip({
  researchers,
  scopeLabel,
}: {
  researchers: ResearcherResult[];
  scopeLabel: string;
}) {
  if (researchers.length === 0) return null;

  return (
    <div className="flex flex-col gap-2">
      <span className="eyebrow flex items-center gap-1.5 text-accent-orange">
        <ArrowUpRight size={12} />
        Rising in {scopeLabel}
      </span>
      <ul
        aria-label="Rising researchers"
        className="-mx-1 flex snap-x snap-mandatory gap-3 overflow-x-auto px-1 pb-1 [scrollbar-width:thin]"
      >
        {researchers.map((r, i) => (
          <motion.li
            key={r.id}
            initial={{ opacity: 0, y: 8 }}
            animate={{ opacity: 1, y: 0 }}
            transition={{ duration: 0.3, delay: Math.min(i * 0.05, 0.2) }}
            className="shrink-0 snap-start"
          >
            <Link
              href={`/author/${encodeURIComponent(shortOpenAlexId(r.id))}?name=${encodeURIComponent(r.display_name)}`}
              className={cn("block rounded-md", focusRing)}
            >
              <Card
                glow
                interactive
                accentColor="var(--accent-orange)"
                accentSide="left"
                className="flex h-full w-56 items-center gap-2.5"
              >
                <div className="flex h-9 w-9 shrink-0 items-center justify-center rounded-full bg-accent-orange/15 font-display text-[13px] font-bold text-accent-orange">
                  {r.display_name.slice(0, 1).toUpperCase()}
                </div>
                <div className="min-w-0 flex-1">
                  <p className="truncate font-body text-body-s font-semibold text-text-primary">
                    {r.display_name}
                  </p>
                  <p className="truncate font-body text-[11px] text-text-muted">
                    {r.institution || "Independent"}
                  </p>
                </div>
              </Card>
            </Link>
          </motion.li>
        ))}
      </ul>
    </div>
  );
}
