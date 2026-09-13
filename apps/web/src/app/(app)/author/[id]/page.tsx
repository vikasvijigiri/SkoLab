"use client";

import { use, useState } from "react";
import { useSearchParams, useRouter } from "next/navigation";
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
  ExternalLink,
  ChevronDown,
  ChevronRight,
  ArrowUpRight,
  ArrowRight,
  ArrowDownRight,
  LayoutDashboard,
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
import { AuthorInline, splitAuthorPair } from "@/components/discovery/AuthorInline";
import { MomentumSparkline } from "@/components/discovery/MomentumSparkline";
import { TrackButton } from "@/components/discovery/TrackButton";
import { RelationshipGraph, type RelationshipNode, type RelationshipEdge } from "@/components/discovery/RelationshipGraph";
import { refreshAuthor, getHorizonPrediction } from "@/lib/api/endpoints";
import {
  authorQuery,
  collaboratorsQuery,
  heatmapQuery,
  journalAdvisorQuery,
  similarResearchersQuery,
} from "@/lib/api/queries";
import { cn, shortOpenAlexId } from "@/lib/utils";
import { momentumSlope, classifyMomentum } from "@/lib/discovery/signals";
import { citationVelocity, fieldStandingLabel } from "@/lib/discovery/format";
import { DISCOVERY_CONFIG } from "@/lib/discovery/config";
import type { AuthorResponse, MomentumState } from "@/lib/types";

const MOMENTUM_DISPLAY: Record<MomentumState, { icon: typeof ArrowRight; color: string; label: string }> = {
  rising: { icon: ArrowUpRight, color: "var(--accent-live)", label: "Rising" },
  steady: { icon: ArrowRight, color: "var(--text-muted)", label: "Steady" },
  cooling: { icon: ArrowDownRight, color: "var(--accent-orange)", label: "Cooling" },
};

function HighlightMetric({ value, label, color }: { value: React.ReactNode; label: string; color?: string }) {
  return (
    <div className="bg-surface p-4 text-center">
      <p className="data text-[18px] font-semibold" style={{ color: color ?? "var(--text-primary)" }}>
        {value}
      </p>
      <p className="mt-1 font-body text-[11px] text-text-muted">{label}</p>
    </div>
  );
}

export default function AuthorDetailPage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = use(params);
  return <AuthorDetailContent authorId={id} />;
}

