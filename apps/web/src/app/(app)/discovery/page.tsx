"use client";

import { useMemo, useState } from "react";
import { useSearchParams } from "next/navigation";
import { motion, AnimatePresence } from "framer-motion";
import { useQuery } from "@tanstack/react-query";
import { SearchX, Flame, Trophy, ChevronRight, Compass, Download, Lightbulb } from "lucide-react";
import { Chip } from "@/components/ui/Badge";
import { ErrorBanner } from "@/components/ui/ErrorBanner";
import { RailShell } from "@/components/layout/RailShell";
import { cn } from "@/lib/utils";
import { useMyProfile } from "@/lib/hooks/useMyProfile";
import {
  leaderboardQuery,
  openAlexWorksQuery,
  openAlexFieldsQuery,
  openAlexSubfieldsQuery,
  openAlexTopicsQuery,
  discoveryAuthorsQuery,
  discoveryWorksQuery,
  discoveryResearchersQuery,
  deceasedFlagsQuery,
  collabFlagsQuery,
} from "@/lib/api/queries";
import type {
  OpenAlexTaxon,
  DiscoveryFilterState,
  DiscoverySort,
  ResearcherResult,
} from "@/lib/types";
import { AuthorResultCard } from "@/components/discovery/AuthorResultCard";
import { PaperResultCard } from "@/components/discovery/PaperResultCard";
import { LeaderboardRow } from "@/components/discovery/LeaderboardRow";
import { ResearcherCard } from "@/components/discovery/ResearcherCard";
import {
  DiscoveryFilters,
  DEFAULT_FILTER_STATE,
  type DiscoveryFacets,
} from "@/components/discovery/DiscoveryFilters";
import { SegmentedControl } from "@/components/ui/SegmentedControl";
import { Button } from "@/components/ui/Button";
import { scoreFit } from "@/lib/discovery/fit";

type Mode = "researchers" | "papers";

const EMPTY_RESEARCHERS: ResearcherResult[] = [];
const MOMENTUM_RANK = { rising: 0, steady: 1, cooling: 2 } as const;

/** Word tokens for fuzzy taxon matching. */
const tokens = (s: string): string[] =>
  s.toLowerCase().replace(/[^a-z0-9 ]/g, " ").split(/\s+/).filter(Boolean);

/** Best display-name match for `query` among `taxa`, or null below a weak floor. */
function bestTaxonMatch(query: string, taxa: OpenAlexTaxon[]): OpenAlexTaxon | null {
  const q = new Set(tokens(query));
  if (q.size === 0) return null;
  let best: OpenAlexTaxon | null = null;
  let bestScore = 0;
  for (const t of taxa) {
    const words = tokens(t.display_name);
    const hits = words.filter((w) => q.has(w)).length;
    const score = hits / Math.max(words.length, 1);
    if (hits > 0 && score > bestScore) {
      best = t;
      bestScore = score;
    }
  }
  return bestScore >= 0.3 ? best : null;
}

export default function DiscoveryPage() {
  return <DiscoveryContent />;
}

