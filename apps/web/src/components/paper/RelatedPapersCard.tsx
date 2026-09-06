"use client";

import Link from "next/link";
import { Compass, ArrowUpRight } from "lucide-react";
import { useQuery } from "@tanstack/react-query";
import { Card } from "@/components/ui/Card";
import { MathText } from "@/components/ui/MathText";
import { similarPapersQuery } from "@/lib/api/queries";

/**
 * "Related papers" — pgvector kNN over paper embeddings, re-ranked with
 * shared-concept + bibliographic-coupling signals and MMR-diversified by the
 * Go gateway. No LLM. Renders nothing once resolved with no results, so a
 * paper the engine can't place yet just doesn't show the section.
 */
export function RelatedPapersCard({ workId }: { workId: string }) {
  const { data, isLoading } = useQuery(similarPapersQuery(workId));
  const papers = data?.results ?? [];

  if (!isLoading && papers.length === 0) return null;

  return (
    <Card id="related-papers" accentColor="var(--accent-cyan)" className="mt-4 scroll-mt-6">
      <h2 className="flex items-center gap-1.5 font-display text-[15px] font-semibold text-text-primary">
        <Compass size={15} style={{ color: "var(--accent-cyan)" }} />
        Related papers
      </h2>

      {isLoading ? (
        <div className="mt-3 flex flex-col gap-2">
          {[0, 1, 2].map((i) => (
            <div key={i} className="h-14 animate-pulse rounded-[8px] bg-surface-subtle" />
          ))}
        </div>
      ) : (
        <div className="mt-3 flex flex-col gap-2">
          {papers.map((p) => (
            <Link
              key={p.work_id}
              href={`/paper/${encodeURIComponent(p.work_id)}`}
              className="group flex items-start gap-3 rounded-[8px] border border-border bg-surface p-2.5 transition-colors duration-[var(--motion-fast)] hover:border-primary/40"
              style={{ transitionTimingFunction: "var(--ease-standard)" }}
            >
              <div className="min-w-0 flex-1">
                <p className="font-body text-[13px] font-medium leading-snug text-text-primary">
                  <MathText text={p.title || "Untitled"} />
                </p>
                {(p.authors.length > 0 || p.year > 0) && (
                  <p className="mt-0.5 truncate font-body text-[12px] text-text-secondary">
                    {p.authors.slice(0, 3).join(", ")}
                    {p.authors.length > 3 ? " et al." : ""}
                    {p.year ? `${p.authors.length ? " · " : ""}${p.year}` : ""}
                  </p>
                )}
                {p.why && (
                  <p className="mt-0.5 font-mono text-[10.5px] uppercase tracking-wide text-text-muted">
                    {p.why}
                  </p>
                )}
              </div>
              <ArrowUpRight
                size={14}
                className="mt-0.5 shrink-0 text-text-muted opacity-0 transition-opacity group-hover:opacity-100"
              />
            </Link>
          ))}
        </div>
      )}
    </Card>
  );
}
