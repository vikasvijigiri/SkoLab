"use client";

import Link from "next/link";
import { motion } from "framer-motion";
import { useQueryClient } from "@tanstack/react-query";
import { Sparkles } from "lucide-react";
import { Card } from "@/components/ui/Card";
import { Badge } from "@/components/ui/Badge";
import { MathText } from "@/components/ui/MathText";
import { prefetchPaper } from "@/lib/api/prefetch";
import { cn, focusRing, shortOpenAlexId } from "@/lib/utils";
import { DURATION_NORMAL, EASE_STANDARD } from "@/lib/motion";
import { reconstructAbstract } from "@/lib/openalex-work";
import { jaccardSim } from "@/lib/discovery/fit";
import type { OpenAlexWork } from "@/lib/types";

/**
 * One real sourced sentence, not an LLM call per card — `analyze_paper` is an
 * LLM endpoint (Python = LLM-only, gated as a premium feature) and isn't
 * something to fan out to every card in a results grid. OpenAlex already
 * ships the abstract on these same list responses (`abstract_inverted_index`,
 * no extra request), so the TL;DR here is the paper's own opening sentence —
 * genuinely sourced, just not AI-synthesized. Honest when there's nothing to
 * show, too: many OpenAlex records simply have no abstract on file.
 */
function tldrFromAbstract(w: OpenAlexWork): string | null {
  const text = reconstructAbstract(w.abstract_inverted_index);
  if (!text) return null;
  const firstSentence = text.match(/^[^.!?]{20,320}[.!?]/)?.[0];
  const excerpt = firstSentence ?? text.slice(0, 220).trim();
  return excerpt.length < text.length && !firstSentence ? `${excerpt}…` : excerpt;
}

export function PaperResultCard({
  w,
  index,
  viewerExpertise = [],
  quieter = false,
}: {
  w: OpenAlexWork;
  index: number;
  /** The signed-in viewer's topic tags — same list `scoreFit` uses for
   *  researchers. Empty for a cold/unresolved viewer, in which case "Fits
   *  your field" is simply omitted rather than showing an invented number. */
  viewerExpertise?: string[];
  /** Visually quieter treatment for a lower-relevance result — still shown,
   *  never hidden (decision 0021). */
  quieter?: boolean;
}) {
  const shortId = shortOpenAlexId(w.id);
  const qc = useQueryClient();
  const warm = () => prefetchPaper(qc, shortId);

  const tldr = tldrFromAbstract(w);
  const topicNames = (w.topics ?? []).map((t) => t.display_name).filter((n): n is string => Boolean(n));
  const fitPct =
    viewerExpertise.length > 0 && topicNames.length > 0
      ? Math.round(jaccardSim(viewerExpertise, topicNames) * 100)
      : null;

  return (
    <motion.div
      initial={{ opacity: 0, y: 12 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ duration: DURATION_NORMAL, delay: Math.min(index * 0.05, 0.3), ease: EASE_STANDARD }}
      className={quieter ? "opacity-70" : undefined}
    >
      <Link
        href={`/paper/${encodeURIComponent(shortId)}`}
        onMouseEnter={warm}
        onFocus={warm}
        className={cn("block rounded-md", focusRing)}
      >
        <Card
          glow
          interactive
          accentColor={quieter ? "var(--border-strong)" : "var(--accent-cyan)"}
          accentSide="left"
          className="flex h-full flex-col gap-2"
        >
          <p
            className={cn(
              "font-display leading-snug text-text-primary",
              quieter ? "text-body-s font-medium" : "text-body font-semibold",
            )}
          >
            <MathText text={w.display_name} />
          </p>
          <p className="font-body text-body-s text-text-secondary">
            {w.authorships?.slice(0, 3).map((a) => a.author.display_name).join(", ")}
            {w.publication_year ? ` · ${w.publication_year}` : ""}
            {w.primary_location?.source?.display_name ? ` · ${w.primary_location.source.display_name}` : ""}
          </p>

          {tldr && (
            <div
              className={cn(
                "flex gap-2 rounded-sm px-2.5 py-2",
                quieter ? "bg-surface-subtle/60" : "border-l-2 border-primary bg-primary/5",
              )}
            >
              {!quieter && (
                <span className="shrink-0 pt-px font-mono text-[9.5px] font-bold uppercase tracking-wide text-primary">
                  TL;DR
                </span>
              )}
              <p className="font-body text-[12.5px] leading-relaxed text-text-primary">
                {quieter ? `TL;DR: ${tldr}` : tldr}
              </p>
            </div>
          )}

          <div className="mt-auto flex flex-wrap items-center gap-2 pt-1">
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
            {fitPct !== null && (
              <span className="ml-auto inline-flex items-center gap-1 font-body text-[11px] font-medium text-accent-teal">
                <Sparkles size={12} />
                Fits your field · {fitPct}%
              </span>
            )}
          </div>
        </Card>
      </Link>
    </motion.div>
  );
}
