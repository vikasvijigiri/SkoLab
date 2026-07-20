"use client";

import { Suspense, useEffect, useState } from "react";
import Link from "next/link";
import { useSearchParams } from "next/navigation";
import { motion, AnimatePresence } from "framer-motion";
import { Search, SearchX, Flame, Trophy } from "lucide-react";
import { Input } from "@/components/ui/Input";
import { Card } from "@/components/ui/Card";
import { Badge, Chip } from "@/components/ui/Badge";
import { ErrorBanner } from "@/components/ui/ErrorBanner";
import { RailShell } from "@/components/layout/RailShell";
import { MathText } from "@/components/ui/MathText";
import { cn } from "@/lib/utils";
import { useDebounce } from "@/lib/hooks/useDebounce";
import { getAuthorSuggestions, getLeaderboard } from "@/lib/api/endpoints";
import type { AuthorSuggestion, OpenAlexWork, LeaderboardEntry } from "@/lib/types";
import { TRANSITION_FAST, DURATION_NORMAL, EASE_STANDARD } from "@/lib/motion";

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

function AuthorResultCard({ a, index }: { a: AuthorSuggestion; index: number }) {
  return (
    <motion.div
      initial={{ opacity: 0, y: 12 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ duration: DURATION_NORMAL, delay: Math.min(index * 0.05, 0.3), ease: EASE_STANDARD }}
    >
      <Link href={`/author/${encodeURIComponent(a.id)}?name=${encodeURIComponent(a.display_name)}&focus=${encodeURIComponent(a.field_of_study ?? "")}`}>
        <Card glow interactive className="flex h-full items-center gap-3">
          <div className="flex h-11 w-11 shrink-0 items-center justify-center rounded-full bg-primary font-display text-[14px] font-bold text-text-on-primary">
            {a.display_name.slice(0, 1).toUpperCase()}
          </div>
          <div className="min-w-0 flex-1">
            <p className="truncate font-body text-[14px] font-semibold text-text-primary">{a.display_name}</p>
            <p className="truncate font-body text-[12.5px] text-text-secondary">
              {a.institution}
              {a.field_of_study ? ` · ${a.field_of_study}` : ""}
            </p>
          </div>
          <div className="flex shrink-0 flex-col items-end gap-1">
            {a.h_index !== undefined && (
              <Badge accentColor="var(--primary)">H-{a.h_index}</Badge>
            )}
          </div>
        </Card>
      </Link>
    </motion.div>
  );
}

function PaperResultCard({ w, index }: { w: OpenAlexWork; index: number }) {
  const shortId = w.id.split("/").pop() ?? w.id;
  return (
    <motion.div
      initial={{ opacity: 0, y: 12 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ duration: DURATION_NORMAL, delay: Math.min(index * 0.05, 0.3), ease: EASE_STANDARD }}
    >
      <Link href={`/paper/${encodeURIComponent(shortId)}`}>
        <Card glow interactive accentColor="var(--accent-cyan)" className="flex h-full flex-col gap-1.5">
          <p className="font-display text-[14.5px] font-semibold leading-snug text-text-primary">
            <MathText text={w.display_name} />
          </p>
          <p className="font-body text-[12.5px] text-text-secondary">
            {w.authorships?.slice(0, 3).map((a) => a.author.display_name).join(", ")}
            {w.publication_year ? ` · ${w.publication_year}` : ""}
            {w.primary_location?.source?.display_name ? ` · ${w.primary_location.source.display_name}` : ""}
          </p>
          {w.cited_by_count !== undefined && (
            <Badge accentColor="var(--accent-cyan)" className="w-fit">
              {w.cited_by_count} citations
            </Badge>
          )}
        </Card>
      </Link>
    </motion.div>
  );
}

const MEDAL: Record<number, string> = {
  1: "linear-gradient(135deg, #fbbf24, #d97706)",
  2: "linear-gradient(135deg, #e2e8f0, #94a3b8)",
  3: "linear-gradient(135deg, #f0a878, #c2703d)",
};

function LeaderboardRow({ entry, index }: { entry: LeaderboardEntry; index: number }) {
  const medal = MEDAL[entry.rank];
  return (
    <motion.div
      initial={{ opacity: 0, y: 12 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ duration: DURATION_NORMAL, delay: Math.min(index * 0.05, 0.3), ease: EASE_STANDARD }}
    >
      {/* Deep-links straight to the author by id — a name-based search here can
          resolve to the wrong same-initial person when names collide. */}
      <Link href={`/author/${encodeURIComponent(entry.id)}?name=${encodeURIComponent(entry.user_name)}`}>
        <Card glow interactive className="flex h-full items-center gap-3">
          <div className="relative shrink-0">
            <div
              className="flex h-11 w-11 items-center justify-center rounded-full font-display text-[14px] font-bold text-white"
              style={{ background: medal ?? "var(--primary)" }}
            >
              {entry.user_name.slice(0, 1).toUpperCase()}
            </div>
            <div
              className="absolute -bottom-1 -right-1 flex h-5 w-5 items-center justify-center rounded-full border-2 border-surface font-mono text-[10px] font-bold text-white"
              style={{ background: medal ?? "var(--text-muted)" }}
            >
              {entry.rank}
            </div>
          </div>
          <div className="min-w-0 flex-1">
            <p className="truncate font-body text-[14px] font-semibold text-text-primary">{entry.user_name}</p>
            <p className="truncate font-body text-[12.5px] text-text-secondary">{entry.institution}</p>
          </div>
          <Badge accentColor="var(--primary)">{entry.entropy_score} pts</Badge>
        </Card>
      </Link>
    </motion.div>
  );
}

