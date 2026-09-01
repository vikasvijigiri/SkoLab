import type { RefObject } from "react";
import { motion, AnimatePresence } from "framer-motion";
import { BookOpen, Search, Plus, Trash2, Loader2 } from "lucide-react";
import { Card } from "@/components/ui/Card";
import { Input } from "@/components/ui/Input";
import { Badge } from "@/components/ui/Badge";
import { MathText } from "@/components/ui/MathText";
import { cn } from "@/lib/utils";
import { TRANSITION_FAST } from "@/lib/motion";
import type { NexusCollectionPaper, OpenAlexWork } from "@/lib/types";

interface Props {
  activeCollection: NexusCollectionPaper[];
  searchQuery: string;
  searchResults: OpenAlexWork[];
  searching: boolean;
  resultsDismissed: boolean;
  searchContainerRef: RefObject<HTMLDivElement | null>;
  mobileHidden: boolean;
  onSearchChange: (v: string) => void;
  onAddPaper: (paper: OpenAlexWork) => void;
  onRemovePaper: (id: string) => void;
}

export function NexusCollectionPanel({
  activeCollection,
  searchQuery,
  searchResults,
  searching,
  resultsDismissed,
  searchContainerRef,
  mobileHidden,
  onSearchChange,
  onAddPaper,
  onRemovePaper,
}: Props) {
  return (
    <aside
      className={cn(
        "flex w-full flex-col border-r border-border bg-surface-subtle/40 md:flex md:w-[380px] lg:w-[420px] shrink-0",
        mobileHidden && "hidden md:flex"
      )}
    >
      <div className="flex flex-col gap-3 p-4 border-b border-border">
        <div className="flex items-center gap-2">
          <div className="flex h-7 w-7 items-center justify-center rounded bg-accent-purple/10 text-accent-purple">
            <BookOpen size={15} />
          </div>
          <span className="font-display text-[14px] font-bold text-text-primary">
            Synthesis Collection ({activeCollection.length})
          </span>
        </div>
        <p className="font-body text-[12.5px] leading-snug text-text-muted">
          Search and add papers from the OpenAlex global database.
        </p>

        <div className="relative mt-1" ref={searchContainerRef}>
          <Input
            leadingIcon={searching ? <Loader2 size={15} className="animate-spin text-primary" /> : <Search size={15} />}
            placeholder="Search literature to add..."
            value={searchQuery}
            onChange={(e) => onSearchChange(e.target.value)}
            className="h-10 text-[13px]"
          />

          <AnimatePresence>
            {searchResults.length > 0 && !resultsDismissed && (
              <motion.div
                initial={{ opacity: 0, y: 10 }}
                animate={{ opacity: 1, y: 0 }}
                exit={{ opacity: 0, y: 10 }}
                className="absolute left-0 right-0 top-11 z-50 max-h-72 overflow-y-auto rounded-md border border-border bg-surface p-1 shadow-elevated"
              >
                {searchResults.map((paper) => {
                  const shortId = paper.id.split("/").pop() ?? paper.id;
                  const isAdded = activeCollection.some((p) => p.id === shortId);
                  return (
                    <div
                      key={paper.id}
                      role="button"
                      tabIndex={isAdded ? -1 : 0}
                      aria-disabled={isAdded}
                      onClick={() => !isAdded && onAddPaper(paper)}
                      onKeyDown={(e) => {
                        if ((e.key === "Enter" || e.key === " ") && !isAdded) {
                          e.preventDefault();
                          onAddPaper(paper);
                        }
                      }}
                      className={`flex items-start justify-between gap-3 rounded p-2.5 text-left transition-colors font-body text-[12.5px] focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-primary ${
                        isAdded ? "bg-surface-subtle opacity-50 cursor-not-allowed" : "cursor-pointer hover:bg-surface-subtle"
                      }`}
                    >
                      <div className="min-w-0 flex-1">
                        <p className="font-semibold text-text-primary line-clamp-2 leading-snug">
                          <MathText text={paper.display_name} />
                        </p>
                        <p className="text-[11.5px] text-text-muted mt-1">
                          {paper.authorships?.slice(0, 2).map((a) => a.author.display_name).join(", ")}
                          {paper.publication_year ? ` · ${paper.publication_year}` : ""}
                        </p>
                      </div>
                      <motion.button
                        type="button"
                        whileHover={isAdded ? undefined : { scale: 1.12 }}
                        whileTap={isAdded ? undefined : { scale: 0.9 }}
                        transition={TRANSITION_FAST}
                        className="flex h-6 w-6 items-center justify-center rounded-full bg-primary/10 text-primary transition-colors duration-[var(--motion-fast)] hover:bg-primary hover:text-text-on-primary disabled:cursor-not-allowed"
                        disabled={isAdded}
                      >
                        <Plus size={13} />
                      </motion.button>
                    </div>
                  );
                })}
              </motion.div>
            )}
          </AnimatePresence>
        </div>
      </div>

      <div className="flex-1 overflow-y-auto p-4 flex flex-col gap-3">
        {activeCollection.length === 0 ? (
          <div className="flex flex-col items-center justify-center text-center py-20 px-4">
            <div className="h-12 w-12 rounded-full border border-dashed border-border flex items-center justify-center text-text-muted mb-4">
              <BookOpen size={20} />
            </div>
            <p className="font-body text-[13px] text-text-muted">
              Your workspace collection is empty. Search and add studies to construct your workspace.
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
                <Card className="border-border/30 bg-surface/80 shadow-sm relative group p-3 flex flex-col gap-1 pr-8">
                  <motion.button
                    onClick={() => onRemovePaper(p.id)}
                    whileHover={{ scale: 1.12 }}
                    whileTap={{ scale: 0.9 }}
                    transition={TRANSITION_FAST}
                    className="absolute right-2 top-2 h-6 w-6 rounded flex items-center justify-center text-text-muted transition-colors duration-[var(--motion-fast)] hover:bg-notification/10 hover:text-notification opacity-0 group-hover:opacity-100 focus:opacity-100"
                  >
                    <Trash2 size={13} />
                  </motion.button>
                  <h4 className="font-display text-[13px] font-semibold text-text-primary line-clamp-2 leading-snug">
                    <MathText text={p.title} />
                  </h4>
                  <p className="font-body text-[11.5px] text-text-secondary truncate mt-0.5">{p.authors.join(", ")}</p>
                  <div className="flex items-center gap-1.5 mt-2">
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
