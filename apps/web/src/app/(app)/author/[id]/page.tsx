"use client";

import { use, useMemo } from "react";
import { useSearchParams } from "next/navigation";
import Link from "next/link";
import { motion, AnimatePresence } from "framer-motion";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import {
  RefreshCw,
  Radar as RadarIcon,
  TrendingUp,
  BookOpen,
  Users2,
  Sparkles,
  FileText,
  UserSearch,
} from "lucide-react";
import { Card } from "@/components/ui/Card";
import { Badge } from "@/components/ui/Badge";
import { Button } from "@/components/ui/Button";
import { Reveal } from "@/components/ui/Reveal";
import { ErrorBanner } from "@/components/ui/ErrorBanner";
import { MarkdownText, MathText } from "@/components/ui/MathText";
import { RailShell } from "@/components/layout/RailShell";
import { RadarChart } from "@/components/author/RadarChart";
import { CitationBarChart } from "@/components/author/CitationBarChart";
import { StatTile } from "@/components/author/StatTile";
import { MetricPill } from "@/components/author/MetricPill";
import { SectionHeading } from "@/components/author/SectionHeading";
import { refreshAuthor } from "@/lib/api/endpoints";
import {
  authorQuery,
  collaboratorsQuery,
  heatmapQuery,
  journalAdvisorQuery,
} from "@/lib/api/queries";
import type { AuthorResponse } from "@/lib/types";

function authorLabel(authorPair: string) {
  return authorPair.split("|")[0];
}

export default function AuthorDetailPage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = use(params);
  return <AuthorDetailContent authorId={id} />;
}

