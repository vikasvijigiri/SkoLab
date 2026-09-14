"use client";

import { motion } from "framer-motion";
import { TrendingUp } from "lucide-react";
import { Card } from "@/components/ui/Card";
import { TrackTopicButton } from "@/components/discovery/TrackTopicButton";
import { cn, focusRing } from "@/lib/utils";
import type { TrendingTopic } from "@/lib/types";

/**
 * One topic growing fastest in the viewer's field — recent-vs-prior-window
 * growth, never a raw or cumulative count (`decisions/0015`). Shows the two
 * real counts behind the percentage so the number is checkable, not just
 * asserted. Click-only: a `<button>`, not a text control — selecting it drills
 * into that topic's papers, mirroring `ResearcherCard`'s shell/stagger.
 *
 * The Track button (decision 0021's topic half) sits absolutely positioned
 * over the card rather than beside the select button — a `<button>` can't
 * nest another `<button>`, and this card (unlike `ResearcherCard`, which
 * wraps its clickable area in a `<Link>`) uses a real `<button>` for select.
 */
export function TrendingTopicCard({
  t,
  index,
  windowDays,
  onSelect,
}: {
  t: TrendingTopic;
  index: number;
  windowDays: number;
  onSelect: (topic: TrendingTopic) => void;
}) {
  return (
    <motion.div
      initial={{ opacity: 0, y: 8 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ duration: 0.3, delay: Math.min(index * 0.05, 0.3) }}
      className="relative h-full"
    >
      <button
        type="button"
        onClick={() => onSelect(t)}
        className={cn("block h-full w-full rounded-sm text-left", focusRing)}
      >
        <Card
          glow
          interactive
          accentSide="left"
          accentColor="var(--accent-orange)"
          className="flex h-full flex-col gap-2"
        >
          <div className="flex items-center gap-2">
            <span className="flex h-7 w-7 shrink-0 items-center justify-center rounded-md bg-accent-orange/15 text-accent-orange">
              <TrendingUp size={14} />
            </span>
            <span className="font-mono text-[13px] font-semibold tabular-nums text-accent-orange">
              +{Math.round(t.growth * 100)}%
            </span>
          </div>
          <p className="font-display text-h3 font-semibold text-text-primary">{t.displayName}</p>
          <p className="font-body text-[11.5px] leading-relaxed text-text-muted">
            {t.recentCount} papers in the last {windowDays} days · {t.priorCount} the {windowDays}{" "}
            days before
          </p>
        </Card>
      </button>
      <TrackTopicButton
        topicId={t.id}
        name={t.displayName}
        iconOnly
        className="absolute right-4 top-4 bg-surface"
      />
    </motion.div>
  );
}
