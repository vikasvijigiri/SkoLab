import Link from "next/link";
import { TriangleAlert, RotateCw, Sparkles } from "lucide-react";
import { motion } from "framer-motion";
import { Card } from "@/components/ui/Card";
import { AnimatedCounter } from "@/components/ui/AnimatedCounter";
import type { AuthorResponse } from "@/lib/types";
import { TRANSITION_FAST } from "@/lib/motion";

function Stat({ label, value, accent }: { label: string; value: number; accent: string }) {
  return (
    <div className="flex-1">
      <p className="font-mono text-[22px] font-medium tabular-nums" style={{ color: accent }}>
        <AnimatedCounter to={value} />
      </p>
      <p className="mt-0.5 font-body text-[11px] font-medium uppercase tracking-wide text-text-muted">
        {label}
      </p>
    </div>
  );
}

/** Web port of FrontierPulseCard.kt — client-derived stat trio, no historical deltas available yet. */
export function FrontierPulseCard({
  author,
  loading,
  error,
  unresolved,
  onRetry,
}: {
  author: AuthorResponse | null;
  loading: boolean;
  error?: string | null;
  /** No OpenAlex match yet — a cold-start prompt, not an error. */
  unresolved?: boolean;
  onRetry?: () => void;
}) {
  if (loading) {
    return (
      <Card className="animate-pulse">
        <div className="h-16 rounded-[8px] bg-surface-subtle" />
      </Card>
    );
  }

  if (!author && unresolved && !error) {
    return (
      <Card className="flex items-center gap-2.5">
        <Sparkles size={16} className="shrink-0 text-accent-violet" />
        <p className="flex-1 font-body text-[12.5px] text-text-secondary">
          Your impact metrics unlock once we match your published work.{" "}
          <Link href="/profile" className="font-medium text-primary">
            Add your name or ORCID
          </Link>
          .
        </p>
      </Card>
    );
  }

  if (!author) {
    return (
      <Card className="flex items-center gap-2.5">
        <TriangleAlert size={16} className="shrink-0 text-accent-amber" />
        <p className="flex-1 font-body text-[12.5px] text-text-secondary">
          {error ? `Couldn't load your metrics — ${error}` : "Couldn't load your metrics."}
        </p>
        {onRetry && (
          <motion.button
            onClick={onRetry}
            whileHover={{ scale: 1.05 }}
            whileTap={{ scale: 0.94 }}
            transition={TRANSITION_FAST}
            className="flex shrink-0 items-center gap-1 rounded-full border border-border px-2.5 py-1 font-body text-[11.5px] font-medium text-text-secondary transition-colors duration-[var(--motion-fast)] hover:border-primary/40 hover:text-text-primary"
          >
            <RotateCw size={11} />
            Retry
          </motion.button>
        )}
      </Card>
    );
  }

  return (
    <Card accentColor="var(--gradient-hero)" className="relative overflow-hidden">
      <div
        className="pointer-events-none absolute inset-0 opacity-[0.06]"
        style={{ background: "var(--gradient-hero)" }}
        aria-hidden
      />
      <div className="relative flex items-center gap-6 p-1">
        <Stat label="Disruption" value={Math.round(author.disruption_score)} accent="var(--accent-orange)" />
        <Stat label="Skill Index" value={Math.round(author.average_skill_score)} accent="var(--primary)" />
        <Stat label="Works" value={author.works_count} accent="var(--accent-teal)" />
      </div>
    </Card>
  );
}
