"use client";

import { useMemo } from "react";
import Link from "next/link";
import { motion } from "framer-motion";
import { useQuery } from "@tanstack/react-query";
import { Users2, FolderKanban } from "lucide-react";
import { useAuth } from "@/lib/hooks/AuthProvider";
import { useMyProfile } from "@/lib/hooks/useMyProfile";
import { useFirestoreCollection } from "@/lib/hooks/useFirestoreCollection";
import { useTrackedResearchers } from "@/lib/hooks/useTrackedResearchers";
import { subscribeProjects } from "@/lib/firebase/workspace";
import {
  dailyFeedQuery,
  industryOpportunitiesQuery,
  matchGrantsQuery,
  similarResearchersQuery,
  activityFeedQuery,
  coachPulseQuery,
} from "@/lib/api/queries";
import { CoachPulseCard } from "@/components/feed/CoachPulseCard";
import { PeerSuggestionsCard } from "@/components/feed/PeerSuggestionsCard";
import { UnifiedFeed } from "@/components/feed/UnifiedFeed";
import { Card } from "@/components/ui/Card";
import { IdentityStrengthCard } from "@/components/product/IdentityStrengthCard";
import type {
  ActivityItem,
  CollabProject,
  DailyFeedItem,
  IndustryOpportunity,
} from "@/lib/types";

const EMPTY_FEED: DailyFeedItem[] = [];
const EMPTY_ACTIVITY: ActivityItem[] = [];
const EMPTY_JOBS: IndustryOpportunity[] = [];

/** Left-rail shortcut list of the user's actual workspaces — data, not nav. */
function WorkspacesRailCard({
  uid,
  projects,
  loading,
}: {
  uid?: string;
  projects: CollabProject[];
  loading: boolean;
}) {
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
  const { user, getIdToken } = useAuth();
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

  const { data: projects, loading: projectsLoading } = useFirestoreCollection<CollabProject>(
    user?.uid ? (next, onErr) => subscribeProjects(user.uid, next, onErr) : null,
    { deps: [user?.uid] },
  );
  const { trackedIds, track } = useTrackedResearchers(user?.uid);
  const trackedAuthorIds = useMemo(() => [...trackedIds], [trackedIds]);

  const feedQ = useQuery({ ...dailyFeedQuery(authorId, topic), enabled: ready });
  const grantsQ = useQuery({ ...matchGrantsQuery(authorId ?? ""), enabled: ready && !!authorId });
  const oppsQ = useQuery({ ...industryOpportunitiesQuery(topic || "AI", name), enabled: ready });
  const peersQ = useQuery({
    ...similarResearchersQuery(authorId ?? "", user?.uid),
    enabled: ready && !!authorId,
  });
  const activityQ = useQuery({
    ...activityFeedQuery(authorId, user?.uid, getIdToken),
    enabled: ready,
  });
  const pulseQ = useQuery({
    ...coachPulseQuery(authorId, topic, trackedAuthorIds),
    enabled: ready && (!!authorId || trackedAuthorIds.length > 0),
  });

  const feed = feedQ.data ?? EMPTY_FEED;
  const activity = activityQ.data?.items ?? EMPTY_ACTIVITY;
  const jobs = oppsQ.data ?? EMPTY_JOBS;

  const topGrant = grantsQ.data?.[0];
  const pulseLoading = !(pulseQ.isFetched || grantsQ.isFetched);
  const feedLoading = feedQ.isPending || activityQ.isPending || oppsQ.isPending;

  const greetName =
    firestoreProfile?.name?.split(" ")[0] || user?.displayName?.split(" ")[0] || "there";

  const rightRail = (
    <>
      <IdentityStrengthCard
        name={name ?? "Researcher"}
        status={firestoreProfile?.academicStatus}
        firestoreProfile={firestoreProfile}
        author={author}
        loading={profileLoading}
        unresolved={profileUnresolved}
      />
      <WorkspacesRailCard uid={user?.uid} projects={projects} loading={projectsLoading} />
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
    </>
  );

  return (
    <div className="mx-auto w-full max-w-[1320px] px-4 py-8 md:px-6 lg:grid lg:grid-cols-[minmax(0,20fr)_minmax(0,53fr)_minmax(0,27fr)] lg:gap-[2.5%]">
      {/* ── Left column (≈20%) — Since You Were Here, its own scrollbar ──── */}
      <aside aria-label="Since you were here" className="hidden lg:block">
        {/* Independent of the page scroll: pinned 24px below the top bar, its
            own overflow when the panel outgrows the viewport. */}
        <div className="sticky top-6 max-h-[calc(100vh-3rem)] overflow-y-auto pr-1 [scrollbar-width:thin]">
          <CoachPulseCard
            impact={pulseQ.data?.impact}
            trackedActivity={pulseQ.data?.tracked_activity}
            worthTracking={pulseQ.data?.worth_tracking}
            topGrant={topGrant}
            careerStage={firestoreProfile?.academicStatus}
            loading={pulseLoading}
            onTrack={track}
          />
        </div>
      </aside>

      {/* ── Center column (≈53%) — command center + the feed ────────────── */}
      <div className="flex min-w-0 flex-col gap-5">
        <motion.div initial={{ opacity: 0, y: -8 }} animate={{ opacity: 1, y: 0 }} transition={{ duration: 0.4 }}>
          <h1 className="font-display text-display-m font-bold text-text-primary">
            Good to see you, {greetName}
          </h1>
          <p className="mt-1 font-body text-body-s text-text-secondary">
            What&apos;s moving in your field today.
          </p>
        </motion.div>

        {/* The panel has no column of its own below lg — it stacks here. */}
        <div className="lg:hidden">
          <CoachPulseCard
            impact={pulseQ.data?.impact}
            trackedActivity={pulseQ.data?.tracked_activity}
            worthTracking={pulseQ.data?.worth_tracking}
            topGrant={topGrant}
            careerStage={firestoreProfile?.academicStatus}
            loading={pulseLoading}
            onTrack={track}
          />
        </div>

        {/* One blended, self-labelling research feed — papers · network ·
            roles, ranked together, with a lens filter and Save / Not-relevant. */}
        <UnifiedFeed
          papers={feed}
          activity={activity}
          jobs={jobs}
          loading={feedLoading}
        />

        {/* Rail content stacked inline below the feed on < lg so nothing is
            lost on tablet / mobile. */}
        <div className="mt-2 flex flex-col gap-5 lg:hidden">{rightRail}</div>
      </div>

      {/* ── Right rail (≈27%) — identity · workspaces · people · strength ── */}
      <aside aria-label="Your profile and suggestions" className="hidden lg:block">
        {/* Rides the page's own scrollbar — the sticky child stays pinned 24px
            below the top bar for the whole scroll. If the rail ever outgrows
            the viewport, add `overflow-y-auto` with a max-height as the left
            column does. */}
        <div className="sticky top-6 flex min-w-0 flex-col gap-5">
          {rightRail}
        </div>
      </aside>
    </div>
  );
}
