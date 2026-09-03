"use client";

import Link from "next/link";
import { motion } from "framer-motion";
import { useQueryClient } from "@tanstack/react-query";
import { Card } from "@/components/ui/Card";
import { Badge } from "@/components/ui/Badge";
import { prefetchAuthor } from "@/lib/api/prefetch";
import { cn, focusRing } from "@/lib/utils";
import { DURATION_NORMAL, EASE_STANDARD } from "@/lib/motion";
import type { LeaderboardEntry } from "@/lib/types";

const MEDAL: Record<number, string> = {
  1: "linear-gradient(135deg, #fbbf24, #d97706)",
  2: "linear-gradient(135deg, #e2e8f0, #94a3b8)",
  3: "linear-gradient(135deg, #f0a878, #c2703d)",
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
        href={`/author/${encodeURIComponent(entry.id)}?name=${encodeURIComponent(entry.user_name)}`}
        onMouseEnter={warm}
        onFocus={warm}
        className={cn("block", focusRing)}
      >
        <Card glow interactive className="flex h-full items-center gap-3">
          <div className="relative shrink-0">
            <div
              className="flex h-11 w-11 items-center justify-center rounded-full font-display text-[14px] font-bold text-white"
              style={{ background: medal ?? "var(--primary)" }}
            >
              {entry.user_name.slice(0, 1).toUpperCase()}
            </div>
            <div
              className="absolute -bottom-1 -right-1 flex h-5 w-5 items-center justify-center rounded-full border-2 border-surface font-mono text-[10px] font-bold text-white"
              style={{ background: medal ?? "var(--text-muted)" }}
            >
              {entry.rank}
            </div>
          </div>
          <div className="min-w-0 flex-1">
            <p className="truncate font-body text-[14px] font-semibold text-text-primary">{entry.user_name}</p>
            <p className="truncate font-body text-[12.5px] text-text-secondary">{entry.institution}</p>
          </div>
          <Badge accentColor="var(--primary)">{entry.entropy_score} pts</Badge>
        </Card>
      </Link>
    </motion.div>
  );
}
