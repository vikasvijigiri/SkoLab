import { useState } from "react";
import { motion, AnimatePresence } from "framer-motion";
import { useQuery } from "@tanstack/react-query";
import { BookOpen, Plus, Trash2, ChevronRight } from "lucide-react";
import { Card } from "@/components/ui/Card";
import { Chip } from "@/components/ui/Badge";
import { Badge } from "@/components/ui/Badge";
import { MathText } from "@/components/ui/MathText";
import { cn } from "@/lib/utils";
import { TRANSITION_FAST } from "@/lib/motion";
import {
  openAlexFieldsQuery,
  openAlexSubfieldsQuery,
  openAlexTopicsQuery,
  discoveryWorksQuery,
} from "@/lib/api/queries";
import type { NexusCollectionPaper, OpenAlexWork, OpenAlexTaxon } from "@/lib/types";

interface Props {
  activeCollection: NexusCollectionPaper[];
  mobileHidden: boolean;
  onAddPaper: (paper: OpenAlexWork) => void;
  onRemovePaper: (id: string) => void;
}

/**
 * Click-only paper picker: drill the OpenAlex taxonomy (field → sub-field →
 * topic) and tap papers to add. No search box.
 */
export function NexusCollectionPanel({
  activeCollection,
  mobileHidden,
  onAddPaper,
  onRemovePaper,
}: Props) {
  const [field, setField] = useState<OpenAlexTaxon | null>(null);
  const [subfield, setSubfield] = useState<OpenAlexTaxon | null>(null);
  const [topic, setTopic] = useState<OpenAlexTaxon | null>(null);

  const fieldsQ = useQuery(openAlexFieldsQuery());
  const subfieldsQ = useQuery(openAlexSubfieldsQuery(field?.id));
  const topicsQ = useQuery(openAlexTopicsQuery(subfield?.id));

  const node = topic
    ? ({ level: "topic", id: topic.id } as const)
    : subfield
      ? ({ level: "subfield", id: subfield.id } as const)
      : field
        ? ({ level: "field", id: field.id } as const)
        : null;

  const worksQ = useQuery({
    ...discoveryWorksQuery(node?.level ?? "field", node?.id),
    enabled: Boolean(node),
  });

  const next: { title: string; q: typeof fieldsQ; pick: (t: OpenAlexTaxon) => void } | null = !field
    ? { title: "Pick a field", q: fieldsQ, pick: (t) => { setField(t); setSubfield(null); setTopic(null); } }
    : !subfield
      ? { title: "Narrow it down", q: subfieldsQ, pick: (t) => { setSubfield(t); setTopic(null); } }
      : !topic
        ? { title: "Pick a topic", q: topicsQ, pick: setTopic }
        : null;

  return (
    <aside
      className={cn(
        "flex w-full flex-col border-r border-border bg-surface-subtle/40 md:flex md:w-[380px] lg:w-[420px] shrink-0",
        mobileHidden && "hidden md:flex",
      )}
    >
      <div className="flex flex-col gap-3 border-b border-border p-4">
        <div className="flex items-center gap-2">
          <div className="flex h-7 w-7 items-center justify-center rounded bg-accent-purple/10 text-accent-purple">
            <BookOpen size={15} />
          </div>
          <span className="font-display text-body font-bold text-text-primary">
            Synthesis Collection ({activeCollection.length})
          </span>
        </div>

        <div className="flex flex-wrap items-center gap-1 font-body text-[11.5px] text-text-muted">
          <button
            type="button"
            onClick={() => { setField(null); setSubfield(null); setTopic(null); }}
            className={cn("cursor-pointer hover:text-text-primary", !field && "font-semibold text-text-primary")}
          >
            All fields
          </button>
          {field && (
            <>
              <ChevronRight size={11} />
              <button
                type="button"
                onClick={() => { setSubfield(null); setTopic(null); }}
                className={cn("cursor-pointer hover:text-text-primary", !subfield && "font-semibold text-text-primary")}
              >
                {field.display_name}
              </button>
            </>
          )}
          {subfield && (
            <>
              <ChevronRight size={11} />
              <button
                type="button"
                onClick={() => setTopic(null)}
                className={cn("cursor-pointer hover:text-text-primary", !topic && "font-semibold text-text-primary")}
              >
                {subfield.display_name}
              </button>
            </>
          )}
          {topic && (
            <>
              <ChevronRight size={11} />
              <span className="font-semibold text-text-primary">{topic.display_name}</span>
            </>
          )}
        </div>

        {next && (
          <div>
            <span className="mb-2 block font-body text-[10.5px] font-semibold uppercase tracking-wide text-text-muted">
              {next.title}
            </span>
            {next.q.isPending ? (
              <div className="flex flex-wrap gap-2">
                {[0, 1, 2, 3].map((i) => (
                  <div key={i} className="h-6 w-20 animate-pulse rounded-full bg-surface-subtle" />
                ))}
              </div>
            ) : (
              <div className="flex max-h-32 flex-wrap gap-2 overflow-y-auto">
                {(next.q.data ?? []).map((t) => (
                  <Chip key={t.id} onClick={() => next.pick(t)}>
                    {t.display_name}
                  </Chip>
                ))}
              </div>
            )}
          </div>
        )}

        {node && (
          <div className="flex flex-col gap-2">
            <span className="font-body text-[10.5px] font-semibold uppercase tracking-wide text-text-muted">
              Top papers — tap to add
            </span>
            {worksQ.isPending ? (
              <div className="flex flex-col gap-2">
                {[0, 1, 2].map((i) => (
                  <div key={i} className="h-12 animate-pulse rounded bg-surface-subtle" />
                ))}
              </div>
            ) : (
              <div className="flex max-h-64 flex-col gap-1 overflow-y-auto">
                {(worksQ.data ?? []).map((paper) => {
                  const shortId = paper.id.split("/").pop() ?? paper.id;
                  const isAdded = activeCollection.some((p) => p.id === shortId);
                  return (
                    <button
                      key={paper.id}
                      type="button"
                      disabled={isAdded}
                      onClick={() => onAddPaper(paper)}
                      className={cn(
                        "flex items-start justify-between gap-2 rounded p-2 text-left font-body text-[12px] transition-colors",
                        isAdded ? "cursor-not-allowed bg-surface-subtle opacity-50" : "cursor-pointer hover:bg-surface-subtle",
                      )}
                    >
                      <div className="min-w-0 flex-1">
                        <p className="line-clamp-2 font-semibold leading-snug text-text-primary">
                          <MathText text={paper.display_name} />
                        </p>
                        <p className="mt-1 text-[11px] text-text-muted">
                          {paper.authorships?.slice(0, 2).map((a) => a.author.display_name).join(", ")}
                          {paper.publication_year ? ` · ${paper.publication_year}` : ""}
                        </p>
                      </div>
                      <span className="flex h-6 w-6 shrink-0 items-center justify-center rounded-full bg-primary/10 text-primary">
                        <Plus size={13} />
                      </span>
                    </button>
                  );
                })}
                {(worksQ.data ?? []).length === 0 && (
                  <p className="px-2 py-3 font-body text-[12px] text-text-muted">
                    No papers listed for this node.
                  </p>
                )}
              </div>
            )}
          </div>
        )}
      </div>

      <div className="flex flex-1 flex-col gap-3 overflow-y-auto p-4">
        {activeCollection.length === 0 ? (
          <div className="flex flex-col items-center justify-center px-4 py-20 text-center">
            <div className="mb-4 flex h-12 w-12 items-center justify-center rounded-full border border-dashed border-border text-text-muted">
              <BookOpen size={20} />
            </div>
            <p className="font-body text-body-s text-text-muted">
              Your collection is empty. Drill into a topic above and tap papers to add them.
            </p>
          </div>
        ) : (
          <AnimatePresence>
            {activeCollection.map((p) => (
              <motion.div
                key={p.id}
                layoutId={p.id}
                initial={{ opacity: 0, scale: 0.96 }}
                animate={{ opacity: 1, scale: 1 }}
                exit={{ opacity: 0, scale: 0.96 }}
                transition={{ duration: 0.2 }}
              >
                <Card className="group relative flex flex-col gap-1 border-border/30 bg-surface/80 p-3 pr-8 shadow-sm">
                  <motion.button
                    onClick={() => onRemovePaper(p.id)}
                    whileHover={{ scale: 1.12 }}
                    whileTap={{ scale: 0.9 }}
                    transition={TRANSITION_FAST}
                    className="absolute right-2 top-2 flex h-6 w-6 items-center justify-center rounded text-text-muted opacity-0 transition-colors duration-[var(--motion-fast)] hover:bg-notification/10 hover:text-notification focus:opacity-100 group-hover:opacity-100"
                  >
                    <Trash2 size={13} />
                  </motion.button>
                  <h4 className="line-clamp-2 font-display text-body-s font-semibold leading-snug text-text-primary">
                    <MathText text={p.title} />
                  </h4>
                  <p className="mt-1 truncate font-body text-[11.5px] text-text-secondary">{p.authors.join(", ")}</p>
                  <div className="mt-2 flex items-center gap-2">
                    <Badge accentColor="var(--accent-purple)" className="px-1.5 text-[10px]">
                      {p.year}
                    </Badge>
                    {p.cited_by_count !== undefined && (
                      <Badge accentColor="var(--accent-teal)" className="px-1.5 text-[10px]">
                        {p.cited_by_count} Citations
                      </Badge>
                    )}
                  </div>
                </Card>
              </motion.div>
            ))}
          </AnimatePresence>
        )}
      </div>
    </aside>
  );
}
