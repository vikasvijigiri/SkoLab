"use client";

import Link from "next/link";
import { motion } from "framer-motion";
import { useQueryClient } from "@tanstack/react-query";
import { Card } from "@/components/ui/Card";
import { Badge } from "@/components/ui/Badge";
import { MathText } from "@/components/ui/MathText";
import { prefetchPaper } from "@/lib/api/prefetch";
import { cn, focusRing, shortOpenAlexId } from "@/lib/utils";
import { DURATION_NORMAL, EASE_STANDARD } from "@/lib/motion";
import type { OpenAlexWork } from "@/lib/types";

export function PaperResultCard({ w, index }: { w: OpenAlexWork; index: number }) {
  const shortId = shortOpenAlexId(w.id);
  const qc = useQueryClient();
  const warm = () => prefetchPaper(qc, shortId);
  return (
    <motion.div
      initial={{ opacity: 0, y: 12 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ duration: DURATION_NORMAL, delay: Math.min(index * 0.05, 0.3), ease: EASE_STANDARD }}
    >
      <Link
        href={`/paper/${encodeURIComponent(shortId)}`}
        onMouseEnter={warm}
        onFocus={warm}
        className={cn("block rounded-lg", focusRing)}
      >
        <Card glow interactive accentColor="var(--accent-cyan)" className="flex h-full flex-col gap-1.5">
          <p className="font-display text-[14.5px] font-semibold leading-snug text-text-primary">
            <MathText text={w.display_name} />
          </p>
          <p className="font-body text-[12.5px] text-text-secondary">
            {w.authorships?.slice(0, 3).map((a) => a.author.display_name).join(", ")}
            {w.publication_year ? ` · ${w.publication_year}` : ""}
            {w.primary_location?.source?.display_name ? ` · ${w.primary_location.source.display_name}` : ""}
          </p>
          <div className="mt-auto flex flex-wrap items-center gap-1.5 pt-1">
            {w.cited_by_count !== undefined && (
              <Badge accentColor="var(--accent-cyan)">{w.cited_by_count.toLocaleString()} citations</Badge>
            )}
            {w.open_access?.is_oa && (
              <span className="inline-flex items-center gap-1 font-mono text-[10.5px] font-medium uppercase tracking-wide text-accent-emerald">
                <span className="h-1.5 w-1.5 rounded-full bg-accent-emerald" aria-hidden="true" />
                Open Access
              </span>
            )}
            {(w.topics ?? []).slice(0, 2).map(
              (t) =>
                t.display_name && (
                  <Badge key={t.id ?? t.display_name} accentColor="var(--accent-indigo)">
                    {t.display_name}
                  </Badge>
                ),
            )}
          </div>
        </Card>
      </Link>
    </motion.div>
  );
}
