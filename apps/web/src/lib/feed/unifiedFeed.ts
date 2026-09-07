import type {
  ActivityItem,
  DailyFeedItem,
  IndustryOpportunity,
  ScienceNewsItem,
} from "@/lib/types";

export type FeedKind = "paper" | "news" | "activity" | "job";

export interface UnifiedItem {
  /** Stable, unique across kinds (kind-prefixed). */
  id: string;
  kind: FeedKind;
  title: string;
  /** "why you're seeing this" — one short phrase. */
  why: string;
  href: string;
  external: boolean;
  /** epoch ms for recency ranking; 0 when the source has no date. */
  ts: number;
  /** publisher / journal / company — shown as a chip. */
  source?: string;
  /** small secondary line (authors · year, tags, …). */
  meta?: string;
  score: number;
}

export interface FeedPrefs {
  savedIds: ReadonlySet<string>;
  /** how many times the user hit "Not relevant" on each kind. */
  dismissedKinds: Readonly<Partial<Record<FeedKind, number>>>;
  dismissedIds: ReadonlySet<string>;
}

const EMPTY_PREFS: FeedPrefs = {
  savedIds: new Set(),
  dismissedKinds: {},
  dismissedIds: new Set(),
};

/** Base weight per kind — papers lead, then activity, jobs, news. */
const KIND_WEIGHT: Record<FeedKind, number> = {
  paper: 1.0,
  activity: 0.9,
  job: 0.85,
  news: 0.7,
};

/** 0..0.5, decays to ~0 over 14 days. ts=0 (unknown date) gets a neutral 0.2. */
function recencyBoost(ts: number, now: number): number {
  if (!ts) return 0.2;
  const days = (now - ts) / 86_400_000;
  if (days <= 0) return 0.5;
  return Math.max(0, 0.5 * Math.exp(-days / 6));
}

function toMs(iso?: string): number {
  if (!iso) return 0;
  const t = new Date(iso).getTime();
  return Number.isNaN(t) ? 0 : t;
}

function fromPapers(items: DailyFeedItem[]): UnifiedItem[] {
  return items.map((p) => ({
    id: `paper:${p.id}`,
    kind: "paper" as const,
    title: p.title,
    why:
      typeof p.relevance_score === "number"
        ? `${Math.round(p.relevance_score)}% match to your work`
        : "recommended for your field",
    href: `/paper/${encodeURIComponent(p.id)}`,
    external: false,
    ts: toMs(p.publication_date) || (p.year ? Date.UTC(p.year, 0, 1) : 0),
    source: p.journal,
    meta: [p.authors?.slice(0, 3).join(", "), p.year ? String(p.year) : ""]
      .filter(Boolean)
      .join(" · "),
    score: 0,
    _match: typeof p.relevance_score === "number" ? p.relevance_score / 100 : 0.5,
  })) as (UnifiedItem & { _match: number })[];
}

function fromNews(items: ScienceNewsItem[]): UnifiedItem[] {
  return items.map((n) => ({
    id: `news:${n.url}`,
    kind: "news" as const,
    title: n.title,
    why: n.source ? `science news from ${n.source}` : "science news",
    href: n.url,
    external: true,
    ts: toMs(n.published),
    source: n.source,
    meta: n.summary,
    score: 0,
  }));
}

function fromActivity(items: ActivityItem[]): UnifiedItem[] {
  return items
    .filter((a) => a.type !== "trending" || a.object) // trending needs an object to link
    .map((a) => {
      const who = a.actor?.display_name;
      return {
        id: `activity:${a.id}`,
        kind: "activity" as const,
        title:
          a.type === "connection_made"
            ? `${who ?? "A researcher"} is now connected with you`
            : a.type === "paper_published"
              ? `${who ?? "A researcher you follow"} published “${a.object?.title ?? "a new paper"}”`
              : (a.object?.title ?? "Trending in your field"),
        why: a.why ?? "from your network",
        href: a.href,
        external: false,
        ts: toMs(a.ts),
        source: a.object?.venue,
        meta: a.object?.authors?.slice(0, 3).join(", "),
        score: 0,
      };
    });
}

function fromJobs(items: IndustryOpportunity[]): UnifiedItem[] {
  return items
    .filter((o) => o.type === "JOB" || o.type === "FUNDING")
    .map((o) => ({
      id: `job:${o.id}`,
      kind: "job" as const,
      title: o.title,
      why:
        typeof o.matchScore === "number"
          ? `${Math.round(o.matchScore)}% match to your field`
          : o.type === "FUNDING"
            ? "funding in your area"
            : "role in your area",
      href: o.url ?? "/discovery",
      external: Boolean(o.url),
      ts: 0,
      source: o.companyOrFunder,
      meta: [o.location, o.amount, o.deadline ? `deadline ${o.deadline}` : ""]
        .filter(Boolean)
        .join(" · "),
      score: 0,
      _match: typeof o.matchScore === "number" ? o.matchScore / 100 : 0.5,
    })) as (UnifiedItem & { _match: number })[];
}

/**
 * Merge every home source into one ranked list.
 *
 * score = kind weight + recency boost + match boost + preference nudge
 *   - saved-kind items get a small lift; kinds the user keeps dismissing get
 *     damped (Semantic-Scholar-style 2-signal learning).
 *   - dismissed ids are removed outright.
 * After sorting, a light interleave pass breaks runs of 3+ same-kind items —
 * variety reduces feed fatigue (recsys literature).
 */
export function buildUnifiedFeed(
  sources: {
    papers?: DailyFeedItem[];
    news?: ScienceNewsItem[];
    activity?: ActivityItem[];
    jobs?: IndustryOpportunity[];
  },
  prefs: FeedPrefs = EMPTY_PREFS,
  now: number = Date.now(),
  limit = 24,
): UnifiedItem[] {
  const raw: (UnifiedItem & { _match?: number })[] = [
    ...fromPapers(sources.papers ?? []),
    ...fromActivity(sources.activity ?? []),
    ...fromJobs(sources.jobs ?? []),
    ...fromNews(sources.news ?? []),
  ];

  const scored = raw
    .filter((it) => !prefs.dismissedIds.has(it.id))
    .map(({ _match, ...it }) => {
      const dismissedN = prefs.dismissedKinds[it.kind] ?? 0;
      const score =
        KIND_WEIGHT[it.kind] +
        recencyBoost(it.ts, now) +
        (_match ?? 0) * 0.4 +
        (prefs.savedIds.has(it.id) ? 0.6 : 0) -
        Math.min(0.6, dismissedN * 0.15);
      return { ...it, score };
    })
    .sort((a, b) => b.score - a.score);

  // Interleave: no 3 in a row of the same kind.
  const out: UnifiedItem[] = [];
  const pending = [...scored];
  while (pending.length && out.length < limit) {
    const lastTwoSame =
      out.length >= 2 && out[out.length - 1]!.kind === out[out.length - 2]!.kind;
    let idx = 0;
    if (lastTwoSame) {
      const alt = pending.findIndex((p) => p.kind !== out[out.length - 1]!.kind);
      if (alt >= 0) idx = alt;
    }
    out.push(pending.splice(idx, 1)[0]!);
  }
  return out;
}
