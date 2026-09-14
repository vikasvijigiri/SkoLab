"use client";

import Link from "next/link";
import { ChevronLeft, ShieldCheck, Quote, Bookmark, TrendingUp, MessageSquare, UserPlus, type LucideIcon } from "lucide-react";
import { useAuth } from "@/lib/hooks/AuthProvider";
import { useNotificationSettings, useTrackedResearchers } from "@/lib/hooks/useNotificationSettings";
import { useTrackedTopics } from "@/lib/hooks/useTrackedTopics";
import { ErrorBanner } from "@/components/ui/ErrorBanner";
import { Card } from "@/components/ui/Card";
import type { AlertCadence, NotificationSettings } from "@/lib/types";
import { cn } from "@/lib/utils";

const CADENCES: { key: AlertCadence; label: string }[] = [
  { key: "realtime", label: "Real-time" },
  { key: "daily", label: "Daily digest" },
  { key: "weekly", label: "Weekly digest" },
  { key: "off", label: "Off" },
];

interface RowSpec {
  key: keyof NotificationSettings;
  Icon: LucideIcon;
  tint: string;
  title: string;
  subtitle: string;
}

function CadenceRow({
  spec,
  value,
  onChange,
  disabled,
}: {
  spec: RowSpec;
  value: AlertCadence;
  onChange: (cadence: AlertCadence) => void;
  disabled: boolean;
}) {
  const { Icon, tint } = spec;
  return (
    <div className="grid grid-cols-[1fr_repeat(4,84px)] items-center gap-2 border-t border-border px-4 py-3.5 first:border-t-0">
      <div className="flex min-w-0 items-center gap-2.5">
        <span
          className="flex h-7 w-7 shrink-0 items-center justify-center rounded-full"
          style={{ backgroundColor: `color-mix(in srgb, ${tint} 14%, transparent)`, color: tint }}
        >
          <Icon size={13} />
        </span>
        <span className="min-w-0">
          <span className="block truncate font-body text-body-s font-semibold text-text-primary">{spec.title}</span>
          <span className="block truncate font-body text-[11px] text-text-muted">{spec.subtitle}</span>
        </span>
      </div>
      {CADENCES.map((c) => (
        <label key={c.key} className="flex cursor-pointer justify-center" aria-label={`${spec.title}: ${c.label}`}>
          <input
            type="radio"
            name={spec.key}
            checked={value === c.key}
            disabled={disabled}
            onChange={() => onChange(c.key)}
            className="h-4 w-4 accent-[var(--primary)] disabled:opacity-50"
          />
        </label>
      ))}
    </div>
  );
}

export default function ManageAlertsPage() {
  const { user } = useAuth();
  const { settings, loading, error, setCadence } = useNotificationSettings(user?.uid);
  const { data: tracked } = useTrackedResearchers(user?.uid);
  const { tracked: trackedTopics } = useTrackedTopics(user?.uid);

  const rows: RowSpec[] = [
    {
      key: "citations",
      Icon: Quote,
      tint: "var(--accent-live)",
      title: "New citations to your papers",
      subtitle: "The single most-used alert across academic tools",
    },
    {
      key: "trackedResearchers",
      Icon: Bookmark,
      tint: "var(--accent-violet)",
      title: "New papers from researchers you track",
      subtitle: `${tracked.length} tracked right now`,
    },
    {
      key: "trackedTopics",
      Icon: TrendingUp,
      tint: "var(--accent-orange)",
      title: "Activity in topics you follow",
      subtitle: `${trackedTopics.length} topic${trackedTopics.length === 1 ? "" : "s"} tracked right now`,
    },
    {
      key: "colabMentionsInvites",
      Icon: MessageSquare,
      tint: "var(--accent-indigo)",
      title: "CoLab mentions & invites",
      subtitle: "Someone @mentions you or shares a workspace",
    },
    {
      key: "connections",
      Icon: UserPlus,
      tint: "var(--accent-teal)",
      title: "New connections",
      subtitle: "Someone connects with you",
    },
  ];

  return (
    <div className="mx-auto flex max-w-2xl flex-col gap-5 px-4 py-8 md:px-6">
      <div>
        <Link
          href="/notifications"
          className="mb-2 flex w-fit items-center gap-1 font-body text-[12px] font-medium text-text-muted transition-colors hover:text-text-primary"
        >
          <ChevronLeft size={14} />
          Notifications
        </Link>
        <h1 className="font-display text-display-m font-bold text-text-primary">Manage alerts</h1>
        <p className="mt-1.5 font-body text-body-s leading-relaxed text-text-secondary">
          Every alert below is real and specific — a citation, a paper from someone you track, an actual
          mention. Nothing is engineered to sound bigger than it is, and nothing requires an account change to
          turn off.
        </p>
      </div>

      {error && <ErrorBanner message={error} />}

      <Card className="overflow-x-auto p-0">
        <div className="min-w-[560px]">
          <div className="grid grid-cols-[1fr_repeat(4,84px)] gap-2 bg-surface-subtle px-4 py-2.5 font-mono text-[10px] font-bold uppercase tracking-wide text-text-muted">
            <span>Alert type</span>
            {CADENCES.map((c) => (
              <span key={c.key} className="text-center">
                {c.label}
              </span>
            ))}
          </div>
          {loading ? (
            <div className="flex flex-col gap-3 p-4">
              {[0, 1, 2, 3, 4].map((i) => (
                <div key={i} className="h-9 animate-pulse rounded bg-surface-subtle" />
              ))}
            </div>
          ) : (
            rows.map((spec) => (
              <CadenceRow
                key={spec.key}
                spec={spec}
                value={settings[spec.key]}
                disabled={!user}
                onChange={(cadence) => setCadence(spec.key, cadence)}
              />
            ))
          )}
        </div>
      </Card>

      <div
        className={cn(
          "flex items-start gap-2.5 rounded-md border px-3.5 py-3",
          "border-accent-teal/25 bg-accent-teal/[0.06]",
        )}
      >
        <ShieldCheck size={16} className="mt-0.5 shrink-0 text-accent-teal" />
        <p className="font-body text-[11.5px] leading-relaxed text-text-secondary">
          Unlike some academic sites, nothing here is on by default in a way you can&apos;t see, and turning an
          alert off never requires creating an account first — every row above works the same for a brand-new
          visitor as for a five-year user.
        </p>
      </div>
    </div>
  );
}
