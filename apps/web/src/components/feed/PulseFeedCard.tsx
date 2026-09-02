"use client";

import { useState } from "react";
import Link from "next/link";
import { motion } from "framer-motion";
import { useQueryClient } from "@tanstack/react-query";
import { ChevronDown, X } from "lucide-react";
import { Card } from "@/components/ui/Card";
import { Badge } from "@/components/ui/Badge";
import { MathText } from "@/components/ui/MathText";
import { prefetchPaper } from "@/lib/api/prefetch";
import type { DailyFeedItem } from "@/lib/types";

function authorLabel(authorPair: string) {
  return authorPair.split("|")[0];
}

export function PulseFeedCard({
  item,
  onDismiss,
}: {
  item: DailyFeedItem;
  /** Present only when the feed can be personalized (a real author is resolved). */
  onDismiss?: () => void;
}) {
  const [expanded, setExpanded] = useState(false);
  const qc = useQueryClient();
  const hasDetails = item.methodology || item.tools_used?.length || item.key_findings;
  const warm = () => prefetchPaper(qc, item.id);

  return (
    <Card interactive={false} className="flex flex-col gap-2.5">
      <div className="flex items-start justify-between gap-3">
        <Badge accentColor="var(--primary)">{item.relevance_score}% MATCH</Badge>
        <div className="flex items-center gap-2">
          <span className="font-mono text-[11px] text-text-muted">{item.year}</span>
          {onDismiss && (
            <button
              onClick={onDismiss}
              aria-label="Not interested — remove from feed"
              title="Not interested — remove from feed"
              className="cursor-pointer rounded-full p-0.5 text-text-muted transition-colors duration-[var(--motion-fast)] hover:bg-surface-subtle hover:text-text-primary"
            >
              <X size={13} />
            </button>
          )}
        </div>
      </div>

      <Link
        href={`/paper/${encodeURIComponent(item.id)}`}
        className="group"
        onMouseEnter={warm}
        onFocus={warm}
      >
        <h3 className="font-display text-[15px] font-semibold leading-snug text-text-primary group-hover:text-primary">
          <MathText text={item.title} />
        </h3>
      </Link>

      <p className="font-body text-[12.5px] text-text-secondary">
        {item.authors.slice(0, 3).map(authorLabel).join(", ")}
        {item.authors.length > 3 && ` +${item.authors.length - 3}`} &middot; {item.journal}
      </p>

      <div className="rounded-[8px] bg-surface-subtle px-3 py-2.5">
        <p className="font-mono text-[10px] font-semibold uppercase tracking-wide text-accent-violet">
          Skolar Insight Brief
        </p>
        <p className="mt-1 font-body text-[13px] leading-relaxed text-text-primary">
          <MathText text={item.recommendation_reason} />
        </p>
      </div>

      {item.abstract && (
        <p className="font-body text-[13px] leading-relaxed text-text-secondary line-clamp-3">
          <MathText text={item.abstract} />
        </p>
      )}

      {hasDetails && (
        <motion.button
          onClick={() => setExpanded((v) => !v)}
          whileHover={{ x: 2 }}
          whileTap={{ scale: 0.96 }}
          className="flex cursor-pointer items-center gap-1 self-start font-body text-[12.5px] font-medium text-primary"
        >
          {expanded ? "Hide details" : "Show methodology & tools"}
          <motion.span animate={{ rotate: expanded ? 180 : 0 }} transition={{ duration: 0.2 }}>
            <ChevronDown size={14} />
          </motion.span>
        </motion.button>
      )}

      {expanded && (
        <div className="flex flex-col gap-2 border-t border-border pt-2.5">
          {item.methodology && (
            <p className="font-body text-[12.5px] text-text-secondary">
              <span className="font-medium text-text-primary">Methodology: </span>
              <MathText text={item.methodology} />
            </p>
          )}
          {item.tools_used && item.tools_used.length > 0 && (
            <div className="flex flex-wrap gap-1.5">
              {item.tools_used.map((tool) => (
                <Badge key={tool} accentColor="var(--accent-teal)">
                  {tool}
                </Badge>
              ))}
            </div>
          )}
          {item.key_findings && (
            <p className="font-body text-[12.5px] text-text-secondary">
              <span className="font-medium text-text-primary">Key findings: </span>
              <MathText text={item.key_findings} />
            </p>
          )}
        </div>
      )}
    </Card>
  );
}
