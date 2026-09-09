"use client";

import { useState } from "react";
import { useSearchParams } from "next/navigation";
import { motion, AnimatePresence } from "framer-motion";
import { useQuery } from "@tanstack/react-query";
import { SearchX, Flame, Trophy, ChevronRight, Compass } from "lucide-react";
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
import { SegmentedControl } from "@/components/ui/SegmentedControl";

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

  const modeToggle = (
    <SegmentedControl
      aria-label="Show researchers or papers"
      layoutId="discovery-mode-pill"
      options={[
        { value: "researchers", label: "researchers" },
        { value: "papers", label: "papers" },
      ]}
      value={mode}
      onChange={(v) => setMode(v as Mode)}
    />
  );

  const railContent = (
    <div className="flex flex-col gap-4 rounded-xl border border-border/80 bg-surface/70 p-4 shadow-card">
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
          <span className="eyebrow mb-2 block">{nextLevel.title}</span>
          {nextLevel.q.isPending ? (
            <div className="flex flex-wrap gap-2">
              {[0, 1, 2, 3, 4].map((i) => (
                <div key={i} className="h-7 w-24 animate-pulse rounded-full bg-surface-subtle" />
              ))}
            </div>
          ) : (
            <div className="flex flex-wrap gap-2 lg:flex-col lg:items-stretch">
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

  const gridClass = "grid grid-cols-1 gap-3 lg:grid-cols-2 xl:grid-cols-3";

  const resultCount = !active.isPending && !active.isError ? (active.data ?? []).length : null;

  return (
    <div className="mx-auto flex max-w-2xl flex-col gap-5 px-4 py-6 md:px-8 lg:max-w-6xl">
      <motion.div
        initial={{ opacity: 0, y: -8 }}
        animate={{ opacity: 1, y: 0 }}
        transition={{ duration: 0.4 }}
        className="flex flex-col gap-3 sm:flex-row sm:items-start sm:justify-between"
      >
        <div className="flex items-start gap-3">
          <div className="mt-1 flex h-10 w-10 shrink-0 items-center justify-center rounded-xl bg-accent-indigo/10 text-accent-indigo">
            <Compass size={20} />
          </div>
          <div>
          <div className="font-mono text-[10px] font-bold uppercase tracking-[0.16em] text-accent-indigo">Research intelligence</div>
          <h1 className="mt-1 font-display text-display-m font-bold text-text-primary">Discovery</h1>
          <p className="mt-1 font-body text-body-s text-text-secondary">
            Browse the literature and the people behind it, one field at a time.
          </p>
          </div>
        </div>
        {modeToggle}
      </motion.div>

      <RailShell rail={railContent} railWidth="260px" mobileRail="collapsible" stickyRail>
        <div className="flex flex-col gap-3">
          <div className="flex items-center gap-2 text-text-secondary">
            <span className={cn("flex h-7 w-7 items-center justify-center rounded-lg", mode === "researchers" ? "bg-accent-amber/15 text-accent-amber" : "bg-accent-orange/15 text-accent-orange")}>
              {mode === "researchers" ? <Trophy size={14} /> : <Flame size={14} />}
            </span>
            <span className="eyebrow">
              {node
                ? `${mode === "researchers" ? "Top researchers" : "Top papers"} in ${node.label}`
                : mode === "researchers"
                  ? "Top Researchers"
                  : "Trending This Year"}
            </span>
            {resultCount !== null && resultCount > 0 && (
              <span className="rounded-full bg-surface-subtle px-2 py-0.5 data text-[11px] text-text-muted">{resultCount} results</span>
            )}
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
              [0, 1, 2, 3, 4, 5].map((i) => (
                <div key={i} className="h-[88px] animate-pulse rounded-md bg-surface-subtle" />
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
            <div className="flex flex-col items-center gap-3 rounded-md border border-dashed border-border bg-surface-subtle/40 px-4 py-12 text-center">
              <SearchX size={24} className="text-text-muted" />
              <p className="font-body text-body font-medium text-text-primary">
                {node ? "Nothing here yet for this topic" : "Start with a field"}
              </p>
              <p className="max-w-xs font-body text-body-s leading-relaxed text-text-muted">
                {node
                  ? "Try a broader level in the breadcrumb, or switch between researchers and papers."
                  : "Pick a field from the panel to see its top researchers and most-cited work."}
              </p>
            </div>
          )}
        </div>
      </RailShell>
    </div>
  );
}
