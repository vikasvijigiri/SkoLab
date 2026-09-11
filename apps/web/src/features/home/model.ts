import { BookOpen, Briefcase, Coins, type LucideIcon } from "lucide-react";
import type { GrantMatch, IndustryOpportunity, JournalRecommendation } from "@/lib/types";

export interface HomeBriefItem {
  key: string;
  icon: LucideIcon;
  color: string;
  label: string;
  text: string;
  href?: string;
}

export function buildBriefItems(opts: {
  topGrant?: GrantMatch;
  topOpportunity?: IndustryOpportunity;
  topJournal?: JournalRecommendation;
}): HomeBriefItem[] {
  const items: HomeBriefItem[] = [];
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
