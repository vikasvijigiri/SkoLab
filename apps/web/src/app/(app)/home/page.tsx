"use client";

import { useEffect, useState } from "react";
import { motion } from "framer-motion";
import { Sparkles, FileText, Coins, Briefcase, BookOpen } from "lucide-react";
import { useAuth } from "@/lib/hooks/AuthProvider";
import { useMyProfile } from "@/lib/hooks/useMyProfile";
import {
  getDailyFeed,
  dismissDailyFeedItem,
  getDailyConjecture,
  getIndustryOpportunities,
  getMatchGrants,
  getJournalAdvisor,
} from "@/lib/api/endpoints";
import { FrontierPulseCard } from "@/components/feed/FrontierPulseCard";
import { AIDailyBriefCard, type BriefItem } from "@/components/feed/AIDailyBriefCard";
import { DailyChallengeCard } from "@/components/feed/DailyChallengeCard";
import { ResearchActionRail } from "@/components/feed/ResearchActionRail";
import { PulseFeedCard } from "@/components/feed/PulseFeedCard";
import type { DailyFeedItem, Conjecture, GrantMatch, JournalRecommendation, IndustryOpportunity } from "@/lib/types";

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

  const [feed, setFeed] = useState<DailyFeedItem[]>([]);
  const [feedLoading, setFeedLoading] = useState(true);
  const [conjecture, setConjecture] = useState<Conjecture | null>(null);
  const [briefItems, setBriefItems] = useState<BriefItem[]>([]);

  useEffect(() => {
    if (profileLoading) return;
    const name = firestoreProfile?.name || user?.displayName || undefined;
    // A topic/field, not the person's name — daily_feed uses this as a literal search
    // query (and as the relevance-discipline filter) whenever the author's OpenAlex
    // profile has no usable concepts yet. Passing a name here previously caused the
    // backend to search arXiv/OpenAlex for the *name itself*, surfacing papers by
    // unrelated same-first-name authors instead of anything topically relevant.
    const topic = firestoreProfile?.researchFocus || author?.field_of_study || undefined;
    const authorId = author?.id;

    let cancelled = false;
    (async () => {
      setFeedLoading(true);
      const [feedRes, conjectureRes, grantsRes, oppsRes, journalsRes] = await Promise.allSettled([
        getDailyFeed(authorId, topic),
        getDailyConjecture(authorId, name),
        authorId ? getMatchGrants(authorId) : Promise.resolve([]),
        getIndustryOpportunities(topic || "AI", name),
        authorId ? getJournalAdvisor(authorId) : Promise.resolve([]),
      ]);
      if (cancelled) return;

      const feedItems = feedRes.status === "fulfilled" ? feedRes.value : [];
      const conj = conjectureRes.status === "fulfilled" ? conjectureRes.value : null;
      const grants = grantsRes.status === "fulfilled" ? grantsRes.value : [];
      const opps = oppsRes.status === "fulfilled" ? oppsRes.value : [];
      const journals = journalsRes.status === "fulfilled" ? journalsRes.value : [];

      setFeed(feedItems);
      setConjecture(conj);
      setBriefItems(
        buildBriefItems({
          topPaper: feedItems[0],
          topGrant: grants[0],
          topOpportunity: opps[0],
          topJournal: journals[0],
        })
      );
      setFeedLoading(false);
    })();

    return () => {
      cancelled = true;
    };
  }, [profileLoading, firestoreProfile, author, user]);

  const greetName = firestoreProfile?.name?.split(" ")[0] || user?.displayName?.split(" ")[0] || "there";

  // Optimistic removal — a failed dismiss call just means the paper could
  // reappear on the next fetch, which isn't worth rolling back the UI for.
  const handleDismiss = (workId: string) => {
    setFeed((prev) => prev.filter((item) => item.id !== workId));
    if (author?.id) {
      dismissDailyFeedItem(author.id, workId).catch(() => {});
    }
  };

  const sections = [
    <FrontierPulseCard key="pulse" author={author} loading={profileLoading} error={profileError} onRetry={refetchProfile} />,
    <AIDailyBriefCard key="brief" items={briefItems} loading={feedLoading} />,
    <DailyChallengeCard key="challenge" conjecture={conjecture} loading={feedLoading} />,
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
              transition={{ duration: 0.4, delay: 0.1 + i * 0.08, ease: [0.22, 1, 0.36, 1] }}
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
            <p className="font-body text-[13px] text-text-muted">
              No recommendations yet — make sure the backend is running and reachable.
            </p>
          )}
          <div className="flex flex-col gap-3 lg:max-h-[calc(100vh-10rem)] lg:overflow-y-auto lg:pr-1">
            {feed.map((item, i) => (
              <motion.div
                key={item.id}
                initial={{ opacity: 0, y: 16 }}
                animate={{ opacity: 1, y: 0 }}
                transition={{ duration: 0.4, delay: 0.34 + i * 0.06, ease: [0.22, 1, 0.36, 1] }}
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
