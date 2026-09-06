"use client";

import { useState } from "react";
import { useSearchParams } from "next/navigation";
import { motion, AnimatePresence } from "framer-motion";
import { useQuery } from "@tanstack/react-query";
import { SearchX, Flame, Trophy, ChevronRight } from "lucide-react";
import { Chip } from "@/components/ui/Badge";
import { ErrorBanner } from "@/components/ui/ErrorBanner";
import { RailShell } from "@/components/layout/RailShell";
import { cn } from "@/lib/utils";
import {
  leaderboardQuery,
  openAlexWorksQuery,
  openAlexFieldsQuery,
  openAlexSubfieldsQuery,
  openAlexTopicsQuery,
  discoveryAuthorsQuery,
  discoveryWorksQuery,
} from "@/lib/api/queries";
import type { OpenAlexTaxon } from "@/lib/types";
import { AuthorResultCard } from "@/components/discovery/AuthorResultCard";
import { PaperResultCard } from "@/components/discovery/PaperResultCard";
import { LeaderboardRow } from "@/components/discovery/LeaderboardRow";
import { TRANSITION_FAST } from "@/lib/motion";

type Mode = "researchers" | "papers";

export default function DiscoveryPage() {
  return <DiscoveryContent />;
}

/** Exported for unit tests — the default export is only the Suspense-free wrapper. */
export function DiscoveryContent() {
  const searchParams = useSearchParams();
  const [mode, setMode] = useState<Mode>(searchParams.get("tab") === "papers" ? "papers" : "researchers");

  // Taxonomy drilldown — the only way to navigate. Nothing is typed.
  const [field, setField] = useState<OpenAlexTaxon | null>(null);
  const [subfield, setSubfield] = useState<OpenAlexTaxon | null>(null);
  const [topic, setTopic] = useState<OpenAlexTaxon | null>(null);

  const fieldsQ = useQuery(openAlexFieldsQuery());
  const subfieldsQ = useQuery(openAlexSubfieldsQuery(field?.id));
  const topicsQ = useQuery(openAlexTopicsQuery(subfield?.id));

  // Deepest selected node drives the results.
  const node = topic
    ? ({ level: "topic", id: topic.id, label: topic.display_name } as const)
    : subfield
      ? ({ level: "subfield", id: subfield.id, label: subfield.display_name } as const)
      : field
        ? ({ level: "field", id: field.id, label: field.display_name } as const)
        : null;

  const leaderboard = useQuery({
    ...leaderboardQuery("all"),
    enabled: !node && mode === "researchers",
  });
  const trending = useQuery({
    ...openAlexWorksQuery({}),
    enabled: !node && mode === "papers",
  });
  const nodeAuthors = useQuery({
    ...discoveryAuthorsQuery(node?.level ?? "field", node?.id),
    enabled: Boolean(node) && mode === "researchers",
  });
  const nodeWorks = useQuery({
    ...discoveryWorksQuery(node?.level ?? "field", node?.id),
    enabled: Boolean(node) && mode === "papers",
  });

  const active =
    mode === "researchers" ? (node ? nodeAuthors : leaderboard) : node ? nodeWorks : trending;

  function reset(level: "root" | "field" | "subfield") {
    if (level === "root") setField(null);
    if (level !== "subfield") setSubfield(null);
    setTopic(null);
  }

  // Which chip set to show next, and its click handler.
  const nextLevel: { title: string; q: typeof fieldsQ; pick: (t: OpenAlexTaxon) => void } | null = !field
    ? { title: "Pick a field", q: fieldsQ, pick: (t) => { setField(t); setSubfield(null); setTopic(null); } }
    : !subfield
      ? { title: "Narrow it down", q: subfieldsQ, pick: (t) => { setSubfield(t); setTopic(null); } }
      : !topic
        ? { title: "Pick a topic", q: topicsQ, pick: setTopic }
        : null;

  const railContent = (
    <div className="flex flex-col gap-4">
      <div className="flex gap-1 rounded-full bg-surface-subtle p-1">
        {(["researchers", "papers"] as const).map((m) => (
          <motion.button
            key={m}
            onClick={() => setMode(m)}
            whileTap={{ scale: 0.96 }}
            transition={TRANSITION_FAST}
            className={cn(
              "relative flex-1 rounded-full py-2 font-body text-[13px] font-medium capitalize transition-colors duration-[var(--motion-fast)]",
              mode === m ? "text-text-on-primary" : "text-text-secondary hover:bg-surface/60 hover:text-text-primary",
            )}
            style={{ transitionTimingFunction: "var(--ease-standard)" }}
          >
            {mode === m && (
              <motion.span
                layoutId="discovery-mode-pill"
                className="absolute inset-0 rounded-full bg-primary"
                transition={{ type: "spring", stiffness: 400, damping: 32 }}
              />
            )}
            <span className="relative z-10">{m}</span>
          </motion.button>
        ))}
      </div>

      {/* Breadcrumb — every crumb is a button back to that level. */}
      <div className="flex flex-wrap items-center gap-1 font-body text-[12px] text-text-muted">
        <button
          type="button"
          onClick={() => reset("root")}
          className={cn("cursor-pointer hover:text-text-primary", !field && "font-semibold text-text-primary")}
        >
          All fields
        </button>
        {field && (
          <>
            <ChevronRight size={12} />
            <button
              type="button"
              onClick={() => reset("field")}
              className={cn("cursor-pointer hover:text-text-primary", !subfield && "font-semibold text-text-primary")}
            >
              {field.display_name}
            </button>
          </>
        )}
        {subfield && (
          <>
            <ChevronRight size={12} />
            <button
              type="button"
              onClick={() => reset("subfield")}
              className={cn("cursor-pointer hover:text-text-primary", !topic && "font-semibold text-text-primary")}
            >
              {subfield.display_name}
            </button>
          </>
        )}
        {topic && (
          <>
            <ChevronRight size={12} />
            <span className="font-semibold text-text-primary">{topic.display_name}</span>
          </>
        )}
      </div>

      {nextLevel && (
        <div>
          <span className="mb-1.5 block font-body text-[11.5px] font-semibold uppercase tracking-wide text-text-muted">
            {nextLevel.title}
          </span>
          {nextLevel.q.isPending ? (
            <div className="flex flex-wrap gap-1.5">
              {[0, 1, 2, 3, 4].map((i) => (
                <div key={i} className="h-7 w-24 animate-pulse rounded-full bg-surface-subtle" />
              ))}
            </div>
          ) : (
            <div className="flex flex-wrap gap-1.5 lg:flex-col lg:items-stretch">
              {(nextLevel.q.data ?? []).map((t) => (
                <Chip key={t.id} onClick={() => nextLevel.pick(t)} className="lg:justify-start">
                  {t.display_name}
                </Chip>
              ))}
            </div>
          )}
        </div>
      )}
    </div>
  );

  const gridClass = "grid grid-cols-1 gap-2.5 lg:grid-cols-2 xl:grid-cols-3";

  return (
    <div className="mx-auto flex max-w-2xl flex-col gap-4 px-4 py-6 md:px-8 lg:max-w-6xl">
      <motion.h1
        initial={{ opacity: 0, y: -8 }}
        animate={{ opacity: 1, y: 0 }}
        transition={{ duration: 0.4 }}
        className="font-display text-[22px] font-bold text-text-primary"
      >
        Discovery
      </motion.h1>

      <RailShell rail={railContent} railWidth="260px" mobileRail="collapsible" stickyRail>
        <div className="flex flex-col gap-3">
          <div className="flex items-center gap-1.5 text-text-secondary">
            {mode === "researchers" ? <Trophy size={14} /> : <Flame size={14} />}
            <span className="font-body text-[12.5px] font-semibold uppercase tracking-wide">
              {node
                ? `${mode === "researchers" ? "Top researchers" : "Top papers"} in ${node.label}`
                : mode === "researchers"
                  ? "Top Researchers"
                  : "Trending This Year"}
            </span>
          </div>

          <AnimatePresence>
            {active.isError && (
              <motion.div initial={{ opacity: 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0 }}>
                <ErrorBanner message="Couldn't load right now." onRetry={() => active.refetch()} />
              </motion.div>
            )}
          </AnimatePresence>

          <div className={gridClass}>
            {active.isPending &&
              [0, 1, 2, 3].map((i) => (
                <div key={i} className="h-16 animate-pulse rounded-[8px] bg-surface-subtle" />
              ))}

            {!active.isPending && !active.isError && mode === "researchers" && !node &&
              (leaderboard.data ?? []).map((entry, i) => (
                <LeaderboardRow key={entry.id} entry={entry} index={i} />
              ))}

            {!active.isPending && !active.isError && mode === "researchers" && node &&
              (nodeAuthors.data ?? []).map((a, i) => (
                <AuthorResultCard
                  key={a.id}
                  index={i}
                  a={{
                    id: a.id,
                    display_name: a.display_name,
                    institution: a.institution,
                    field_of_study: node.label,
                    h_index: a.h_index,
                  }}
                />
              ))}

            {!active.isPending && !active.isError && mode === "papers" &&
              ((node ? nodeWorks.data : trending.data) ?? []).map((w, i) => (
                <PaperResultCard key={w.id} w={w} index={i} />
              ))}
          </div>

          {!active.isPending && !active.isError && (active.data ?? []).length === 0 && (
            <div className="flex flex-col items-center gap-2 py-10 text-center">
              <SearchX size={26} className="text-text-muted" />
              <p className="font-body text-[13.5px] text-text-muted">
                {node ? "Nothing here yet for this topic." : "Pick a field on the left to explore."}
              </p>
            </div>
          )}
        </div>
      </RailShell>
    </div>
  );
}
