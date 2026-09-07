"use client";

import { useMemo, useState } from "react";
import Link from "next/link";
import { motion } from "framer-motion";
import { useQuery } from "@tanstack/react-query";
import { ArrowRight, ChevronRight } from "lucide-react";
import { Chip } from "@/components/ui/Badge";
import {
  openAlexFieldsQuery,
  openAlexSubfieldsQuery,
  openAlexTopicsQuery,
  discoveryAuthorsQuery,
} from "@/lib/api/queries";
import { EASE_STANDARD } from "@/lib/motion";
import type { OpenAlexAuthorHit, OpenAlexTaxon } from "@/lib/types";

/**
 * Ungated "try it" hero demo — the product thesis made tangible: the visitor
 * never types, they click a field → sub-field → topic → researcher, and a real
 * impact signature renders from public OpenAlex data. No signup, no backend
 * metrics call (OpenAlexAuthorHit already carries the numbers), nothing
 * hardcoded — every level is whatever OpenAlex returns first. (OpenAlex's
 * /authors endpoint only filters by topics.id, not field/subfield, so the
 * drilldown goes one level deeper than Discovery's paper view.)
 */

const AXES = [
  { key: "reach", label: "Reach" },
  { key: "output", label: "Output" },
  { key: "selectivity", label: "Selectivity" },
  { key: "hindex", label: "h-index" },
] as const;

/** Map a raw OpenAlex author record to 0..1 per axis. Log scales for the
 *  heavy-tailed counts; fixed reference ceilings so the shape is comparable
 *  across researchers. A small floor keeps the polygon from collapsing. */
function signature(a: OpenAlexAuthorHit): number[] {
  const log = (v: number, ceilLog10: number) =>
    Math.min(1, Math.max(0.08, Math.log10(Math.max(1, v)) / ceilLog10));
  const perWork = a.works_count > 0 ? a.cited_by_count / a.works_count : 0;
  return [
    log(a.cited_by_count, 5.5), // ~316k citations = full
    log(a.works_count, 3.0), // ~1000 works = full
    Math.min(1, Math.max(0.08, perWork / 120)),
    Math.min(1, Math.max(0.08, a.h_index / 120)),
  ];
}

function point(i: number, r: number, cx: number, cy: number, n: number) {
  const angle = (Math.PI * 2 * i) / n - Math.PI / 2;
  return [cx + Math.cos(angle) * r, cy + Math.sin(angle) * r] as const;
}

function Radar({ values }: { values: number[] }) {
  const cx = 100;
  const cy = 100;
  const maxR = 78;
  const n = values.length;
  const rings = [0.33, 0.66, 1];
  const poly = values.map((v, i) => point(i, v * maxR, cx, cy, n).join(",")).join(" ");

  return (
    <svg viewBox="0 0 200 200" className="h-full w-full" aria-hidden>
      {rings.map((rr) => (
        <polygon
          key={rr}
          points={Array.from({ length: n }, (_, i) => point(i, rr * maxR, cx, cy, n).join(",")).join(" ")}
          fill="none"
          stroke="var(--border-color)"
          strokeWidth="1"
        />
      ))}
      {Array.from({ length: n }, (_, i) => {
        const [x, y] = point(i, maxR, cx, cy, n);
        return <line key={i} x1={cx} y1={cy} x2={x} y2={y} stroke="var(--border-color)" strokeWidth="1" />;
      })}
      <motion.polygon
        key={poly}
        points={poly}
        fill="color-mix(in srgb, var(--primary) 20%, transparent)"
        stroke="var(--primary)"
        strokeWidth="2"
        initial={{ opacity: 0, scale: 0.7 }}
        animate={{ opacity: 1, scale: 1 }}
        transition={{ duration: 0.5, ease: EASE_STANDARD }}
        style={{ transformOrigin: "center" }}
      />
    </svg>
  );
}

function ChipRow({
  items,
  activeId,
  onPick,
  loading,
}: {
  items: OpenAlexTaxon[];
  activeId?: string;
  onPick: (t: OpenAlexTaxon) => void;
  loading: boolean;
}) {
  if (loading) {
    return (
      <div className="flex flex-wrap gap-2">
        {[0, 1, 2, 3].map((i) => (
          <div key={i} className="h-6 w-20 animate-pulse rounded-full bg-surface-subtle" />
        ))}
      </div>
    );
  }
  return (
    <div className="flex flex-wrap gap-2">
      {items.slice(0, 8).map((t) => (
        <Chip key={t.id} selected={t.id === activeId} onClick={() => onPick(t)}>
          {t.display_name}
        </Chip>
      ))}
    </div>
  );
}