/** Exported for unit tests — the default export is just the Next route wrapper. */
export function AuthorDetailContent({ authorId }: { authorId: string }) {
  const searchParams = useSearchParams();
  const name = searchParams.get("name") ?? "";
  const focus = searchParams.get("focus") ?? undefined;
  const queryClient = useQueryClient();

  const primary = authorQuery(name, authorId, focus);
  const {
    data: author,
    isPending,
    isError,
    refetch,
  } = useQuery(primary);

  // Each secondary section owns its own request — it renders as soon as its own
  // data arrives, independent of the other two. `enabled` is gated on the author
  // id inside the query factory, so nothing fires until the primary resolves.
  const authorPk = author?.id ?? "";
  const { data: collaborators = [] } = useQuery(
    collaboratorsQuery(authorPk, author?.field_of_study, author?.display_name),
  );
  const { data: heatmap } = useQuery(heatmapQuery(authorPk));
  const { data: journals = [] } = useQuery(journalAdvisorQuery(authorPk));

  const refresh = useMutation({
    mutationFn: () => refreshAuthor(name, authorId),
    onSuccess: (fresh: AuthorResponse) => {
      queryClient.setQueryData(primary.queryKey, fresh);
      queryClient.invalidateQueries({ queryKey: ["author", authorPk] });
    },
  });

  const sortedWorks = useMemo(
    () => (author?.works ? [...author.works].sort((a, b) => (b.year ?? 0) - (a.year ?? 0)) : []),
    [author?.works],
  );

  if (isError && !author) {
    return (
      <div className="mx-auto max-w-3xl px-4 py-6 md:px-8">
        <ErrorBanner
          message="Couldn't load this researcher's profile — the backend may be unreachable."
          onRetry={() => refetch()}
        />
      </div>
    );
  }

  if (isPending || !author) {
    return (
      <div className="mx-auto max-w-3xl px-4 py-6 md:px-8">
        <div className="h-48 animate-pulse rounded-[8px] bg-surface-subtle" />
      </div>
    );
  }

  // "Creativity"/"Complexity" and "Policy Impact" are deliberately excluded: the first two
  // are backend aliases of Novelty/Interdisciplinary (numerically identical, not independent
  // signals — see researcher_worker.py), and policy_patent_score has no real data source
  // wired up yet, so it's always exactly 0 for every researcher. Showing any of them here
  // would present fake or duplicate numbers as if they were measured.
  const radarAxes = [
    { label: "Disruption", value: author.disruption_score, color: "var(--metric-disruption)" },
    { label: "Novelty", value: author.semantic_novelty, color: "var(--metric-novelty)" },
    { label: "Fut. Impact", value: author.future_impact_score, color: "var(--metric-future-impact)" },
    { label: "Influence", value: author.network_centrality, color: "var(--metric-influence)" },
    { label: "Open Sci.", value: author.open_science_score, color: "var(--metric-open-science)" },
    { label: "Collab.", value: author.collaboration_diversity, color: "var(--metric-collab)" },
  ];

  const pills = [
    ...radarAxes,
    { label: "Cit. Accel.", value: author.citation_acceleration, color: "var(--accent-cyan)" },
    { label: "Consistency", value: author.research_consistency, color: "var(--metric-consistency)" },
    { label: "Interdiscipl.", value: author.interdisciplinary_index, color: "var(--accent-indigo)" },
  ];

  const sparseData = author.works_count < 5 || author.cited_by_count < 10;
  const refreshing = refresh.isPending;

  const railContent = (
    <div className="flex flex-col gap-4">
      {/* Hero */}
      <motion.div initial={{ opacity: 0, y: -8 }} animate={{ opacity: 1, y: 0 }} transition={{ duration: 0.4 }}>
        <Card accentColor="var(--primary)">
          <div className="flex items-start gap-4">
            <div
              className="flex h-16 w-16 shrink-0 items-center justify-center rounded-full font-display text-[22px] font-bold text-white shadow-card"
              style={{ background: "var(--gradient-hero)" }}
            >
              {(author.display_name || "Unknown").slice(0, 1).toUpperCase()}
            </div>
            <div className="min-w-0 flex-1">
              <div className="flex items-center justify-between gap-2">
                <h1 className="truncate font-display text-[19px] font-bold text-text-primary">
                  {author.display_name || "Unknown Researcher"}
                </h1>
                <motion.button
                  onClick={() => refresh.mutate()}
                  disabled={refreshing}
                  whileHover={{ scale: 1.05 }}
                  whileTap={{ scale: 0.95 }}
                  className="flex shrink-0 cursor-pointer items-center gap-1 font-body text-[12px] font-medium text-primary disabled:opacity-50"
                >
                  <motion.span animate={refreshing ? { rotate: 360 } : {}} transition={{ duration: 0.8, repeat: refreshing ? Infinity : 0, ease: "linear" }}>
                    <RefreshCw size={12} />
                  </motion.span>
                  {refreshing ? "Refreshing…" : "Refresh"}
                </motion.button>
              </div>
              <p className="mt-0.5 font-body text-[13.5px] text-text-secondary">
                {author.institution}
                {author.field_of_study ? ` · ${author.field_of_study}` : ""}
              </p>
              {author.expertise.length > 0 && (
                <div className="mt-2 flex flex-wrap gap-1.5">
                  {author.expertise.slice(0, 5).map((e) => (
                    <Badge key={e} accentColor="var(--accent-indigo)">
                      {e}
                    </Badge>
                  ))}
                </div>
              )}
            </div>
          </div>

          <div className="mt-4 flex flex-wrap gap-2">
            <Button variant="primary" fullWidth={false} disabled title="Coming soon">
              Connect
            </Button>
            <Button variant="outlined" fullWidth={false} disabled title="Coming soon">
              Message
            </Button>
            <Button variant="ghost" fullWidth={false} disabled title="Coming soon">
              Collaborate
            </Button>
          </div>
        </Card>
      </motion.div>

      {/* Stats quad */}
      <div className="flex gap-2.5">
        <StatTile label="H-Index" value={author.h_index} />
        <StatTile label="i10-Index" value={author.i10_index} />
        <StatTile label="Works" value={author.works_count} />
        <StatTile label="Citations" value={author.cited_by_count} />
      </div>
    </div>
  );

  return (
    <div className="mx-auto max-w-3xl px-4 py-6 md:px-8 lg:max-w-6xl">
      <AnimatePresence>
        {refresh.isError && (
          <motion.div initial={{ opacity: 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0 }}>
            <ErrorBanner message="Couldn't refresh this profile right now." />
          </motion.div>
        )}
      </AnimatePresence>

      <RailShell rail={railContent} railWidth="300px" stickyRail mobileRail="collapsible">
        <div className="flex flex-col gap-4">
      {/* Metrics radar + pills */}
      {author.metrics_computed ? (
        <Reveal>
          <Card>
            <SectionHeading icon={RadarIcon} color="var(--primary)">
              Impact Signature
            </SectionHeading>
            {sparseData && (
              <p className="mt-1 font-body text-[12px] text-text-muted">
                Based on {author.works_count} published {author.works_count === 1 ? "work" : "works"} and{" "}
                {author.cited_by_count} {author.cited_by_count === 1 ? "citation" : "citations"} — several signals
                need a longer track record before they diverge from zero.
              </p>
            )}
            <div className="mt-2 flex justify-center">
              <RadarChart axes={radarAxes} />
            </div>
            <div className="mt-3 flex flex-wrap gap-2">
              {pills.map((p) => (
                <MetricPill key={p.label} label={p.label} value={p.value} color={p.color} />
              ))}
            </div>
          </Card>
        </Reveal>
      ) : (
        <Card>
          <p className="font-body text-[13px] text-text-muted">
            Metrics are still being computed for this researcher — check back shortly.
          </p>
        </Card>
      )}

      {/* Citation heatmap */}
      {heatmap && (
        <Reveal>
          <Card>
            <SectionHeading icon={TrendingUp} color="var(--accent-cyan)">
              Citation Trend
            </SectionHeading>
            <div className="mt-3">
              <CitationBarChart data={heatmap} />
            </div>
          </Card>
        </Reveal>
      )}

      {/* Journal advisor + Suggested connections — paired side by side once there's room */}
      <div className="grid grid-cols-1 gap-4 xl:grid-cols-2">
        {journals.length > 0 && (
          <Reveal>
            <Card>
              <SectionHeading icon={BookOpen} color="var(--accent-emerald)">
                Journal Advisor
              </SectionHeading>
              <div className="mt-2.5 flex flex-col gap-2">
                {journals.map((j) => (
                  <div key={j.journal_name} className="flex items-start justify-between gap-3 rounded-[8px] bg-surface-subtle p-2.5">
                    <div className="min-w-0">
                      <p className="font-body text-[13px] font-medium text-text-primary">{j.journal_name}</p>
                      <p className="mt-0.5 font-body text-[11px] text-text-muted">
                        ~{j.works_count.toLocaleString()} papers/yr · {j.is_oa ? "Open Access" : "Hybrid/Subscription"}
                        {j.citation_impact > 0 && ` · ${j.citation_impact} citation impact`}
                      </p>
                      <p className="mt-1 font-body text-[12px] text-text-secondary">{j.rationale}</p>
                    </div>
                    <Badge accentColor="var(--accent-emerald)" className="shrink-0">
                      {j.match_score}%
                    </Badge>
                  </div>
                ))}
              </div>
            </Card>
          </Reveal>
        )}

        {collaborators.length > 0 && (
          <Reveal>
            <Card>
              <SectionHeading icon={Users2} color="var(--accent-teal)">
                Suggested Connections
              </SectionHeading>
              <div className="mt-2.5 flex flex-col gap-2">
                {collaborators.slice(0, 5).map((c) => (
                  <div key={c.id} className="flex items-center justify-between gap-3 rounded-[8px] bg-surface-subtle p-2.5">
                    <div className="min-w-0">
                      <p className="truncate font-body text-[13px] font-medium text-text-primary">{c.name}</p>
                      <p className="truncate font-body text-[12px] text-text-secondary">
                        <MathText text={c.connection_path} />
                      </p>
                    </div>
                    <Badge accentColor="var(--accent-teal)" className="shrink-0">
                      {c.relevance_score}%
                    </Badge>
                  </div>
                ))}
              </div>
            </Card>
          </Reveal>
        )}
      </div>

      {/* Next prediction */}
      {author.next_prediction && (
        <Reveal>
          <Card accentColor="var(--accent-violet)">
            <SectionHeading icon={Sparkles} color="var(--accent-violet)">
              AI Gap Finder
            </SectionHeading>
            <p className="mt-2 whitespace-pre-line font-body text-[13px] leading-relaxed text-text-secondary">
              <MarkdownText text={author.next_prediction} />
            </p>
          </Card>
        </Reveal>
      )}

      {/* Publications */}
      {author.works.length > 0 && (
        <Reveal>
          <Card>
            <SectionHeading icon={FileText} color="var(--accent-indigo)">
              Publications
            </SectionHeading>
            <div className="mt-2.5 flex flex-col divide-y divide-border">
              {sortedWorks.map((w) => (
                  <Link
                    key={w.id ?? w.title}
                    href={w.id ? `/paper/${encodeURIComponent(w.id)}` : "#"}
                    className="py-2.5 first:pt-0 last:pb-0"
                  >
                    <p className="font-body text-[13.5px] font-medium text-text-primary hover:text-primary">
                      <MathText text={w.title ?? ""} />
                    </p>
                    <p className="mt-0.5 font-body text-[12px] text-text-secondary">
                      {w.authors?.slice(0, 3).map(authorLabel).join(", ")}
                      {w.journal ? ` · ${w.journal}` : ""}
                      {w.year ? ` · ${w.year}` : ""} · {w.citations} citations
                    </p>
                  </Link>
                ))}
            </div>
          </Card>
        </Reveal>
      )}

      {/* Similar researchers */}
      {author.similar_researchers.length > 0 && (
        <Reveal>
          <Card>
            <SectionHeading icon={UserSearch} color="var(--accent-rose)">
              Similar Researchers
            </SectionHeading>
            <div className="mt-2.5 flex flex-col gap-2">
              {author.similar_researchers.slice(0, 5).map((s) => (
                <Link
                  key={s.id}
                  href={`/author/${encodeURIComponent(s.id)}?name=${encodeURIComponent(s.display_name || "")}`}
                  className="flex items-center justify-between gap-3 rounded-[8px] bg-surface-subtle p-2.5"
                >
                  <div className="min-w-0">
                    <p className="truncate font-body text-[13px] font-medium text-text-primary">{s.display_name || "Unknown Researcher"}</p>
                    <p className="truncate font-body text-[12px] text-text-secondary">{s.institution}</p>
                  </div>
                </Link>
              ))}
            </div>
          </Card>
        </Reveal>
      )}
        </div>
      </RailShell>
    </div>
  );
}
