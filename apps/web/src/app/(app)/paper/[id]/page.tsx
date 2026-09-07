"use client";

import { use } from "react";
import { useQuery } from "@tanstack/react-query";
import { motion } from "framer-motion";
import {
  ListChecks,
  Cog,
  Wrench,
  Lightbulb,
  Sigma,
  TriangleAlert,
  Globe,
  Compass,
} from "lucide-react";
import { ExternalLink, Unlock } from "lucide-react";
import { Card } from "@/components/ui/Card";
import { Badge } from "@/components/ui/Badge";
import { ErrorBanner } from "@/components/ui/ErrorBanner";
import { Reveal } from "@/components/ui/Reveal";
import { MathText, Formula } from "@/components/ui/MathText";
import { RailShell } from "@/components/layout/RailShell";
import { TableOfContents } from "@/components/paper/TableOfContents";
import { RelatedPapersCard } from "@/components/paper/RelatedPapersCard";
import { AbstractCard } from "@/components/paper/AbstractCard";
import { PaperCite } from "@/components/paper/PaperCite";
import { AuthorInline } from "@/components/discovery/AuthorInline";
import type { PaperIntelligence } from "@/lib/types";
import { paperWorkQuery, paperAnalysisQuery } from "@/lib/api/queries";

const CONFIDENCE_COLOR: Record<PaperIntelligence["confidence"], string> = {
  High: "var(--accent-emerald)",
  Medium: "var(--accent-amber)",
  Low: "var(--notification)",
};

function Section({
  id,
  title,
  icon: Icon,
  color,
  delay,
  children,
}: {
  id: string;
  title: string;
  icon: typeof ListChecks;
  color: string;
  delay: number;
  children: React.ReactNode;
}) {
  return (
    <Reveal delay={delay}>
      <Card id={id} className="scroll-mt-6">
        <h2 className="flex items-center gap-2 font-display text-h3 font-semibold text-text-primary">
          <Icon size={15} style={{ color }} />
          {title}
        </h2>
        <div className="mt-2">{children}</div>
      </Card>
    </Reveal>
  );
}

function BulletList({ items }: { items: string[] }) {
  return (
    <ul className="flex flex-col gap-2">
      {items.map((item, i) => (
        <li key={i} className="flex gap-2 font-body text-body-s leading-relaxed text-text-secondary">
          <span className="mt-1.5 h-1 w-1 shrink-0 rounded-full bg-text-muted" />
          <MathText text={item} />
        </li>
      ))}
    </ul>
  );
}

export default function PaperDetailPage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = use(params);
  return <PaperDetailContent id={id} />;
}