export default function DiscoveryPage() {
  return (
    <Suspense fallback={null}>
      <DiscoveryContent />
    </Suspense>
  );
}

function DiscoveryContent() {
  const searchParams = useSearchParams();
  const [mode, setMode] = useState<Mode>(searchParams.get("tab") === "papers" ? "papers" : "researchers");
  const [query, setQuery] = useState("");
  const debouncedQuery = useDebounce(query, 300);

  const [authors, setAuthors] = useState<AuthorSuggestion[]>([]);
  const [papers, setPapers] = useState<OpenAlexWork[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const hasQuery = debouncedQuery.trim().length >= 3;

  // ---- Default (no-query) content: trending papers / top researchers ----------
  const [activeField, setActiveField] = useState<string | null>(null);
  const [leaderboard, setLeaderboard] = useState<LeaderboardEntry[]>([]);
  const [trending, setTrending] = useState<OpenAlexWork[]>([]);
  const [defaultLoading, setDefaultLoading] = useState(true);
  const [defaultError, setDefaultError] = useState<string | null>(null);

  useEffect(() => {
    if (hasQuery) return;
    let cancelled = false;

    (async () => {
      setDefaultLoading(true);
      setDefaultError(null);
      try {
        if (mode === "researchers") {
          const res = await getLeaderboard(activeField ?? "all");
          if (!cancelled) setLeaderboard(res);
        } else {
          const url = activeField
            ? `/api/openalex/works?focus=${encodeURIComponent(activeField)}`
            : "/api/openalex/works";
          const res = await fetch(url);
          if (!res.ok) throw new Error(`HTTP ${res.status}`);
          const data = await res.json();
          if (!cancelled) setTrending(Array.isArray(data) ? data : []);
        }
      } catch (err) {
        if (!cancelled) {
          setDefaultError((err as Error).message || "Couldn't load right now.");
          setLeaderboard([]);
          setTrending([]);
        }
      } finally {
        if (!cancelled) setDefaultLoading(false);
      }
    })();

    return () => {
      cancelled = true;
    };
  }, [mode, activeField, hasQuery]);

  useEffect(() => {
    if (!hasQuery) return;
    let cancelled = false;

    (async () => {
      setLoading(true);
      setError(null);
      try {
        if (mode === "researchers") {
          const res = await getAuthorSuggestions(debouncedQuery);
          if (!cancelled) setAuthors(res);
        } else {
          const res = await fetch(`/api/openalex/works?q=${encodeURIComponent(debouncedQuery)}`);
          if (!res.ok) throw new Error(`Search failed (HTTP ${res.status})`);
          const data = await res.json();
          if (!cancelled) setPapers(Array.isArray(data) ? data : []);
        }
      } catch (err) {
        if (!cancelled) setError((err as Error).message || "Couldn't complete the search. Try again.");
      } finally {
        if (!cancelled) setLoading(false);
      }
    })();

    return () => {
      cancelled = true;
    };
  }, [debouncedQuery, mode, hasQuery]);

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
              {defaultError && (
                <motion.div initial={{ opacity: 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0 }}>
                  <ErrorBanner message={defaultError} />
                </motion.div>
              )}
            </AnimatePresence>

            <div className={resultsGridClass}>
              {defaultLoading &&
                [0, 1, 2, 3].map((i) => (
                  <div key={i} className="h-16 animate-pulse rounded-[8px] bg-surface-subtle" />
                ))}

              {!defaultLoading &&
                !defaultError &&
                mode === "researchers" &&
                leaderboard.map((entry, i) => (
                  <LeaderboardRow key={entry.id} entry={entry} index={i} />
                ))}

              {!defaultLoading &&
                !defaultError &&
                mode === "papers" &&
                trending.map((w, i) => <PaperResultCard key={w.id} w={w} index={i} />)}
            </div>

            {!defaultLoading &&
              !defaultError &&
              ((mode === "researchers" && leaderboard.length === 0) ||
                (mode === "papers" && trending.length === 0)) && (
                <div className="flex flex-col items-center gap-2 py-10 text-center">
                  <SearchX size={26} className="text-text-muted" />
                  <p className="font-body text-[13.5px] text-text-muted">Nothing here yet for this field.</p>
                </div>
              )}
          </div>
        )}

        <AnimatePresence>
          {error && (
            <motion.div initial={{ opacity: 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0 }}>
              <ErrorBanner message={error} />
            </motion.div>
          )}
        </AnimatePresence>

        <AnimatePresence>
          {hasQuery && (
            <motion.div initial={{ opacity: 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0 }} className="flex flex-col gap-3">
              <div className={resultsGridClass}>
                {loading &&
                  [0, 1, 2].map((i) => (
                    <div key={i} className="h-16 animate-pulse rounded-[8px] bg-surface-subtle" />
                  ))}

                {!loading &&
                  mode === "researchers" &&
                  authors.map((a, i) => <AuthorResultCard key={a.id} a={a} index={i} />)}
                {!loading &&
                  mode === "papers" &&
                  papers.map((w, i) => <PaperResultCard key={w.id} w={w} index={i} />)}
              </div>

              {!loading &&
                !error &&
                ((mode === "researchers" && authors.length === 0) ||
                  (mode === "papers" && papers.length === 0)) && (
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
