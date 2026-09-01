"use client";

import { useState } from "react";
import { useSearchParams } from "next/navigation";
import { motion, AnimatePresence } from "framer-motion";
import { useQuery } from "@tanstack/react-query";
import { Search, SearchX, Flame, Trophy } from "lucide-react";
import { Input } from "@/components/ui/Input";
import { Chip } from "@/components/ui/Badge";
import { ErrorBanner } from "@/components/ui/ErrorBanner";
import { RailShell } from "@/components/layout/RailShell";
import { cn } from "@/lib/utils";
import { useDebounce } from "@/lib/hooks/useDebounce";
import {
  authorSuggestionsQuery,
  leaderboardQuery,
  openAlexWorksQuery,
} from "@/lib/api/queries";
import { AuthorResultCard } from "@/components/discovery/AuthorResultCard";
import { PaperResultCard } from "@/components/discovery/PaperResultCard";
import { LeaderboardRow } from "@/components/discovery/LeaderboardRow";
import { TRANSITION_FAST } from "@/lib/motion";

type Mode = "researchers" | "papers";

const POPULAR_FIELDS = [
  "Physics",
  "Computer Science",
  "Biology",
  "Chemistry",
  "Medicine",
  "Mathematics",
  "Engineering",
  "Neuroscience",
];

export default function DiscoveryPage() {
  return <DiscoveryContent />;
}