/** Exported for unit tests — the default export is just the Next route wrapper. */
export function AuthorDetailContent({ authorId }: { authorId: string }) {
  const searchParams = useSearchParams();
  const router = useRouter();
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
  const { data: similar } = useQuery(similarResearchersQuery(authorPk));
  // Prefer the live similarity engine; fall back to whatever search_author
  // embedded (older payloads) so the section never regresses to empty.
  const similarResearchers =
    similar?.results && similar.results.length > 0
      ? similar.results
      : (author?.similar_researchers ?? []).map((s) => ({
          author_id: s.id,
          display_name: s.display_name,
          institution: s.institution,
          field_of_study: s.field_of_study ?? "",
          h_index: s.h_index ?? 0,
          score: 0,
          why: "",
          shared_collaborators: 0,
        }));

  const refresh = useMutation({
    mutationFn: () => refreshAuthor(name, authorId),
    onSuccess: (fresh: AuthorResponse) => {
      queryClient.setQueryData(primary.queryKey, fresh);
      queryClient.invalidateQueries({ queryKey: ["author", authorPk] });
    },
  });

  // Full research dashboard (decision 0021) — collapsed by default; the
  // Highlights layer above it is meant to answer "should I care about this
  // person" on its own.
  const [dashboardOpen, setDashboardOpen] = useState(false);

  // Horizon's prediction engine, pointed at this one person instead of a
  // field (decision 0021) — the same `/discovery/predict` call Horizon's own
  // page makes, just with this author as the subject. On-demand (a button),
  // not auto-fired on page load: it's an LLM call, and Horizon/AI features
  // are gated as premium elsewhere in this app — a profile view alone
  // shouldn't trigger one for every visitor.
  const horizonPredict = useMutation({
    mutationFn: () =>
      getHorizonPrediction(author?.field_of_study || author?.expertise?.[0] || "", undefined, author?.id),
  });

  // `toSorted` (non-mutating) + no manual useMemo — the React Compiler memoises
  // this and can actually preserve it, unlike `[...arr].sort()` in a useMemo.
  const sortedWorks = author?.works
    ? author.works.toSorted((a, b) => (b.year ?? 0) - (a.year ?? 0))
    : [];

  if (isError && !author) {
    return (
      <div className="mx-auto max-w-3xl px-4 py-6 md:px-8">
        <ErrorBanner
          message="We couldn't load this researcher's profile. Give it another try in a moment."
          onRetry={() => refetch()}
        />
      </div>
    );
  }

  if (isPending || !author) {
    return (
      <div className="mx-auto max-w-3xl px-4 py-6 md:px-8">
        <div className="h-48 animate-pulse rounded-md bg-surface-subtle" />
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

  // ── Highlights layer (decision 0021) ─────────────────────────────────────
  const firstName = (author.display_name || "This researcher").split(" ")[0];

  // Momentum: the same rising/steady/cooling derivation `ResearcherCard` uses
  // on Discovery, reused here on this author's own citation heatmap — real
  // per-year citation counts already fetched for the "Citation Trend" chart
  // below, not a second data source.
  const citationSeries = heatmap?.citations ?? [];
  const momentumScore = momentumSlope(citationSeries);
  const momentum = classifyMomentum(momentumScore, DISCOVERY_CONFIG.momentumEpsilon);
  const momentumDisplay = MOMENTUM_DISPLAY[momentum];
  const MomentumIcon = momentumDisplay.icon;
  const velocity = citationVelocity(citationSeries);

  // Real collaborator institutions vs. this author's own — the same signal
  // "Suggested Connections" below already lists, just counted.
  const home = (author.institution ?? "").trim().toLowerCase();
  const crossInstitutionCount = collaborators.length
    ? collaborators.filter((c) => c.institution && c.institution.trim().toLowerCase() !== home).length
    : null;

  const narrativeSentences: string[] = [
    `${author.display_name || "This researcher"} has published ${author.works_count.toLocaleString()} work${author.works_count === 1 ? "" : "s"}${
      author.field_of_study ? ` in ${author.field_of_study}` : ""
    }, cited ${author.cited_by_count.toLocaleString()} time${author.cited_by_count === 1 ? "" : "s"} in total.`,
  ];
  if (citationSeries.length >= 3) {
    narrativeSentences.push(
      momentum === "steady"
        ? `Citation output has held steady over the last ${citationSeries.length} years.`
        : `Citation output has been ${momentum} over the last ${citationSeries.length} years${
            velocity != null ? `, running ${velocity}× the prior average most recently` : ""
          }.`,
    );
  }
  if (crossInstitutionCount != null && crossInstitutionCount > 0) {
    narrativeSentences.push(
      `${crossInstitutionCount} of ${collaborators.length} known collaborators are outside ${
        author.institution || "their home institution"
      }, suggesting ${firstName} collaborates readily across institutions.`,
    );
  }
  const narrative = narrativeSentences.join(" ");

  const canPredict = Boolean(author.field_of_study || author.expertise?.length);

  // Similar researchers as a relationship graph (decision 0021), replacing a
  // flat list — center node is this researcher, spokes are the real
  // similarity-engine results (`similarResearchersQuery`/`similar_researchers`
  // fallback above), plus one dashed peer-to-peer edge between two spokes so
  // it doesn't read as a pure hub-and-spoke when the underlying pair also
  // shares an institution (a real, if partial, signal — not invented).
  const graphPeers = similarResearchers.slice(0, 4);
  const graphNodes: RelationshipNode[] = [
    { id: authorPk, label: author.display_name || "This researcher", kind: "center" },
    ...graphPeers.map((s, i) => ({
      id: s.author_id,
      label: s.display_name || "Unknown researcher",
      sublabel: s.institution || undefined,
      kind: "peer" as const,
      color: ["var(--accent-violet)", "var(--accent-teal)", "var(--accent-orange)", "var(--accent-amber)"][i % 4],
    })),
  ];
  const graphEdges: RelationshipEdge[] = graphPeers.map((s) => ({ source: authorPk, target: s.author_id }));
  // A dashed peer-to-peer edge between the first two peers that share an
  // institution — real when it exists, simply omitted when it doesn't.
  let peerEdgeFound = false;
  for (let i = 0; i < graphPeers.length && !peerEdgeFound; i++) {
    for (let j = i + 1; j < graphPeers.length && !peerEdgeFound; j++) {
      const instA = graphPeers[i]!.institution?.trim().toLowerCase();
      const instB = graphPeers[j]!.institution?.trim().toLowerCase();
      if (instA && instB && instA === instB) {
        graphEdges.push({ source: graphPeers[i]!.author_id, target: graphPeers[j]!.author_id, dashed: true });
        peerEdgeFound = true;
      }
    }
  }

  const railContent = (
    <div className="flex flex-col gap-4">
      {/* Hero */}
      <motion.div initial={{ opacity: 0, y: -8 }} animate={{ opacity: 1, y: 0 }} transition={{ duration: 0.4 }}>
        <Card accentColor="var(--primary)">
          <div className="flex items-start gap-4">
            <div
              className="flex h-16 w-16 shrink-0 items-center justify-center rounded-full font-display text-[22px] font-bold text-text-on-primary shadow-card"
              style={{ background: "var(--primary)" }}
            >
              {(author.display_name || "Unknown").slice(0, 1).toUpperCase()}
            </div>
            <div className="min-w-0 flex-1">
              <div className="flex items-center justify-between gap-2">
                <h1 className="truncate font-display text-display-m font-bold text-text-primary">
                  {author.display_name || "Unknown Researcher"}
                </h1>
                <motion.button
                  onClick={() => refresh.mutate()}
                  disabled={refreshing}
                  className="flex shrink-0 cursor-pointer items-center gap-1 font-body text-[12px] font-medium text-primary transition-colors hover:text-primary-dark disabled:opacity-50"
                >
                  <motion.span animate={refreshing ? { rotate: 360 } : {}} transition={{ duration: 0.8, repeat: refreshing ? Infinity : 0, ease: "linear" }}>
                    <RefreshCw size={12} />
                  </motion.span>
                  {refreshing ? "Refreshing…" : "Refresh"}
                </motion.button>
              </div>
              <p className="mt-1 font-body text-body-s text-text-secondary">
                {author.institution}
                {author.field_of_study ? ` · ${author.field_of_study}` : ""}
              </p>
              {author.orcid && (
                <a
                  href={`https://orcid.org/${author.orcid.replace(/^https?:\/\/orcid\.org\//, "")}`}
                  target="_blank"
                  rel="noopener noreferrer"
                  className="mt-1 inline-flex items-center gap-2 font-mono text-[11.5px] text-text-secondary transition-colors hover:text-primary hover:underline"
                >
                  {/* ORCID brand green as a decorative dot — the low-contrast
                      #A6CE39 never carries text, so no WCAG issue. */}
                  <span className="h-2 w-2 shrink-0 rounded-full bg-[#A6CE39]" aria-hidden="true" />
                  ORCID {author.orcid.replace(/^https?:\/\/orcid\.org\//, "")}
                  <ExternalLink size={10} aria-hidden="true" />
                </a>
              )}
              {(author.expertise?.length ?? 0) > 0 && (
                <div className="mt-2 flex flex-wrap gap-2">
                  {author.expertise!.slice(0, 5).map((e) => (
                    <Badge key={e} accentColor="var(--accent-indigo)">
                      {e}
                    </Badge>
                  ))}
                </div>
              )}
            </div>
          </div>

          <div className="mt-4 flex items-center gap-2">
            <Button variant="primary" fullWidth={false} disabled title="Coming soon">
              Connect — coming soon
            </Button>
            <TrackButton authorId={authorPk || authorId} name={author.display_name || "this researcher"} />
          </div>
        </Card>
      </motion.div>
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
      {/* ── Highlights (decision 0021) — a short sourced narrative, a few
           hand-picked metrics, plain-English momentum, and Horizon's
           prediction pointed at this one person. The dense dashboard below
           is still all here, just collapsed by default. ── */}
      <Reveal>
        <Card className="overflow-hidden p-0!">
          <div className="flex items-center justify-between gap-2 px-5 pt-4">
            <SectionHeading icon={Sparkles} color="var(--primary)">
              Highlights
            </SectionHeading>
            <span className="data rounded-full bg-surface-subtle px-2.5 py-1 text-[10px] text-text-muted">
              AI-assisted · sourced below
            </span>
          </div>

          <p className="px-5 pt-3 font-body text-body-s leading-relaxed text-text-primary">{narrative}</p>

          <div className="mt-4 grid grid-cols-2 gap-px bg-border sm:grid-cols-4">
            <HighlightMetric value={fieldStandingLabel(author.h_index, null)} label="Field standing" />
            <HighlightMetric
              value={velocity != null ? `${velocity}×` : "—"}
              label="Citation velocity"
              color={velocity != null ? "var(--accent-live)" : undefined}
            />
            <HighlightMetric value={crossInstitutionCount ?? "—"} label="Cross-inst. co-authors" />
            <HighlightMetric
              value={author.metrics_computed ? Math.round(author.research_consistency) : "—"}
              label="Consistency score"
            />
          </div>

          {citationSeries.length >= 3 && (
            <div className="flex flex-wrap items-center gap-3 border-t border-border bg-surface-subtle px-5 py-3.5">
              <span
                className="flex shrink-0 items-center gap-1 font-mono text-[11px] font-bold uppercase tracking-wide"
                style={{ color: momentumDisplay.color }}
              >
                <MomentumIcon size={13} />
                {momentumDisplay.label}
              </span>
              {citationSeries.length >= 2 && (
                <MomentumSparkline values={citationSeries} momentum={momentum} width={110} height={24} />
              )}
              <p className="font-body text-[12.5px] text-text-secondary">
                Citations per year have been {momentum} over the last {citationSeries.length} years.
              </p>
            </div>
          )}

          <div className="flex gap-3 border-t border-border px-5 py-4">
            <span className="flex h-8 w-8 shrink-0 items-center justify-center rounded-md bg-accent-violet/15 text-accent-violet">
              <Sparkles size={15} />
            </span>
            <div className="min-w-0 flex-1">
              <p className="font-body text-[13px] font-semibold text-text-primary">
                What {firstName} might work on next
              </p>

              {!horizonPredict.data && !horizonPredict.isPending && (
                <>
                  <p className="mt-1 font-body text-[12.5px] leading-relaxed text-text-secondary">
                    Horizon&apos;s foresight engine can speculate about this researcher&apos;s likely next
                    direction, from the same evidence-led model behind the generic Horizon tool.
                  </p>
                  <button
                    type="button"
                    onClick={() => horizonPredict.mutate()}
                    disabled={!canPredict}
                    className="mt-2 inline-flex items-center gap-1.5 rounded-md border border-accent-violet/40 px-3 py-1.5 font-body text-[12px] font-semibold text-accent-violet transition-colors hover:bg-accent-violet/10 disabled:cursor-not-allowed disabled:opacity-40"
                  >
                    Predict what&apos;s next
                  </button>
                  {!canPredict && (
                    <p className="mt-1 font-body text-[11px] text-text-muted">
                      No field of study on file for this researcher yet — nothing to ground a prediction in.
                    </p>
                  )}
                </>
              )}

              {horizonPredict.isPending && (
                <p className="mt-1 font-body text-[12.5px] text-text-muted">Thinking…</p>
              )}

              {horizonPredict.isError && (
                <p className="mt-1 font-body text-[12.5px] text-notification">
                  Couldn&apos;t generate a prediction right now — try again in a moment.
                </p>
              )}

              {horizonPredict.data && (
                <>
                  {horizonPredict.data.is_fallback && (
                    <Badge accentColor="var(--warning)" className="mt-1">
                      Estimated — AI unavailable
                    </Badge>
                  )}
                  <p className="mt-1.5 font-body text-[12.5px] leading-relaxed text-text-secondary">
                    <strong className="text-text-primary">{horizonPredict.data.breakthrough_name}</strong> —{" "}
                    {horizonPredict.data.description}
                  </p>
                  <p className="mt-1 font-mono text-[10.5px] text-text-muted">
                    Speculative — Horizon&apos;s foresight engine, not a stated plan.
                  </p>
                </>
              )}

              <a
                href={`/horizon?field=${encodeURIComponent(author.field_of_study || "")}`}
                className="mt-2 inline-flex items-center gap-1 font-body text-[12px] font-semibold text-accent-violet hover:underline"
              >
                Explore this field in Horizon
                <ChevronRight size={12} />
              </a>
            </div>
          </div>
        </Card>
      </Reveal>

      {/* ── Full research dashboard — collapsed by default. Nothing below is
           removed, only demoted behind this toggle (decision 0021). ── */}
      <div className="rounded-lg border border-border bg-surface">
        <button
          type="button"
          onClick={() => setDashboardOpen((v) => !v)}
          aria-expanded={dashboardOpen}
          className="flex w-full items-center justify-between gap-2 px-5 py-4 text-left"
        >
          <span className="flex items-center gap-2">
            <LayoutDashboard size={16} className="text-text-secondary" />
            <span className="font-display text-h3 font-semibold text-text-primary">Full research dashboard</span>
          </span>
          <ChevronDown
            size={16}
            className={cn("text-text-muted transition-transform", dashboardOpen && "rotate-180")}
          />
        </button>

        {dashboardOpen && (
          <div className="flex flex-col gap-4 px-5 pb-5">
      {/* Stats quad */}
      <div className="flex gap-3">
        <StatTile label="H-Index" value={author.h_index} />
        <StatTile label="i10-Index" value={author.i10_index} />
        <StatTile label="Works" value={author.works_count} />
        <StatTile label="Citations" value={author.cited_by_count} />
      </div>

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
          <p className="font-body text-body-s text-text-muted">
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
              <div className="mt-3 flex flex-col gap-2">
                {journals.map((j) => (
                  <div key={j.journal_name} className="flex items-start justify-between gap-3 rounded-md bg-surface-subtle p-3">
                    <div className="min-w-0">
                      <p className="font-body text-body-s font-medium text-text-primary">{j.journal_name}</p>
                      <p className="mt-1 font-body text-[11px] text-text-muted">
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
              <div className="mt-3 flex flex-col gap-2">
                {collaborators.slice(0, 5).map((c) => (
                  <Link
                    key={c.id}
                    href={`/author/${encodeURIComponent(shortOpenAlexId(c.id))}?name=${encodeURIComponent(c.name || "")}`}
                    className="flex items-center justify-between gap-3 rounded-md bg-surface-subtle p-3 transition-colors hover:bg-surface-subtle/60 hover:ring-1 hover:ring-primary/30"
                  >
                    <div className="min-w-0">
                      <p className="truncate font-body text-body-s font-medium text-text-primary">{c.name}</p>
                      <p className="truncate font-body text-[12px] text-text-secondary">
                        <MathText text={c.connection_path} />
                      </p>
                    </div>
                    <Badge accentColor="var(--accent-teal)" className="shrink-0">
                      {c.relevance_score}%
                    </Badge>
                  </Link>
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
            <p className="mt-2 whitespace-pre-line font-body text-body-s leading-relaxed text-text-secondary">
              <MarkdownText text={author.next_prediction} />
            </p>
          </Card>
        </Reveal>
      )}

      {/* Publications */}
      {sortedWorks.length > 0 && (
        <Reveal>
          <Card>
            <SectionHeading icon={FileText} color="var(--accent-indigo)">
              Publications
            </SectionHeading>
            <div className="mt-3 flex flex-col divide-y divide-border">
              {sortedWorks.map((w) => (
                <div key={w.id ?? w.title} className="py-3 first:pt-0 last:pb-0">
                  {w.id ? (
                    <Link
                      href={`/paper/${encodeURIComponent(shortOpenAlexId(w.id))}`}
                      className="font-body text-body-s font-medium text-text-primary hover:text-primary"
                    >
                      <MathText text={w.title ?? ""} />
                    </Link>
                  ) : (
                    <p className="font-body text-body-s font-medium text-text-primary">
                      <MathText text={w.title ?? ""} />
                    </p>
                  )}
                  <p className="mt-1 flex flex-wrap items-baseline gap-x-1 font-body text-[12px] text-text-secondary">
                    {w.authors && w.authors.length > 0 && (
                      <AuthorInline
                        authors={w.authors.map(splitAuthorPair)}
                        max={4}
                        className="text-[12px]"
                      />
                    )}
                    <span className="text-text-muted">
                      {w.journal ? ` · ${w.journal}` : ""}
                      {w.year ? ` · ${w.year}` : ""} · {w.citations} citations
                    </span>
                  </p>
                </div>
              ))}
            </div>
          </Card>
        </Reveal>
      )}

      {/* Similar researchers — a small relationship graph (decision 0021),
           replacing the old flat list. Center node is this researcher; spokes
           are the real similarity-engine results above. */}
      {graphPeers.length > 0 && (
        <Reveal>
          <Card>
            <SectionHeading icon={UserSearch} color="var(--accent-rose)">
              Similar Researchers
            </SectionHeading>
            <div className="mt-3">
              <RelationshipGraph
                nodes={graphNodes}
                edges={graphEdges}
                onNodeClick={(id) => {
                  const peer = graphPeers.find((s) => s.author_id === id);
                  const params = new URLSearchParams({ name: peer?.display_name || "" });
                  router.push(`/author/${encodeURIComponent(shortOpenAlexId(id))}?${params.toString()}`);
                }}
                caption="Nodes are clickable — open a researcher's own Highlights. Dashed line: a real institution overlap between two peers, not just shared field."
              />
            </div>
          </Card>
        </Reveal>
      )}
          </div>
        )}
      </div>
        </div>
      </RailShell>
    </div>
  );
}
