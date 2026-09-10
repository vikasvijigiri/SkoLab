"use client";

import { useMemo } from "react";
import Link from "next/link";
import { motion } from "framer-motion";
import { useQuery } from "@tanstack/react-query";
import { Coins, Briefcase, BookOpen, Users2, FolderKanban } from "lucide-react";
import { useAuth } from "@/lib/hooks/AuthProvider";
import { useMyProfile } from "@/lib/hooks/useMyProfile";
import { useFirestoreCollection } from "@/lib/hooks/useFirestoreCollection";
import { subscribeProjects } from "@/lib/firebase/workspace";
import {
  dailyFeedQuery,
  dailyConjectureQuery,
  industryOpportunitiesQuery,
  matchGrantsQuery,
  journalAdvisorQuery,
  similarResearchersQuery,
  activityFeedQuery,
  scienceNewsQuery,
} from "@/lib/api/queries";
import { AIDailyBriefCard, type BriefItem } from "@/components/feed/AIDailyBriefCard";
import { DailyChallengeCard } from "@/components/feed/DailyChallengeCard";
import { PeerSuggestionsCard } from "@/components/feed/PeerSuggestionsCard";
import { UnifiedFeed } from "@/components/feed/UnifiedFeed";
import { IdentityRailCard } from "@/components/feed/IdentityRailCard";
import { Card } from "@/components/ui/Card";
import { ResearchCommandCenter, ResearchLoopNote } from "@/components/product/ResearchCommandCenter";
import type {
  ActivityItem,
  ScienceNewsItem,
  CollabProject,
  DailyFeedItem,
  GrantMatch,
  JournalRecommendation,
  IndustryOpportunity,
} from "@/lib/types";

const EMPTY_FEED: DailyFeedItem[] = [];
const EMPTY_ACTIVITY: ActivityItem[] = [];
const EMPTY_NEWS: ScienceNewsItem[] = [];
const EMPTY_JOBS: IndustryOpportunity[] = [];

/** Each insight is a distinct fact from a distinct source — separate rows, not a
 * run-on paragraph. Papers are deliberately NOT here: they lead the unified
 * feed below, and duplicating feed[0] into the brief is the redundancy the
 * feed was meant to remove. */
function buildBriefItems(opts: {
  topGrant?: GrantMatch;
  topOpportunity?: IndustryOpportunity;
  topJournal?: JournalRecommendation;
}): BriefItem[] {
  const items: BriefItem[] = [];
  if (opts.topGrant) {
    items.push({
      key: "grant",
      icon: Coins,
      color: "var(--accent-emerald)",
      label: "Grant match",
      text: `**${opts.topGrant.title}** (${opts.topGrant.agency}) — ${opts.topGrant.match_score}% fit · ${opts.topGrant.amount}`,
      href: opts.topGrant.url,
    });
  }
  if (opts.topOpportunity) {
    const deadlinePart = opts.topOpportunity.deadline ? ` · Deadline: ${opts.topOpportunity.deadline}` : "";
    const amountPart = opts.topOpportunity.amount ? ` · ${opts.topOpportunity.amount}` : "";
    items.push({
      key: "opportunity",
      icon: Briefcase,
      color: "var(--accent-orange)",
      label: opts.topOpportunity.type === "JOB" ? "Role opened" : "Opportunity",
      text: `**${opts.topOpportunity.title}** at **${opts.topOpportunity.companyOrFunder}**${amountPart}${deadlinePart}`,
      href: opts.topOpportunity.url,
    });
  }
  if (opts.topJournal) {
    items.push({
      key: "journal",
      icon: BookOpen,
      color: "var(--accent-indigo)",
      label: "Journal target",
      text: `**${opts.topJournal.journal_name}** — ${opts.topJournal.match_score}% match`,
    });
  }
  return items;
}

/** Left-rail shortcut list of the user's actual workspaces — data, not nav. */
function WorkspacesRailCard({ uid }: { uid?: string }) {
  const { data: projects, loading } = useFirestoreCollection<CollabProject>(
    uid ? (next, onErr) => subscribeProjects(uid, next, onErr) : null,
    { deps: [uid] },
  );

  if (!uid) return null;

  return (
    <Card className="flex flex-col gap-2">
      <h2 className="eyebrow flex items-center gap-2">
        <FolderKanban size={11} />
        Your workspaces
      </h2>
      {loading ? (
        <div className="flex flex-col gap-2">
          {[0, 1].map((i) => (
            <div key={i} className="h-6 animate-pulse rounded bg-surface-subtle" />
          ))}
        </div>
      ) : projects.length === 0 ? (
        <Link href="/workspace" className="font-body text-[12px] font-medium text-primary hover:underline">
          Start a CoLab →
        </Link>
      ) : (
        <ul className="flex flex-col">
          {projects.slice(0, 5).map((p) => (
            <li key={p.id}>
              <Link
                href={`/workspace/${p.id}`}
                className="block truncate rounded px-2 py-1 font-body text-body-s text-text-secondary transition-colors hover:bg-surface-subtle hover:text-text-primary"
              >
                {p.name}
              </Link>
            </li>
          ))}
        </ul>
      )}
    </Card>
  );
}