/** Exported for unit tests — the default export is only the Suspense-free wrapper. */
export function DiscoveryContent() {
  const searchParams = useSearchParams();
  const [mode, setMode] = useState<Mode>(searchParams.get("tab") === "papers" ? "papers" : "researchers");

  // Taxonomy drilldown — "explore another area". Nothing is typed.
  const [field, setField] = useState<OpenAlexTaxon | null>(null);
  const [subfield, setSubfield] = useState<OpenAlexTaxon | null>(null);
  const [topic, setTopic] = useState<OpenAlexTaxon | null>(null);

  // Fit-first controls.
  const [filters, setFilters] = useState<DiscoveryFilterState>(DEFAULT_FILTER_STATE);
  const [sort, setSort] = useState<DiscoverySort>("fit");

  const { author, firestoreProfile } = useMyProfile();
  const viewerFocus = firestoreProfile?.researchFocus || author?.field_of_study || "";
  const viewer = useMemo(
    () => ({ expertise: author?.expertise ?? [], institution: author?.institution ?? undefined }),
    [author],
  );

  const fieldsQ = useQuery(openAlexFieldsQuery());
  const subfieldsQ = useQuery(openAlexSubfieldsQuery(field?.id));
  const topicsQ = useQuery(openAlexTopicsQuery(subfield?.id));

  // Resolve the viewer's own subfield from their profile focus.
  const viewerField = useMemo(
    () => (viewerFocus ? bestTaxonMatch(viewerFocus, fieldsQ.data ?? []) : null),
    [viewerFocus, fieldsQ.data],
  );
  const viewerSubfieldsQ = useQuery({
    ...openAlexSubfieldsQuery(viewerField?.id),
    enabled: Boolean(viewerField),
  });
  const viewerSubfield = useMemo(() => {
    const subs = viewerSubfieldsQ.data ?? [];
    if (subs.length === 0) return null;
    return bestTaxonMatch(viewerFocus, subs) ?? subs[0] ?? null;
  }, [viewerFocus, viewerSubfieldsQ.data]);

  // Deepest selected node drives the drilldown views.
  const node = topic
    ? ({ level: "topic", id: topic.id, label: topic.display_name } as const)
    : subfield
      ? ({ level: "subfield", id: subfield.id, label: subfield.display_name } as const)
      : field
        ? ({ level: "field", id: field.id, label: field.display_name } as const)
        : null;

  // The subfield the fit-first grid runs on: a manual drilldown wins, else the viewer's.
  const gridSubfield = subfield ?? viewerSubfield;
  const fitGridActive = mode === "researchers" && !topic && Boolean(gridSubfield);

  const leaderboard = useQuery({
    ...leaderboardQuery("all"),
    enabled: mode === "researchers" && !node && !gridSubfield,
  });
  const trending = useQuery({
    ...openAlexWorksQuery({}),
    enabled: !node && mode === "papers",
  });
  const nodeAuthors = useQuery({
    ...discoveryAuthorsQuery(node?.level ?? "field", node?.id),
    enabled: Boolean(node) && mode === "researchers" && !fitGridActive,
  });
  const nodeWorks = useQuery({
    ...discoveryWorksQuery(node?.level ?? "field", node?.id),
    enabled: Boolean(node) && mode === "papers",
  });

  // ── fit-first data ────────────────────────────────────────────────────────
  const researchersQ = useQuery({
    ...discoveryResearchersQuery(gridSubfield?.id, filters, sort),
    enabled: fitGridActive,
  });
  const rawRows = researchersQ.data ?? EMPTY_RESEARCHERS;
  const orcids = useMemo(
    () => rawRows.map((r) => r.orcid).filter((o): o is string => Boolean(o)),
    [rawRows],
  );
  const ids = useMemo(() => rawRows.map((r) => r.id), [rawRows]);
  const deceasedQ = useQuery({ ...deceasedFlagsQuery(orcids), enabled: orcids.length > 0 });
  const collabQ = useQuery({ ...collabFlagsQuery(ids), enabled: ids.length > 0 });

  const researchers = useMemo(() => {
    const deceased = deceasedQ.data ?? {};
    const collab = collabQ.data ?? {};
    let rows: ResearcherResult[] = rawRows.map((r) => ({
      ...r,
      deceased: r.orcid && deceased[r.orcid] ? { year: deceased[r.orcid]! } : undefined,
      openToCollaboration: collab[r.id] === true ? true : undefined,
      fit: scoreFit(viewer, r),
    }));
    rows = rows.filter((r) => {
      const s = r.signals;
      if (filters.careerStage.length && !filters.careerStage.includes(s.careerStage)) return false;
      if (filters.activity.length && !filters.activity.includes(s.activity)) return false;
      if (filters.momentum.length && !filters.momentum.includes(s.momentum)) return false;
      if (s.topicalFocus < filters.topicalFocusMin) return false;
      if (
        filters.sharesInstitution &&
        viewer.institution &&
        r.institution.toLowerCase() !== viewer.institution.toLowerCase()
      ) {
        return false;
      }
      return true;
    });
    if (sort === "fit") {
      rows = [...rows].sort((a, b) => (b.fit?.score ?? 0) - (a.fit?.score ?? 0));
    } else if (sort === "momentum") {
      rows = [...rows].sort(
        (a, b) =>
          MOMENTUM_RANK[a.signals.momentum] - MOMENTUM_RANK[b.signals.momentum] ||
          (b.fit?.score ?? 0) - (a.fit?.score ?? 0),
      );
    }
    // "standing" / "recent" arrive already ordered by the server.
    return rows;
  }, [rawRows, deceasedQ.data, collabQ.data, viewer, filters, sort]);

  const facets: DiscoveryFacets = useMemo(() => {
    const c = new Map<string, number>();
    const it = new Map<string, number>();
    for (const r of rawRows) {
      if (r.country) c.set(r.country, (c.get(r.country) ?? 0) + 1);
      if (r.instType) it.set(r.instType, (it.get(r.instType) ?? 0) + 1);
    }
    return {
      countries: [...c].map(([code, count]) => ({ code, count })).sort((a, b) => b.count - a.count),
      instTypes: [...it].map(([type, count]) => ({ type, count })).sort((a, b) => b.count - a.count),
    };
  }, [rawRows]);

  const degraded = viewer.expertise.length === 0;

  const active =
    mode === "researchers"
      ? node && !fitGridActive
        ? nodeAuthors
        : leaderboard
      : node
        ? nodeWorks
        : trending;

  function reset(level: "root" | "field" | "subfield") {
    if (level === "root") setField(null);
    if (level !== "subfield") setSubfield(null);
    setTopic(null);
  }

  // Which chip set to show next in "explore another area", and its click handler.
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
    <div className="flex flex-col gap-4">
      {fitGridActive && (
        <DiscoveryFilters
          state={filters}
          sort={sort}
          facets={facets}
          onChange={setFilters}
          onSortChange={setSort}
          onReset={() => {
            setFilters(DEFAULT_FILTER_STATE);
            setSort("fit");
          }}
        />
      )}

      <div className="flex flex-col gap-4 rounded-sm border border-border/80 bg-surface/70 p-4 shadow-card">
        <span className="eyebrow">Explore another area</span>
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
    </div>
  );

  const gridClass = "grid grid-cols-1 gap-3 lg:grid-cols-2 xl:grid-cols-3";

  const scopeLabel = gridSubfield?.display_name ?? node?.label ?? "your field";
  const resultCount = fitGridActive
    ? researchersQ.isPending
      ? null
      : researchers.length
    : !active.isPending && !active.isError
      ? (active.data ?? []).length
      : null;

  function downloadFieldBrief() {
    const label = scopeLabel;
    const lines = [
      `SkoLab field brief: ${label}`,
      `Generated: ${new Date().toISOString().slice(0, 10)}`,
      "",
      `Mode: ${mode}`,
      `Visible results: ${resultCount ?? "loading"}`,
      "",
      "Next steps",
      "- Review the leading researchers or papers.",
      "- Save a useful signal to a CoLab project.",
      "- Test an evidence-backed opportunity in Horizon.",
    ];
    const blob = new Blob([lines.join("\n")], { type: "text/plain;charset=utf-8" });
    const url = URL.createObjectURL(blob);
    const anchor = document.createElement("a");
    anchor.href = url;
    anchor.download = `${label.toLowerCase().replace(/[^a-z0-9]+/g, "-")}-field-brief.txt`;
    anchor.click();
    URL.revokeObjectURL(url);
  }

  const fitError = researchersQ.isError;
  const showEmpty = fitGridActive
    ? !researchersQ.isPending && !fitError && researchers.length === 0
    : !active.isPending && !active.isError && (active.data ?? []).length === 0;

  return (
    <div className="mx-auto flex w-full max-w-[1128px] flex-col gap-5 px-4 py-6 md:px-6">
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
            <div className="font-mono text-[10px] font-bold uppercase tracking-[0.16em] text-accent-indigo">
              Research intelligence
            </div>
            <h1 className="mt-1 font-display text-display-m font-bold text-text-primary">Discovery</h1>
            <p className="mt-1 font-body text-body-s text-text-secondary">
              The people behind your field — ranked by how well they fit your work.
            </p>
          </div>
        </div>
        {modeToggle}
      </motion.div>

      <RailShell rail={railContent} railWidth="280px" mobileRail="collapsible" stickyRail>
        <div className="flex flex-col gap-3">
          <div className="flex flex-col gap-3 rounded-sm border border-accent-indigo/20 bg-accent-indigo/5 p-4 sm:flex-row sm:items-center sm:justify-between">
            <div className="flex items-start gap-3">
              <span className="mt-0.5 flex h-8 w-8 shrink-0 items-center justify-center rounded-md bg-accent-indigo/15 text-accent-indigo">
                <Lightbulb size={15} />
              </span>
              <div>
                <p className="font-display text-h3 font-semibold text-text-primary">
                  From a name to a collaboration
                </p>
                <p className="mt-1 font-body text-[12px] leading-relaxed text-text-secondary">
                  {fitGridActive
                    ? `Researchers in ${scopeLabel}, closest fit first.`
                    : "Choose an area to reveal its researchers."}{" "}
                  Save a promising one to a CoLab, or pressure-test the field in Horizon.
                </p>
              </div>
            </div>
            <div className="flex shrink-0 flex-wrap gap-2">
              <Button
                type="button"
                variant="outlined"
                fullWidth={false}
                onClick={downloadFieldBrief}
                className="h-9! px-3! text-[12px]!"
              >
                <Download size={13} /> Brief
              </Button>
              <a
                href={`/horizon?field=${encodeURIComponent(scopeLabel)}`}
                className="inline-flex h-9 items-center gap-1.5 rounded-md bg-primary px-3 font-body text-[12px] font-semibold text-text-on-primary hover:bg-primary-dark"
              >
                Test in Horizon <ChevronRight size={13} />
              </a>
            </div>
          </div>

          <div className="flex items-center gap-2 text-text-secondary">
            <span
              className={cn(
                "flex h-7 w-7 items-center justify-center rounded-lg",
                mode === "researchers"
                  ? "bg-accent-amber/15 text-accent-amber"
                  : "bg-accent-orange/15 text-accent-orange",
              )}
            >
              {mode === "researchers" ? <Trophy size={14} /> : <Flame size={14} />}
            </span>
            <span className="eyebrow">
              {fitGridActive
                ? `Researchers in ${scopeLabel}`
                : node
                  ? `${mode === "researchers" ? "Top researchers" : "Top papers"} in ${node.label}`
                  : mode === "researchers"
                    ? "Top Researchers"
                    : "Trending This Year"}
            </span>
            {resultCount !== null && resultCount > 0 && (
              <span className="rounded-full bg-surface-subtle px-2 py-0.5 data text-[11px] text-text-muted">
                {resultCount} results
              </span>
            )}
            {fitGridActive && degraded && (
              <span className="font-body text-[11px] text-text-muted">
                · ranked by topical overlap — add your work in Profile for personalised fit
              </span>
            )}
          </div>

          <AnimatePresence>
            {(fitGridActive ? fitError : active.isError) && (
              <motion.div initial={{ opacity: 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0 }}>
                <ErrorBanner
                  message="Couldn't load right now."
                  onRetry={() => (fitGridActive ? researchersQ.refetch() : active.refetch())}
                />
              </motion.div>
            )}
          </AnimatePresence>

          <div className={gridClass}>
            {(fitGridActive ? researchersQ.isPending : active.isPending) &&
              [0, 1, 2, 3, 4, 5].map((i) => (
                <div key={i} className="h-[188px] animate-pulse rounded-md bg-surface-subtle" />
              ))}

            {fitGridActive &&
              !researchersQ.isPending &&
              !fitError &&
              researchers.map((r, i) => <ResearcherCard key={r.id} r={r} index={i} />)}

            {!fitGridActive &&
              !active.isPending &&
              !active.isError &&
              mode === "researchers" &&
              !node &&
              (leaderboard.data ?? []).map((entry, i) => (
                <LeaderboardRow key={entry.id} entry={entry} index={i} />
              ))}

            {!fitGridActive &&
              !active.isPending &&
              !active.isError &&
              mode === "researchers" &&
              node &&
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

            {!active.isPending &&
              !active.isError &&
              mode === "papers" &&
              ((node ? nodeWorks.data : trending.data) ?? []).map((w, i) => (
                <PaperResultCard key={w.id} w={w} index={i} />
              ))}
          </div>

          {showEmpty && (
            <div className="flex flex-col items-center gap-3 rounded-md border border-dashed border-border bg-surface-subtle/40 px-4 py-12 text-center">
              <SearchX size={24} className="text-text-muted" />
              <p className="font-body text-body font-medium text-text-primary">
                {fitGridActive
                  ? "No researchers match these filters"
                  : node
                    ? "Nothing here yet for this topic"
                    : "Pick your area"}
              </p>
              <p className="max-w-xs font-body text-body-s leading-relaxed text-text-muted">
                {fitGridActive
                  ? "Loosen a filter in the panel, or switch the sort."
                  : node
                    ? "Try a broader level in the breadcrumb, or switch between researchers and papers."
                    : "Choose a field in the panel to see its researchers, ranked by fit to your work."}
              </p>
            </div>
          )}
        </div>
      </RailShell>
    </div>
  );
}