export function LandingTryDemo() {
  // Only the *explicit* picks are state; the effective field / sub-field fall
  // back to whatever OpenAlex returns first, so the demo shows a live list at
  // rest with no setState-in-effect and nothing hardcoded.
  const [pickedField, setPickedField] = useState<OpenAlexTaxon | null>(null);
  const [pickedSub, setPickedSub] = useState<OpenAlexTaxon | null>(null);
  const [pickedTopic, setPickedTopic] = useState<OpenAlexTaxon | null>(null);
  const [pickedAuthor, setPickedAuthor] = useState<OpenAlexAuthorHit | null>(null);

  const fieldsQ = useQuery(openAlexFieldsQuery());
  const field = pickedField ?? fieldsQ.data?.[0] ?? null;

  const subfieldsQ = useQuery(openAlexSubfieldsQuery(field?.id));
  const subfield = pickedSub ?? subfieldsQ.data?.[0] ?? null;

  const topicsQ = useQuery(openAlexTopicsQuery(subfield?.id));
  const topic = pickedTopic ?? topicsQ.data?.[0] ?? null;

  const authorsQ = useQuery({
    ...discoveryAuthorsQuery("topic", topic?.id),
    enabled: Boolean(topic?.id),
  });

  const authors = authorsQ.data ?? [];
  const active = pickedAuthor ?? authors[0] ?? null;
  const values = useMemo(() => (active ? signature(active) : AXES.map(() => 0.1)), [active]);

  const tiles = active
    ? [
        { label: "Citations", value: fmt(active.cited_by_count) },
        { label: "Works", value: fmt(active.works_count) },
        { label: "h-index", value: String(active.h_index) },
      ]
    : [];

  return (
    <motion.div
      initial={{ opacity: 0, y: 24 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ duration: 0.6, delay: 0.3, ease: EASE_STANDARD }}
      className="mx-auto mt-12 w-full max-w-3xl overflow-hidden rounded-lg border border-border bg-surface text-left shadow-elevated"
      style={{ borderTop: "2px solid var(--primary)" }}
    >
      <div className="flex items-center gap-2 border-b border-border px-4 py-2">
        <span className="font-mono text-[10px] font-semibold uppercase tracking-wide text-text-muted">
          Try it — no sign-up, no typing
        </span>
      </div>

      <div className="grid grid-cols-1 gap-4 p-4 sm:grid-cols-[1fr_220px] sm:p-5">
        {/* click path */}
        <div className="flex flex-col gap-3">
          <div>
            <p className="mb-2 font-body text-[10.5px] font-semibold uppercase tracking-wide text-text-muted">
              Pick a field
            </p>
            <ChipRow
              items={fieldsQ.data ?? []}
              activeId={field?.id}
              loading={fieldsQ.isPending}
              onPick={(t) => {
                setPickedField(t);
                setPickedSub(null);
                setPickedTopic(null);
                setPickedAuthor(null);
              }}
            />
          </div>

          {field && (
            <div>
              <p className="mb-2 flex items-center gap-1 font-body text-[10.5px] font-semibold uppercase tracking-wide text-text-muted">
                <ChevronRight size={11} />
                Narrow it
              </p>
              <ChipRow
                items={subfieldsQ.data ?? []}
                activeId={subfield?.id}
                loading={subfieldsQ.isPending}
                onPick={(t) => {
                  setPickedSub(t);
                  setPickedTopic(null);
                  setPickedAuthor(null);
                }}
              />
            </div>
          )}

          {subfield && (
            <div>
              <p className="mb-2 flex items-center gap-1 font-body text-[10.5px] font-semibold uppercase tracking-wide text-text-muted">
                <ChevronRight size={11} />
                Pick a topic
              </p>
              <ChipRow
                items={topicsQ.data ?? []}
                activeId={topic?.id}
                loading={topicsQ.isPending}
                onPick={(t) => {
                  setPickedTopic(t);
                  setPickedAuthor(null);
                }}
              />
            </div>
          )}

          {topic && (
            <div>
              <p className="mb-2 flex items-center gap-1 font-body text-[10.5px] font-semibold uppercase tracking-wide text-text-muted">
                <ChevronRight size={11} />
                Pick a researcher
              </p>
              {authorsQ.isPending ? (
                <div className="flex flex-wrap gap-2">
                  {[0, 1, 2, 3].map((i) => (
                    <div key={i} className="h-6 w-28 animate-pulse rounded-full bg-surface-subtle" />
                  ))}
                </div>
              ) : (
                <div className="flex max-h-[92px] flex-wrap gap-2 overflow-y-auto">
                  {authors.slice(0, 8).map((a) => (
                    <Chip
                      key={a.id}
                      selected={active?.id === a.id}
                      onClick={() => setPickedAuthor(a)}
                    >
                      {a.display_name}
                    </Chip>
                  ))}
                </div>
              )}
            </div>
          )}
        </div>

        {/* signature */}
        <div className="flex flex-col items-center gap-2 rounded-md border border-border bg-surface-subtle/40 p-3">
          <p className="self-start font-body text-[10px] font-semibold uppercase tracking-wide text-text-muted">
            Impact signature
          </p>
          <div className="h-[150px] w-[150px]">
            <Radar values={values} />
          </div>
          {active ? (
            <>
              <p className="line-clamp-1 text-center font-display text-[12.5px] font-semibold text-text-primary">
                {active.display_name}
              </p>
              <div className="grid w-full grid-cols-3 gap-1">
                {tiles.map((t) => (
                  <div key={t.label} className="rounded-md border border-border bg-surface p-1.5 text-center">
                    <p className="font-mono text-[12px] font-bold text-text-primary">{t.value}</p>
                    <p className="font-body text-[8px] uppercase tracking-wide text-text-muted">{t.label}</p>
                  </div>
                ))}
              </div>
            </>
          ) : (
            <p className="text-center font-body text-[11px] text-text-muted">
              Pick a researcher to see their signature.
            </p>
          )}
        </div>
      </div>

      <div className="flex items-center justify-between border-t border-border bg-surface-subtle/40 px-4 py-3">
        <p className="font-body text-[11px] text-text-muted">
          Public-data snapshot. The full profile adds 8 AI-scored axes — disruption, novelty, influence…
        </p>
        <Link
          href="/signup"
          className="flex shrink-0 items-center gap-1 font-body text-[12px] font-semibold text-primary hover:underline"
        >
          Full profile <ArrowRight size={13} />
        </Link>
      </div>
    </motion.div>
  );
}

function fmt(n: number): string {
  if (n >= 1_000_000) return `${(n / 1_000_000).toFixed(1)}M`;
  if (n >= 1_000) return `${(n / 1_000).toFixed(1)}k`;
  return String(n);
}
