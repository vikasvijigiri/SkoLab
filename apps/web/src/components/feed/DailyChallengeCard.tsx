"use client";

import { useState } from "react";
import Link from "next/link";
import { motion } from "framer-motion";
import { Card } from "@/components/ui/Card";
import { Badge } from "@/components/ui/Badge";
import { MathText } from "@/components/ui/MathText";
import { cn } from "@/lib/utils";
import type { Conjecture } from "@/lib/types";
import { TRANSITION_FAST } from "@/lib/motion";

export function DailyChallengeCard({
  conjecture,
  loading,
  unresolved,
}: {
  conjecture: Conjecture | null;
  loading: boolean;
  /** No resolved research profile yet — the challenge can't be personalized. */
  unresolved?: boolean;
}) {
  const [selected, setSelected] = useState<number | null>(null);

  if (loading) {
    return (
      <Card className="animate-pulse">
        <div className="h-24 rounded-[8px] bg-surface-subtle" />
      </Card>
    );
  }

  if (!conjecture) {
    return (
      <Card>
        <p className="font-body text-[13px] leading-relaxed text-text-secondary">
          {unresolved ? (
            <>
              Your daily challenge is drawn from your field.{" "}
              <Link href="/profile" className="font-medium text-primary">
                Set a research focus
              </Link>{" "}
              to start.
            </>
          ) : (
            "Today's challenge is still being prepared — check back shortly."
          )}
        </p>
      </Card>
    );
  }

  return (
    <Card accentColor="var(--accent-amber)">
      <Badge accentColor="var(--accent-amber)">{conjecture.category.toUpperCase()}</Badge>
      <h3 className="mt-2.5 font-display text-[15px] font-semibold text-text-primary">
        <MathText text={conjecture.title} />
      </h3>
      <p className="mt-1.5 font-body text-[13px] leading-relaxed text-text-secondary">
        <MathText text={conjecture.hypothesis} />
      </p>

      <div className="mt-3 flex flex-col gap-2">
        {conjecture.options.map((opt, i) => {
          const isCorrect = i === conjecture.correctOptionIndex;
          const isSelected = selected === i;
          const revealed = selected !== null;
          return (
            <motion.button
              key={opt}
              onClick={() => setSelected(i)}
              disabled={revealed}
              whileHover={revealed ? undefined : { scale: 1.015, boxShadow: "0 4px 14px color-mix(in srgb, var(--primary) 18%, transparent)" }}
              whileTap={revealed ? undefined : { scale: 0.985 }}
              transition={TRANSITION_FAST}
              className={cn(
                "cursor-pointer rounded-[8px] border px-3 py-2.5 text-left font-body text-[13px] transition-colors duration-[var(--motion-fast)] disabled:cursor-default",
                revealed && isCorrect && "border-accent-emerald bg-accent-emerald/10 text-text-primary",
                revealed && isSelected && !isCorrect && "border-notification bg-notification/10 text-text-primary",
                revealed && !isSelected && !isCorrect && "border-border text-text-muted",
                !revealed && "border-border text-text-primary hover:border-primary"
              )}
              style={{ transitionTimingFunction: "var(--ease-standard)" }}
            >
              <MathText text={opt} />
            </motion.button>
          );
        })}
      </div>

      {selected !== null && (
        <p className="mt-3 font-body text-[12.5px] leading-relaxed text-text-secondary">
          <span className="font-medium text-text-primary">Explanation: </span>
          <MathText text={conjecture.explanation} />
        </p>
      )}
    </Card>
  );
}
