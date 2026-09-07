"use client";

import Link from "next/link";
import { motion } from "framer-motion";
import { useQueryClient } from "@tanstack/react-query";
import { Card } from "@/components/ui/Card";
import { Badge } from "@/components/ui/Badge";
import { prefetchAuthor } from "@/lib/api/prefetch";
import { cn, focusRing, shortOpenAlexId } from "@/lib/utils";
import { DURATION_NORMAL, EASE_STANDARD } from "@/lib/motion";
import type { LeaderboardEntry } from "@/lib/types";

const MEDAL: Record<number, string> = {
  1: "#c08a2e", // gold
  2: "#8b93a1", // silver
  3: "#b06a3c", // bronze
};

export function LeaderboardRow({ entry, index }: { entry: LeaderboardEntry; index: number }) {
  const medal = MEDAL[entry.rank];
  const qc = useQueryClient();
  const warm = () => prefetchAuthor(qc, { id: entry.id, name: entry.user_name });
  return (
    <motion.div
      initial={{ opacity: 0, y: 12 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ duration: DURATION_NORMAL, delay: Math.min(index * 0.05, 0.3), ease: EASE_STANDARD }}
    >
      {/* Deep-links straight to the author by id — a name-based search here can
          resolve to the wrong same-initial person when names collide. */}
      <Link
        href={`/author/${encodeURIComponent(shortOpenAlexId(entry.id))}?name=${encodeURIComponent(entry.user_name)}`}
        onMouseEnter={warm}
        onFocus={warm}
        className={cn("block rounded-md", focusRing)}
      >
        <Card glow interactive accentColor={medal ?? "var(--primary)"} accentSide="left" className="flex h-full items-center gap-3">
          <div className="relative shrink-0">
            <div
              className="flex h-11 w-11 items-center justify-center rounded-full font-display text-[14px] font-bold text-text-on-primary"
              style={{ background: medal ?? "var(--primary)" }}
            >
              {entry.user_name.slice(0, 1).toUpperCase()}
            </div>
            <div
              className="data absolute -bottom-1 -right-1 flex h-5 w-5 items-center justify-center rounded-full border-2 border-surface text-[10px] font-bold text-text-on-primary"
              style={{ background: medal ?? "var(--text-muted)" }}
            >
              {entry.rank}
            </div>
          </div>
          <div className="min-w-0 flex-1">
            <p className="truncate font-body text-body font-semibold text-text-primary">{entry.user_name}</p>
            <p className="truncate font-body text-body-s text-text-secondary">{entry.institution}</p>
          </div>
          <Badge accentColor="var(--primary)">{entry.entropy_score} pts</Badge>
        </Card>
      </Link>
    </motion.div>
  );
}