/** Exported for unit tests — the default export is only the Suspense-free wrapper. */
export function DiscoveryContent() {
  const searchParams = useSearchParams();
  const [mode, setMode] = useState<Mode>(searchParams.get("tab") === "papers" ? "papers" : "researchers");
  const [query, setQuery] = useState("");
  const [activeField, setActiveField] = useState<string | null>(null);
  const debouncedQuery = useDebounce(query, 300);
  const hasQuery = debouncedQuery.trim().length >= 3;

  // ── Default (no-query) content: leaderboard / trending papers ──────────────
  const leaderboard = useQuery({
    ...leaderboardQuery(activeField ?? "all"),
    enabled: !hasQuery && mode === "researchers",
  });
  const trending = useQuery({
    ...openAlexWorksQuery({ focus: activeField ?? undefined }),
    enabled: !hasQuery && mode === "papers",
  });

  // ── Query results: author suggestions / paper search ─────────────────────
  const authors = useQuery({
    ...authorSuggestionsQuery(debouncedQuery),
    enabled: hasQuery && mode === "researchers",
  });
  const papers = useQuery({
    ...openAlexWorksQuery({ q: debouncedQuery }),
    enabled: hasQuery && mode === "papers",
  });

  const defaultActive = mode === "researchers" ? leaderboard : trending;
  const searchActive = mode === "researchers" ? authors : papers;
  const defaultRows = mode === "researchers" ? (leaderboard.data ?? []) : (trending.data ?? []);
  const searchRows = mode === "researchers" ? (authors.data ?? []) : (papers.data ?? []);

  const railContent = (
    <div className="flex flex-col gap-4">
      <div className="flex gap-1 rounded-full bg-surface-subtle p-1">
        {(["researchers", "papers"] as const).map((m) => (
          <motion.button
            key={m}
            onClick={() => setMode(m)}
            whileTap={{ scale: 0.96 }}
            transition={TRANSITION_FAST}
            className={cn(
              "relative flex-1 rounded-full py-2 font-body text-[13px] font-medium capitalize transition-colors duration-[var(--motion-fast)]",
              mode === m
                ? "text-text-on-primary"
                : "text-text-secondary hover:bg-surface/60 hover:text-text-primary"
            )}
            style={{ transitionTimingFunction: "var(--ease-standard)" }}
          >
            {mode === m && (
              <motion.span
                layoutId="discovery-mode-pill"
                className="absolute inset-0 rounded-full bg-primary"
                transition={{ type: "spring", stiffness: 400, damping: 32 }}
              />
            )}
            <span className="relative z-10">{m}</span>
          </motion.button>
        ))}
      </div>

      <Input
        leadingIcon={<Search size={16} />}
        placeholder={mode === "researchers" ? "Search researchers by name..." : "Search papers by title..."}
        value={query}
        onChange={(e) => setQuery(e.target.value)}
      />

      {query.trim().length > 0 && query.trim().length < 3 && (
        <p className="font-body text-[12.5px] text-text-muted">Keep typing — at least 3 characters.</p>
      )}

      {!hasQuery && (
        <div className="flex flex-wrap gap-2 lg:flex-col lg:items-stretch lg:gap-1.5">
          {POPULAR_FIELDS.map((field) => {
            const isActive = activeField === field;
            return (
              <Chip
                key={field}
                selected={isActive}
                onClick={() => setActiveField(isActive ? null : field)}
                className="lg:justify-start"
              >
                {field}
              </Chip>
            );
          })}
        </div>
      )}
    </div>
  );

  const resultsGridClass = "grid grid-cols-1 gap-2.5 lg:grid-cols-2 xl:grid-cols-3";

  return (
    <div className="mx-auto flex max-w-2xl flex-col gap-4 px-4 py-6 md:px-8 lg:max-w-6xl">
      <motion.h1
        initial={{ opacity: 0, y: -8 }}
        animate={{ opacity: 1, y: 0 }}
        transition={{ duration: 0.4 }}
        className="font-display text-[22px] font-bold text-text-primary"
      >
        Discovery
      </motion.h1>

      <RailShell rail={railContent} railWidth="260px" mobileRail="collapsible" stickyRail>
        {/* ---- Default (no-query) state: trending / leaderboard ---- */}
        {!hasQuery && (
          <div className="flex flex-col gap-3">
            <div className="flex items-center gap-1.5 text-text-secondary">
              {mode === "researchers" ? <Trophy size={14} /> : <Flame size={14} />}
              <span className="font-body text-[12.5px] font-semibold uppercase tracking-wide">
                {mode === "researchers"
                  ? activeField
                    ? `Top in ${activeField}`
                    : "Top Researchers"
                  : activeField
                    ? `Trending in ${activeField}`
                    : "Trending This Year"}
              </span>
            </div>

            <AnimatePresence>
              {defaultActive.isError && (
                <motion.div initial={{ opacity: 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0 }}>
                  <ErrorBanner message="Couldn't load right now." onRetry={() => defaultActive.refetch()} />
                </motion.div>
              )}
            </AnimatePresence>

            <div className={resultsGridClass}>
              {defaultActive.isPending &&
                [0, 1, 2, 3].map((i) => (
                  <div key={i} className="h-16 animate-pulse rounded-[8px] bg-surface-subtle" />
                ))}

              {!defaultActive.isPending &&
                !defaultActive.isError &&
                mode === "researchers" &&
                (leaderboard.data ?? []).map((entry, i) => (
                  <LeaderboardRow key={entry.id} entry={entry} index={i} />
                ))}

              {!defaultActive.isPending &&
                !defaultActive.isError &&
                mode === "papers" &&
                (trending.data ?? []).map((w, i) => <PaperResultCard key={w.id} w={w} index={i} />)}
            </div>

            {!defaultActive.isPending && !defaultActive.isError && defaultRows.length === 0 && (
              <div className="flex flex-col items-center gap-2 py-10 text-center">
                <SearchX size={26} className="text-text-muted" />
                <p className="font-body text-[13.5px] text-text-muted">Nothing here yet for this field.</p>
              </div>
            )}
          </div>
        )}

        <AnimatePresence>
          {searchActive.isError && hasQuery && (
            <motion.div initial={{ opacity: 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0 }}>
              <ErrorBanner
                message="Couldn't complete the search. Try again."
                onRetry={() => searchActive.refetch()}
              />
            </motion.div>
          )}
        </AnimatePresence>

        <AnimatePresence>
          {hasQuery && (
            <motion.div initial={{ opacity: 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0 }} className="flex flex-col gap-3">
              <div className={resultsGridClass}>
                {searchActive.isPending &&
                  [0, 1, 2].map((i) => (
                    <div key={i} className="h-16 animate-pulse rounded-[8px] bg-surface-subtle" />
                  ))}

                {!searchActive.isPending &&
                  mode === "researchers" &&
                  (authors.data ?? []).map((a, i) => <AuthorResultCard key={a.id} a={a} index={i} />)}
                {!searchActive.isPending &&
                  mode === "papers" &&
                  (papers.data ?? []).map((w, i) => <PaperResultCard key={w.id} w={w} index={i} />)}
              </div>

              {!searchActive.isPending && !searchActive.isError && searchRows.length === 0 && (
                <div className="flex flex-col items-center gap-2 py-10 text-center">
                  <SearchX size={26} className="text-text-muted" />
                  <p className="font-body text-[13.5px] text-text-muted">No results found.</p>
                </div>
              )}
            </motion.div>
          )}
        </AnimatePresence>
      </RailShell>
    </div>
  );
}
