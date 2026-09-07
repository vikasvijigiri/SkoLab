"use client";

import { useMemo, useState } from "react";
import Link from "next/link";
import { motion } from "framer-motion";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { FileText, Coins, Briefcase, BookOpen, Users2, FolderKanban, Sparkles, Newspaper } from "lucide-react";
import { useAuth } from "@/lib/hooks/AuthProvider";
import { useMyProfile } from "@/lib/hooks/useMyProfile";
import { useFirestoreCollection } from "@/lib/hooks/useFirestoreCollection";
import { subscribeProjects } from "@/lib/firebase/workspace";
import { dismissDailyFeedItem } from "@/lib/api/endpoints";
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
import { PulseFeedCard } from "@/components/feed/PulseFeedCard";
import { PeerSuggestionsCard } from "@/components/feed/PeerSuggestionsCard";
import { ActivityFeedItem } from "@/components/feed/ActivityFeedItem";
import { ScienceNewsCard } from "@/components/feed/ScienceNewsCard";
import { IdentityRailCard } from "@/components/feed/IdentityRailCard";
import { Card } from "@/components/ui/Card";
import { cn } from "@/lib/utils";
import { DURATION_SLOW, EASE_STANDARD } from "@/lib/motion";
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

/** Each insight is a distinct fact from a distinct source — separate rows, not a
 * run-on paragraph. */