/** Exported for unit tests — the default export is only the Next route wrapper. */
export function PaperDetailContent({ id }: { id: string }) {
  const workQ = useQuery(paperWorkQuery(id));
  const work = workQ.data ?? null;
  const error = workQ.isError ? "Couldn't load this paper." : null;

  const intelQ = useQuery({
    ...paperAnalysisQuery({ title: work?.display_name, doi: work?.doi, openalexId: work?.id }),
    enabled: Boolean(work?.id),
  });
  // Only trust a well-formed intelligence payload — a malformed `{}` / `[]` from
  // a degraded backend used to crash the whole page on `.key_findings.length`.
  const raw = intelQ.data;
  const intelligence: PaperIntelligence | null =
    raw && typeof raw === "object" && Array.isArray((raw as PaperIntelligence).key_findings)
      ? (raw as PaperIntelligence)
      : null;
  const intelLoading = intelQ.isPending;
  const intelError =
    intelQ.isError || (intelQ.isSuccess && !intelligence) ? "Couldn't analyze this paper." : null;

  if (error && !work) {
    return (
      <div className="mx-auto max-w-3xl px-4 py-6 md:px-8">
        <ErrorBanner message={error} onRetry={() => workQ.refetch()} />
      </div>
    );
  }

  if (!work) {
    return (
      <div className="mx-auto max-w-3xl px-4 py-6 md:px-8">
        <div className="h-48 animate-pulse rounded-md bg-surface-subtle" />
      </div>
    );
  }

  const firstAffiliation = work.authorships?.[0]?.institutions?.[0]?.display_name || null;
  const workType = work.type ? work.type.replace(/-/g, " ") : null;

  const tocSections = intelligence
    ? (
        [
          { id: "tldr", label: "TL;DR" },
          intelligence.key_findings.length > 0 && { id: "key-findings", label: "Key Findings" },
          intelligence.techniques.length > 0 && { id: "techniques", label: "Techniques & Methods" },
          intelligence.tools_and_software.length > 0 && { id: "tools", label: "Tools & Software" },
          intelligence.core_concepts.length > 0 && { id: "core-concepts", label: "Core Concepts" },
          intelligence.formulas.length > 0 && { id: "formulas", label: "Mathematical Model" },
          intelligence.limitations.length > 0 && { id: "limitations", label: "Honest Limitations" },
          intelligence.real_world_impact && { id: "real-world-impact", label: "Real-World Impact" },
          intelligence.future_directions.length > 0 && { id: "future-directions", label: "Future Directions" },
        ] as const
      ).filter((s): s is { id: string; label: string } => Boolean(s))
    : [];

  return (
    <div className="mx-auto max-w-3xl px-4 py-6 md:px-8 lg:max-w-5xl">
      <motion.div initial={{ opacity: 0, y: -8 }} animate={{ opacity: 1, y: 0 }} transition={{ duration: 0.4 }}>
        <Card accentColor="var(--primary)">
          <div className="flex items-start justify-between gap-3">
            <h1 className="font-display text-h2 font-bold leading-snug text-text-primary">
              <MathText text={work.display_name} />
            </h1>
            <div className="shrink-0">
              <PaperCite work={work} />
            </div>
          </div>

          {work.authorships && work.authorships.length > 0 && (
            <p className="mt-2">
              <AuthorInline
                authors={work.authorships.map((a) => ({
                  name: a.author.display_name,
                  id: a.author.id,
                }))}
                max={12}
              />
            </p>
          )}

          {firstAffiliation && (
            <p className="mt-1 font-body text-[12px] text-text-muted">{firstAffiliation}</p>
          )}

          {/* metadata strip — venue · year · type · OA · citations · DOI */}
          <div className="mt-2 flex flex-wrap items-center gap-x-2 gap-y-1 font-body text-[12.5px] text-text-muted">
            {work.primary_location?.source?.display_name && (
              <span className="text-text-secondary">{work.primary_location.source.display_name}</span>
            )}
            {work.publication_year && <span>· {work.publication_year}</span>}
            {workType && (
              <span className="rounded bg-surface-subtle px-1.5 py-0.5 data text-[10.5px] uppercase tracking-wide">
                {workType}
              </span>
            )}
            {work.open_access?.is_oa && (
              <Badge accentColor="var(--accent-emerald)" className="gap-1">
                <Unlock size={10} aria-hidden="true" />
                Open Access
              </Badge>
            )}
            {work.cited_by_count !== undefined && <span>· {work.cited_by_count.toLocaleString()} citations</span>}
            {work.doi && (
              <a
                href={work.doi}
                target="_blank"
                rel="noopener noreferrer"
                className="inline-flex items-center gap-0.5 text-primary hover:underline"
              >
                DOI
                <ExternalLink size={11} aria-hidden="true" />
              </a>
            )}
          </div>
        </Card>
      </motion.div>

      <AbstractCard index={work.abstract_inverted_index} />

      <RelatedPapersCard workId={id} />

      {intelLoading && (
        <Card className="mt-4 animate-pulse">
          <div className="h-32 rounded-md bg-surface-subtle" />
        </Card>
      )}

      {intelError && !intelLoading && (
        <div className="mt-4">
          <ErrorBanner
            message={`Couldn't generate the paper intelligence report — ${intelError}`}
            onRetry={() => intelQ.refetch()}
          />
        </div>
      )}

      {intelligence && (
        <div className="mt-4">
          <RailShell
            rail={<TableOfContents sections={tocSections} />}
            railPosition="right"
            railWidth="200px"
            stickyRail
            mobileRail="hidden"
          >
            <div className="flex flex-col gap-4">
              <Reveal>
                <Card id="tldr" accentColor="var(--accent-violet)" className="scroll-mt-6">
                  <div className="flex items-center justify-between">
                    <h2 className="font-display text-h3 font-semibold text-text-primary">TL;DR</h2>
                    <Badge accentColor={CONFIDENCE_COLOR[intelligence.confidence]}>
                      {intelligence.confidence} confidence
                    </Badge>
                  </div>
                  <p className="mt-2 font-body text-body-s leading-relaxed text-text-primary">
                    <MathText text={intelligence.tldr} />
                  </p>
                </Card>
              </Reveal>

              {intelligence.key_findings.length > 0 && (
                <Section id="key-findings" title="Key Findings" icon={ListChecks} color="var(--accent-emerald)" delay={0.05}>
                  <BulletList items={intelligence.key_findings} />
                </Section>
              )}

              {intelligence.techniques.length > 0 && (
                <Section id="techniques" title="Techniques & Methods" icon={Cog} color="var(--primary)" delay={0.08}>
                  <div className="flex flex-wrap gap-2">
                    {intelligence.techniques.map((t) => (
                      <Badge key={t} accentColor="var(--primary)">
                        {t}
                      </Badge>
                    ))}
                  </div>
                </Section>
              )}

              {intelligence.tools_and_software.length > 0 && (
                <Section id="tools" title="Tools & Software" icon={Wrench} color="var(--accent-teal)" delay={0.11}>
                  <div className="flex flex-wrap gap-2">
                    {intelligence.tools_and_software.map((t) => (
                      <Badge key={t} accentColor="var(--accent-teal)">
                        {t}
                      </Badge>
                    ))}
                  </div>
                </Section>
              )}

              {intelligence.core_concepts.length > 0 && (
                <Section id="core-concepts" title="Core Concepts" icon={Lightbulb} color="var(--accent-amber)" delay={0.14}>
                  <BulletList items={intelligence.core_concepts} />
                </Section>
              )}

              {intelligence.formulas.length > 0 && (
                <Section id="formulas" title="Mathematical Model" icon={Sigma} color="var(--accent-indigo)" delay={0.17}>
                  <div className="flex flex-col gap-2">
                    {intelligence.formulas.map((f, i) => (
                      <Formula
                        key={i}
                        tex={f}
                        className="overflow-x-auto rounded-md bg-surface-subtle p-3 text-text-primary"
                      />
                    ))}
                  </div>
                </Section>
              )}

              {intelligence.limitations.length > 0 && (
                <Section id="limitations" title="Honest Limitations" icon={TriangleAlert} color="var(--accent-orange)" delay={0.2}>
                  <BulletList items={intelligence.limitations} />
                </Section>
              )}

              {intelligence.real_world_impact && (
                <Section id="real-world-impact" title="Real-World Impact" icon={Globe} color="var(--accent-cyan)" delay={0.23}>
                  <p className="font-body text-body-s leading-relaxed text-text-secondary">
                    <MathText text={intelligence.real_world_impact} />
                  </p>
                </Section>
              )}

              {intelligence.future_directions.length > 0 && (
                <Section id="future-directions" title="Future Directions" icon={Compass} color="var(--accent-rose)" delay={0.26}>
                  <BulletList items={intelligence.future_directions} />
                </Section>
              )}
            </div>
          </RailShell>
        </div>
      )}
    </div>
  );
}