export function HomeClient() {
  const { user } = useAuth();
  const {
    firestoreProfile,
    author,
    loading: profileLoading,
    unresolved: profileUnresolved,
  } = useMyProfile();

  const name = firestoreProfile?.name || user?.displayName || undefined;
  // A topic/field, not the person's name — daily_feed uses it as a literal
  // search query when the OpenAlex profile has no usable concepts yet.
  const topic = firestoreProfile?.researchFocus || author?.field_of_study || undefined;
  const authorId = author?.id;
  const ready = !profileLoading;

  const feedQ = useQuery({ ...dailyFeedQuery(authorId, topic), enabled: ready });
  const conjectureQ = useQuery({ ...dailyConjectureQuery(authorId, name), enabled: ready });
  const grantsQ = useQuery({ ...matchGrantsQuery(authorId ?? ""), enabled: ready && !!authorId });
  const oppsQ = useQuery({ ...industryOpportunitiesQuery(topic || "AI", name), enabled: ready });
  const journalQ = useQuery({ ...journalAdvisorQuery(authorId ?? ""), enabled: ready && !!authorId });
  const peersQ = useQuery({
    ...similarResearchersQuery(authorId ?? "", user?.uid),
    enabled: ready && !!authorId,
  });
  const activityQ = useQuery({ ...activityFeedQuery(authorId, user?.uid), enabled: ready });
  const newsQ = useQuery({ ...scienceNewsQuery(topic), enabled: ready });

  const feed = feedQ.data ?? EMPTY_FEED;
  const conjecture = conjectureQ.data ?? null;
  const activity = activityQ.data?.items ?? EMPTY_ACTIVITY;
  const news = newsQ.data?.items ?? EMPTY_NEWS;
  const jobs = oppsQ.data ?? EMPTY_JOBS;

  const topGrant = grantsQ.data?.[0];
  const topOpportunity = oppsQ.data?.[0];
  const topJournal = journalQ.data?.[0];
  const briefLoading = !(feedQ.isFetched || grantsQ.isFetched || oppsQ.isFetched || journalQ.isFetched);
  const feedLoading =
    feedQ.isPending || activityQ.isPending || newsQ.isPending || oppsQ.isPending;

  const briefItems = useMemo(
    () => buildBriefItems({ topGrant, topOpportunity, topJournal }),
    [topGrant, topOpportunity, topJournal],
  );

  const greetName =
    firestoreProfile?.name?.split(" ")[0] || user?.displayName?.split(" ")[0] || "there";

  const rightRail = (
    <>
      <IdentityRailCard
        name={name ?? "Researcher"}
        status={firestoreProfile?.academicStatus}
        author={author}
        loading={profileLoading}
        unresolved={profileUnresolved}
      />
      <WorkspacesRailCard uid={user?.uid} />
      <div className="flex flex-col gap-3">
        <h2 className="flex items-center gap-2 font-display text-h3 font-semibold text-text-primary">
          <Users2 size={14} className="text-accent-teal" />
          Researchers you may know
        </h2>
        <PeerSuggestionsCard
          peers={peersQ.data?.results ?? []}
          loading={profileLoading || (!!authorId && peersQ.isPending)}
          unresolved={profileUnresolved}
        />
      </div>
      <DailyChallengeCard
        conjecture={conjecture}
        loading={conjectureQ.isPending}
        unresolved={profileUnresolved}
      />
    </>
  );

  return (
    <div className="mx-auto w-full max-w-[1128px] px-4 py-8 md:px-6 lg:grid lg:grid-cols-[minmax(0,70fr)_minmax(0,27fr)] lg:gap-[3%]">
      {/* ── Main column (≈72%) — the feed ───────────────────────────────── */}
      <div className="flex min-w-0 flex-col gap-5">
        <motion.div initial={{ opacity: 0, y: -8 }} animate={{ opacity: 1, y: 0 }} transition={{ duration: 0.4 }}>
          <h1 className="font-display text-display-m font-bold text-text-primary">
            Good to see you, {greetName}
          </h1>
          <p className="mt-1 font-body text-body-s text-text-secondary">
            What&apos;s moving in your field today.
          </p>
        </motion.div>

        <AIDailyBriefCard items={briefItems} loading={briefLoading} />

        <ResearchCommandCenter topic={topic} projectCount={0} />
        <ResearchLoopNote />

        {/* One blended, self-labelling research feed — papers · news · network
            · roles, ranked together, with a lens filter and Save / Not-relevant. */}
        <UnifiedFeed
          papers={feed}
          news={news}
          activity={activity}
          jobs={jobs}
          loading={feedLoading}
        />

        {/* Rail content stacked inline below the feed on < lg so nothing is
            lost on tablet / mobile. */}
        <div className="mt-2 flex flex-col gap-5 lg:hidden">{rightRail}</div>
      </div>

      {/* ── Right rail (≈25%) — identity · workspaces · people · challenge ── */}
      <aside aria-label="Your profile and suggestions" className="hidden lg:block">
        {/* One scrollbar only. The rail rides the page's own scrollbar — no
            second, independent scroll container beside it (that gutter was the
            wasted space). The aside stretches to the feed's height, so the
            sticky child stays pinned 24px below the top bar for the whole
            scroll. The rail's content fits within the viewport, so nothing is
            clipped; if it ever outgrows the viewport, re-introduce an internal
            scroll with `scrollbar-gutter: stable` rather than a raw overflow. */}
        <div className="sticky top-6 flex min-w-0 flex-col gap-5">
          {rightRail}
        </div>
      </aside>
    </div>
  );
}
