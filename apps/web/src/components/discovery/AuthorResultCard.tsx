"use client";

import Link from "next/link";
import { motion } from "framer-motion";
import { useQueryClient } from "@tanstack/react-query";
import { Card } from "@/components/ui/Card";
import { Badge } from "@/components/ui/Badge";
import { prefetchAuthor } from "@/lib/api/prefetch";
import { cn, focusRing } from "@/lib/utils";
import { DURATION_NORMAL, EASE_STANDARD } from "@/lib/motion";
import type { AuthorSuggestion } from "@/lib/types";

export function AuthorResultCard({ a, index }: { a: AuthorSuggestion; index: number }) {
  const qc = useQueryClient();
  const warm = () =>
    prefetchAuthor(qc, { id: a.id, name: a.display_name, focus: a.field_of_study ?? undefined });
  return (
    <motion.div
      initial={{ opacity: 0, y: 12 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ duration: DURATION_NORMAL, delay: Math.min(index * 0.05, 0.3), ease: EASE_STANDARD }}
    >
      <Link
        href={`/author/${encodeURIComponent(a.id)}?name=${encodeURIComponent(a.display_name)}&focus=${encodeURIComponent(a.field_of_study ?? "")}`}
        onMouseEnter={warm}
        onFocus={warm}
        className={cn("block", focusRing)}
      >
        <Card glow interactive className="flex h-full items-center gap-3">
          <div className="flex h-11 w-11 shrink-0 items-center justify-center rounded-full bg-primary font-display text-[14px] font-bold text-text-on-primary">
            {a.display_name.slice(0, 1).toUpperCase()}
          </div>
          <div className="min-w-0 flex-1">
            <p className="truncate font-body text-[14px] font-semibold text-text-primary">{a.display_name}</p>
            <p className="truncate font-body text-[12.5px] text-text-secondary">
              {a.institution}
              {a.field_of_study ? ` · ${a.field_of_study}` : ""}
            </p>
          </div>
          <div className="flex shrink-0 flex-col items-end gap-1">
            {a.h_index !== undefined && <Badge accentColor="var(--primary)">H-{a.h_index}</Badge>}
          </div>
        </Card>
      </Link>
    </motion.div>
  );
}
