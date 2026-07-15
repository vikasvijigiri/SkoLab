"use client";

import { Suspense, use, useEffect, useState } from "react";
import { useSearchParams } from "next/navigation";
import Link from "next/link";
import { motion } from "framer-motion";
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
import { AnimatedCounter } from "@/components/ui/AnimatedCounter";
import { Reveal } from "@/components/ui/Reveal";
import { ErrorBanner } from "@/components/ui/ErrorBanner";
import { MarkdownText, MathText } from "@/components/ui/MathText";
import { RailShell } from "@/components/layout/RailShell";
import { RadarChart } from "@/components/author/RadarChart";
import { CitationBarChart } from "@/components/author/CitationBarChart";
import {
  searchAuthor,
  refreshAuthor,
  getNetworkCollaborators,
  getCitationHeatmap,
  getJournalAdvisor,
} from "@/lib/api/endpoints";
import type { AuthorResponse, NetworkCollaborator, CitationHeatmap, JournalRecommendation } from "@/lib/types";

function authorLabel(authorPair: string) {
  return authorPair.split("|")[0];
}

function StatTile({ label, value }: { label: string; value: number }) {
  return (
    <div className="flex-1 rounded-[8px] bg-surface-subtle px-3 py-2.5 text-center">
      <p className="font-mono text-[18px] font-semibold tabular-nums text-text-primary">
        <AnimatedCounter to={value} />
      </p>
      <p className="mt-0.5 font-body text-[10.5px] font-medium uppercase tracking-wide text-text-muted">
        {label}
      </p>
    </div>
  );
}

function MetricPill({ label, value, color }: { label: string; value: number; color: string }) {
  const isZero = value === 0;
  return (
    <div
      className="flex items-center gap-1.5 rounded-full border border-border px-2.5 py-1"
      style={{ opacity: isZero ? 0.5 : 1 }}
    >
      <span className="h-1.5 w-1.5 rounded-full" style={{ backgroundColor: isZero ? "var(--text-muted)" : color }} />
      <span className="font-body text-[11px] text-text-secondary">{label}</span>
      <span className="font-mono text-[11px] font-semibold text-text-primary">{value.toFixed(0)}</span>
    </div>
  );
}

function SectionHeading({ icon: Icon, color, children }: { icon: typeof RadarIcon; color: string; children: React.ReactNode }) {
  return (
    <h2 className="flex items-center gap-1.5 font-display text-[15px] font-semibold text-text-primary">
      <Icon size={15} style={{ color }} />
      {children}
    </h2>
  );
}

export default function AuthorDetailPage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = use(params);
  return (
    <Suspense fallback={null}>
      <AuthorDetailContent authorId={id} />
    </Suspense>
  );
}

function AuthorDetailContent({ authorId }: { authorId: string }) {
  const searchParams = useSearchParams();
  const name = searchParams.get("name") ?? "";
  const focus = searchParams.get("focus") ?? undefined;

  const [author, setAuthor] = useState<AuthorResponse | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [collaborators, setCollaborators] = useState<NetworkCollaborator[]>([]);
  const [heatmap, setHeatmap] = useState<CitationHeatmap | null>(null);
  const [journals, setJournals] = useState<JournalRecommendation[]>([]);
  const [refreshing, setRefreshing] = useState(false);
  const [connectionState, setConnectionState] = useState<"idle" | "connected" | "messaged" | "proposed">("idle");

  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        const a = await searchAuthor(name, authorId, focus);
        if (cancelled) return;
        setAuthor(a);
        setError(null);

        const [collabRes, heatmapRes, journalRes] = await Promise.allSettled([
          getNetworkCollaborators(a.id, a.field_of_study, a.display_name),
          getCitationHeatmap(a.id),
          getJournalAdvisor(a.id),
        ]);
        if (cancelled) return;
        if (collabRes.status === "fulfilled") setCollaborators(collabRes.value);
        if (heatmapRes.status === "fulfilled") setHeatmap(heatmapRes.value);
        if (journalRes.status === "fulfilled") setJournals(journalRes.value);
      } catch {
        if (!cancelled) setError("Couldn't load this researcher's profile — the backend may be unreachable.");
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [authorId, name, focus]);

  async function handleRefresh() {
    setRefreshing(true);
    try {
      const a = await refreshAuthor(name, authorId);
      setAuthor(a);
      setError(null);
    } catch {
      setError("Couldn't refresh this profile right now.");
    } finally {
      setRefreshing(false);
    }
  }

  if (error && !author) {
    return (
      <div className="mx-auto max-w-3xl px-4 py-6 md:px-8">
        <ErrorBanner message={error} />
      </div>
    );
  }

  if (!author) {
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
              {author.display_name.slice(0, 1).toUpperCase()}
            </div>
            <div className="min-w-0 flex-1">
              <div className="flex items-center justify-between gap-2">
                <h1 className="truncate font-display text-[19px] font-bold text-text-primary">
                  {author.display_name}
                </h1>
                <motion.button
                  onClick={handleRefresh}
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

          <div className="mt-4 flex gap-2">
            <Button
              variant={connectionState === "connected" ? "outlined" : "primary"}
              onClick={() => setConnectionState("connected")}
            >
              {connectionState === "connected" ? "Request sent" : "Connect"}
            </Button>
            <Button variant="outlined" onClick={() => setConnectionState("messaged")}>
              Message
            </Button>
            <Button variant="ghost" onClick={() => setConnectionState("proposed")}>
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
      {error && <ErrorBanner message={error} />}

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
                    <div>
                      <p className="font-body text-[13px] font-medium text-text-primary">{j.journal_name}</p>
                      <p className="mt-0.5 font-body text-[12px] text-text-secondary">{j.submission_tips}</p>
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
                      <p className="truncate font-body text-[12px] text-text-secondary">{c.connection_path}</p>
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
              {author.works
                .slice()
                .sort((a, b) => (b.year ?? 0) - (a.year ?? 0))
                .map((w) => (
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
                  href={`/author/${encodeURIComponent(s.id)}?name=${encodeURIComponent(s.display_name)}`}
                  className="flex items-center justify-between gap-3 rounded-[8px] bg-surface-subtle p-2.5"
                >
                  <div className="min-w-0">
                    <p className="truncate font-body text-[13px] font-medium text-text-primary">{s.display_name}</p>
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
