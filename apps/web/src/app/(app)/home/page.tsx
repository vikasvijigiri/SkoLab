"use client";

import { useMemo } from "react";
import { motion } from "framer-motion";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Sparkles, FileText, Coins, Briefcase, BookOpen } from "lucide-react";
import { useAuth } from "@/lib/hooks/AuthProvider";
import { useMyProfile } from "@/lib/hooks/useMyProfile";
import { dismissDailyFeedItem } from "@/lib/api/endpoints";
import {
  dailyFeedQuery,
  dailyConjectureQuery,
  industryOpportunitiesQuery,
  matchGrantsQuery,
  journalAdvisorQuery,
} from "@/lib/api/queries";
import { FrontierPulseCard } from "@/components/feed/FrontierPulseCard";
import { AIDailyBriefCard, type BriefItem } from "@/components/feed/AIDailyBriefCard";
import { DailyChallengeCard } from "@/components/feed/DailyChallengeCard";
import { ResearchActionRail } from "@/components/feed/ResearchActionRail";
import { PulseFeedCard } from "@/components/feed/PulseFeedCard";
import type { DailyFeedItem, GrantMatch, JournalRecommendation, IndustryOpportunity } from "@/lib/types";

const EMPTY_FEED: DailyFeedItem[] = [];
import { DURATION_SLOW, EASE_STANDARD } from "@/lib/motion";

/** Each insight is a distinct fact from a distinct source — kept as separate rows
 * rather than joined into one paragraph, which read as a run-on wall of text. */
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
    // No "closes in Nd" here deliberately — the backend's days_left for these
    // programs is a static number, not a live countdown from a real deadline, so
    // displaying it as one would be actively misleading. title/agency/amount are
    // real program facts; the URL is the program's real application page.
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

export default function HomePage() {
  const { user } = useAuth();
  const { firestoreProfile, author, loading: profileLoading, error: profileError, refetch: refetchProfile } = useMyProfile();
  const queryClient = useQueryClient();

  const name = firestoreProfile?.name || user?.displayName || undefined;
  // A topic/field, not the person's name — daily_feed uses this as a literal search
  // query (and as the relevance-discipline filter) whenever the author's OpenAlex
  // profile has no usable concepts yet. Passing a name here previously caused the
  // backend to search for the *name itself*, surfacing unrelated same-name authors.
  const topic = firestoreProfile?.researchFocus || author?.field_of_study || undefined;
  const authorId = author?.id;
  const ready = !profileLoading;

  // Each backend call is its own query — previously the whole page sat in a full
  // skeleton until the slowest of 5 independent calls resolved (measured up to
  // 259s). Now each section reveals the moment its own data arrives.
  const feedQ = useQuery({ ...dailyFeedQuery(authorId, topic), enabled: ready });
  const conjectureQ = useQuery({ ...dailyConjectureQuery(authorId, name), enabled: ready });
  const grantsQ = useQuery({ ...matchGrantsQuery(authorId ?? ""), enabled: ready && !!authorId });
  const oppsQ = useQuery({ ...industryOpportunitiesQuery(topic || "AI", name), enabled: ready });
  const journalQ = useQuery({ ...journalAdvisorQuery(authorId ?? ""), enabled: ready && !!authorId });

  const feed = feedQ.data ?? EMPTY_FEED;
  const feedLoading = feedQ.isPending;
  const conjecture = conjectureQ.data ?? null;
  const conjectureLoading = conjectureQ.isPending;
  const topGrant = grantsQ.data?.[0];
  const topOpportunity = oppsQ.data?.[0];
  const topJournal = journalQ.data?.[0];
  // Brief appears as soon as ANY of its four sources has settled.
  const briefLoading = !(feedQ.isFetched || grantsQ.isFetched || oppsQ.isFetched || journalQ.isFetched);

  const briefItems = useMemo(
    () => buildBriefItems({ topPaper: feed[0], topGrant, topOpportunity, topJournal }),
    [feed, topGrant, topOpportunity, topJournal]
  );

  const greetName = firestoreProfile?.name?.split(" ")[0] || user?.displayName?.split(" ")[0] || "there";

  // Optimistic removal from the feed cache — a failed dismiss just means the
  // paper could reappear on the next fetch, not worth rolling the UI back for.
  const dismiss = useMutation({
    mutationFn: (workId: string) =>
      author?.id ? dismissDailyFeedItem(author.id, workId) : Promise.resolve({ success: true }),
    onMutate: (workId: string) => {
      const key = dailyFeedQuery(authorId, topic).queryKey;
      queryClient.setQueryData<DailyFeedItem[]>(key, (prev) =>
        (prev ?? []).filter((item) => item.id !== workId),
      );
    },
  });
  const handleDismiss = (workId: string) => dismiss.mutate(workId);

  const sections = [
    <FrontierPulseCard key="pulse" author={author} loading={profileLoading} error={profileError} onRetry={refetchProfile} />,
    <AIDailyBriefCard key="brief" items={briefItems} loading={briefLoading} />,
    <DailyChallengeCard key="challenge" conjecture={conjecture} loading={conjectureLoading} />,
  ];

  return (
    <div className="mx-auto max-w-3xl px-4 py-6 md:px-8 lg:max-w-6xl">
      <motion.div initial={{ opacity: 0, y: -8 }} animate={{ opacity: 1, y: 0 }} transition={{ duration: 0.4 }}>
        <h1 className="font-display text-[22px] font-bold text-text-primary">
          Good to see you, {greetName}
        </h1>
        <p className="mt-0.5 font-body text-[13.5px] text-text-secondary">
          Here&apos;s what&apos;s moving in your field today.
        </p>
      </motion.div>

      <div className="mt-4 grid grid-cols-1 gap-6 lg:grid-cols-[1fr_360px]">
        <div className="flex min-w-0 flex-col gap-4">
          <ResearchActionRail />

          {sections.map((section, i) => (
            <motion.div
              key={i}
              initial={{ opacity: 0, y: 16 }}
              animate={{ opacity: 1, y: 0 }}
              transition={{ duration: DURATION_SLOW, delay: 0.1 + i * 0.08, ease: EASE_STANDARD }}
            >
              {section}
            </motion.div>
          ))}
        </div>

        <div className="flex min-w-0 flex-col gap-3 lg:sticky lg:top-6 lg:self-start">
          <h2 className="flex items-center gap-1.5 font-display text-[16px] font-semibold text-text-primary">
            <Sparkles size={15} className="text-accent-violet" />
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
              <p className="mt-2 font-body text-[13px] font-medium text-text-primary">
                No recommendations yet
              </p>
              <p className="mt-0.5 font-body text-[12px] leading-relaxed text-text-muted">
                Add a research focus to your profile and fresh papers in your
                field will show up here.
              </p>
            </div>
          )}
          <div className="flex flex-col gap-3 lg:max-h-[calc(100vh-10rem)] lg:overflow-y-auto lg:pr-1">
            {feed.map((item, i) => (
              <motion.div
                key={item.id}
                initial={{ opacity: 0, y: 16 }}
                animate={{ opacity: 1, y: 0 }}
                transition={{ duration: DURATION_SLOW, delay: 0.34 + i * 0.06, ease: EASE_STANDARD }}
              >
                <PulseFeedCard
                  item={item}
                  onDismiss={author?.id ? () => handleDismiss(item.id) : undefined}
                />
              </motion.div>
            ))}
          </div>
        </div>
      </div>
    </div>
  );
}