function buildBriefItems(opts: {
  topPaper?: DailyFeedItem;
  topGrant?: GrantMatch;
  topOpportunity?: IndustryOpportunity;
  topJournal?: JournalRecommendation;
}): BriefItem[] {
  const items: BriefItem[] = [];
  if (opts.topPaper) {
    items.push({
      key: "paper",
      icon: FileText,
      color: "var(--accent-cyan)",
      label: "New paper",
      text: `**${opts.topPaper.title}** — ${opts.topPaper.relevance_score}% match`,
      href: `/paper/${encodeURIComponent(opts.topPaper.id)}`,
    });
  }
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
      <p className="flex items-center gap-1.5 font-mono text-[10px] font-semibold uppercase tracking-wide text-text-muted">
        <FolderKanban size={11} />
        Your workspaces
      </p>
      {loading ? (
        <div className="flex flex-col gap-1.5">
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
                className="block truncate rounded px-1.5 py-1 font-body text-[12.5px] text-text-secondary transition-colors hover:bg-surface-subtle hover:text-text-primary"
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

type Sort = "latest" | "top";

export default function HomePage() {
  const { user, getIdToken } = useAuth();
  const {
    firestoreProfile,
    author,
    loading: profileLoading,
    unresolved: profileUnresolved,
  } = useMyProfile();
  const queryClient = useQueryClient();

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
  const feedLoading = feedQ.isPending;
  const conjecture = conjectureQ.data ?? null;
  const activity = activityQ.data?.items ?? EMPTY_ACTIVITY;
  const activityLoading = activityQ.isPending;
  const news = newsQ.data?.items ?? EMPTY_NEWS;

  const topGrant = grantsQ.data?.[0];
  const topOpportunity = oppsQ.data?.[0];
  const topJournal = journalQ.data?.[0];
  const briefLoading = !(feedQ.isFetched || grantsQ.isFetched || oppsQ.isFetched || journalQ.isFetched);

  const briefItems = useMemo(
    () => buildBriefItems({ topPaper: feed[0], topGrant, topOpportunity, topJournal }),
    [feed, topGrant, topOpportunity, topJournal],
  );

  const [sort, setSort] = useState<Sort>("latest");
  const sortedActivity = useMemo(() => {
    if (sort === "latest") return activity;
    return [...activity].sort(
      (a, b) => (b.object?.citations ?? 0) - (a.object?.citations ?? 0),
    );
  }, [activity, sort]);

  const greetName =
    firestoreProfile?.name?.split(" ")[0] || user?.displayName?.split(" ")[0] || "there";

  // Optimistic feed removal — a failed dismiss just means the paper may reappear.
  const dismiss = useMutation({
    mutationFn: async (workId: string) => {
      if (!author?.id) return { success: true };
      const idToken = await getIdToken();
      return dismissDailyFeedItem(idToken, author.id, workId);
    },
    onMutate: (workId: string) => {
      const key = dailyFeedQuery(authorId, topic).queryKey;
      queryClient.setQueryData<DailyFeedItem[]>(key, (prev) =>
        (prev ?? []).filter((item) => item.id !== workId),
      );
    },
  });

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
        <h2 className="flex items-center gap-1.5 font-display text-[15px] font-semibold text-text-primary">
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
    <div className="mx-auto w-full max-w-[1400px] px-4 py-6 md:px-6 lg:grid lg:grid-cols-[minmax(0,72fr)_minmax(0,25fr)] lg:gap-[3%]">
      {/* ── Main column (≈72%) — the feed ───────────────────────────────── */}
      <div className="flex min-w-0 flex-col gap-4">
        <motion.div initial={{ opacity: 0, y: -8 }} animate={{ opacity: 1, y: 0 }} transition={{ duration: 0.4 }}>
          <h1 className="font-display text-[20px] font-bold text-text-primary">
            Good to see you, {greetName}
          </h1>
          <p className="mt-0.5 font-body text-[13px] text-text-secondary">
            What&apos;s moving in your field today.
          </p>
        </motion.div>

        <AIDailyBriefCard items={briefItems} loading={briefLoading} />

        {/* activity stream */}
        <div className="flex items-center justify-between">
          <h2 className="font-display text-[15px] font-semibold text-text-primary">Activity</h2>
          <div className="flex items-center gap-0.5 rounded-full border border-border p-0.5">
            {(["latest", "top"] as const).map((s) => (
              <button
                key={s}
                type="button"
                onClick={() => setSort(s)}
                className={cn(
                  "rounded-full px-2.5 py-0.5 font-body text-[11.5px] font-medium capitalize transition-colors",
                  sort === s
                    ? "bg-primary text-text-on-primary"
                    : "text-text-muted hover:text-text-primary",
                )}
              >
                {s}
              </button>
            ))}
          </div>
        </div>

        {activityLoading ? (
          <div className="flex flex-col gap-3">
            {[0, 1, 2].map((i) => (
              <div key={i} className="h-28 animate-pulse rounded-[8px] bg-surface-subtle" />
            ))}
          </div>
        ) : sortedActivity.length === 0 ? (
          <Card className="text-center">
            <Sparkles size={18} className="mx-auto text-text-muted" />
            <p className="mt-2 font-body text-[13px] font-medium text-text-primary">Your feed is warming up</p>
            <p className="mt-0.5 font-body text-[12px] leading-relaxed text-text-muted">
              Connect with researchers and their new work shows up here. Meanwhile, see the recommendations below.
            </p>
          </Card>
        ) : (
          <div className="flex flex-col gap-3">
            {sortedActivity.map((item, i) => (
              <motion.div
                key={item.id}
                initial={{ opacity: 0, y: 12 }}
                animate={{ opacity: 1, y: 0 }}
                transition={{ duration: DURATION_SLOW, delay: Math.min(i, 6) * 0.05, ease: EASE_STANDARD }}
              >
                <ActivityFeedItem item={item} />
              </motion.div>
            ))}
          </div>
        )}

        {/* in the news */}
        {(newsQ.isPending || news.length > 0) && (
          <div className="mt-2 flex flex-col gap-3">
            <h2 className="flex items-center gap-1.5 font-display text-[15px] font-semibold text-text-primary">
              <Newspaper size={14} className="text-accent-teal" />
              In the news
            </h2>
            <ScienceNewsCard items={news} loading={newsQ.isPending} />
          </div>
        )}

        {/* recommended papers */}
        <div className="mt-2 flex flex-col gap-3">
          <h2 className="flex items-center gap-1.5 font-display text-[15px] font-semibold text-text-primary">
            <Sparkles size={14} className="text-accent-violet" />
            Recommended for you
          </h2>
          {feedLoading && (
            <div className="flex flex-col gap-3">
              {[0, 1].map((i) => (
                <div key={i} className="h-40 animate-pulse rounded-[8px] bg-surface-subtle" />
              ))}
            </div>
          )}
          {!feedLoading && feed.length === 0 && (
            <div className="rounded-[10px] border border-dashed border-border px-4 py-6 text-center">
              <Sparkles size={18} className="mx-auto text-text-muted" />
              <p className="mt-2 font-body text-[13px] font-medium text-text-primary">No recommendations yet</p>
              <p className="mt-0.5 font-body text-[12px] leading-relaxed text-text-muted">
                Add a research focus to your profile and fresh papers in your field will show up here.
              </p>
            </div>
          )}
          {feed.map((item, i) => (
            <motion.div
              key={item.id}
              initial={{ opacity: 0, y: 16 }}
              animate={{ opacity: 1, y: 0 }}
              transition={{ duration: DURATION_SLOW, delay: 0.1 + Math.min(i, 6) * 0.06, ease: EASE_STANDARD }}
            >
              <PulseFeedCard item={item} onDismiss={author?.id ? () => dismiss.mutate(item.id) : undefined} />
            </motion.div>
          ))}
        </div>

        {/* Rail content stacked inline below the feed on < lg so nothing is
            lost on tablet / mobile. */}
        <div className="mt-2 flex flex-col gap-5 lg:hidden">{rightRail}</div>
      </div>

      {/* ── Right rail (≈25%) — identity · workspaces · people · challenge ── */}
      <aside className="hidden lg:block">
        <div className="sticky top-6 flex flex-col gap-5">{rightRail}</div>
      </aside>
    </div>
  );
}
