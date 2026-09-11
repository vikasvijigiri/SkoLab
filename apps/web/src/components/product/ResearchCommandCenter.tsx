"use client";

import Link from "next/link";
import {
  ArrowRight,
  Check,
  Compass,
  FlaskConical,
  FolderKanban,
  ShieldCheck,
  Users2,
  CircleHelp,
} from "lucide-react";
import { Card } from "@/components/ui/Card";
import { useLocalStorage } from "@/lib/hooks/useLocalStorage";
import { cn } from "@/lib/utils";

type ActionKey = "change" | "evidence" | "unknown" | "next" | "collaborate" | "preserve";

const ACTIONS: Array<{
  key: ActionKey;
  label: string;
  detail: string;
  href: string;
  Icon: typeof Compass;
  color: string;
}> = [
  {
    key: "change",
    label: "What changed?",
    detail: "See the newest movement, topics and people in your field.",
    href: "/discovery",
    Icon: Compass,
    color: "var(--accent-indigo)",
  },
  {
    key: "evidence",
    label: "What supports it?",
    detail: "Trace a signal back to papers, authors and source context.",
    href: "/discovery?tab=papers",
    Icon: ShieldCheck,
    color: "var(--accent-teal)",
  },
  {
    key: "unknown",
    label: "What is unknown?",
    detail: "Surface uncertainty, contradictions and the proof still missing.",
    href: "/horizon",
    Icon: CircleHelp,
    color: "var(--accent-amber)",
  },
  {
    key: "next",
    label: "What should we do next?",
    detail: "Turn the strongest open question into an owned experiment.",
    href: "/workspace",
    Icon: FlaskConical,
    color: "var(--accent-orange)",
  },
  {
    key: "collaborate",
    label: "Who should collaborate?",
    detail: "Find researchers with complementary expertise and methods.",
    href: "/discovery?tab=researchers",
    Icon: Users2,
    color: "var(--accent-violet)",
  },
  {
    key: "preserve",
    label: "How do we preserve it?",
    detail: "Keep decisions, evidence and handoffs in a durable project record.",
    href: "/workspace",
    Icon: FolderKanban,
    color: "var(--accent-rose)",
  },
];

export function ResearchCommandCenter({
  topic,
  projectCount = 0,
}: {
  topic?: string;
  projectCount?: number;
}) {
  const [completed, setCompleted] = useLocalStorage<Partial<Record<ActionKey, boolean>>>(
    "research-command-center:completed",
    {},
  );
  const done = ACTIONS.filter((action) => completed[action.key]).length;

  function toggle(key: ActionKey) {
    setCompleted({ ...completed, [key]: !completed[key] });
  }

  return (
    <Card accentColor="var(--accent-indigo)" className="overflow-hidden p-0">
      <div className="flex flex-col gap-2 border-b border-border/70 p-5 sm:flex-row sm:items-end sm:justify-between">
        <div>
          <p className="eyebrow text-accent-indigo">Research command center</p>
          <h2 className="mt-1 font-display text-h2 font-semibold text-text-primary">Answer the questions behind good research</h2>
          <p className="mt-1 max-w-xl font-body text-body-s text-text-secondary">
            {topic ? `Your current signal: ${topic}. ` : "Your next research signal starts here. "}
            A simple decision trail for moving from a changing field to a defensible, collaborative next step.
          </p>
        </div>
        <div className="shrink-0 font-mono text-[11px] uppercase tracking-wide text-text-muted">
          {done}/{ACTIONS.length} steps active{projectCount > 0 ? ` · ${projectCount} project${projectCount === 1 ? "" : "s"}` : ""}
        </div>
      </div>
      <div className="grid grid-cols-1 divide-y divide-border/70 sm:grid-cols-2 sm:divide-x sm:divide-y-0 lg:grid-cols-3">
        {ACTIONS.map(({ key, label, detail, href, Icon, color }) => {
          const isDone = Boolean(completed[key]);
          return (
            <div key={key} className="flex min-h-[132px] flex-col gap-3 p-4">
              <div className="flex items-start justify-between gap-3">
                <span className="flex h-8 w-8 items-center justify-center rounded-md" style={{ background: `${color}18`, color }}>
                  <Icon size={16} />
                </span>
                <button
                  type="button"
                  aria-label={`${isDone ? "Mark" : "Complete"} ${label}`}
                  aria-pressed={isDone}
                  onClick={() => toggle(key)}
                  className={cn(
                    "flex h-7 w-7 cursor-pointer items-center justify-center rounded-full border transition-colors",
                    isDone ? "border-accent-emerald bg-accent-emerald/10 text-accent-emerald" : "border-border text-text-muted hover:border-primary hover:text-primary",
                  )}
                >
                  <Check size={14} />
                </button>
              </div>
              <div className="min-w-0">
                <Link href={href} className="group inline-flex items-center gap-1 font-display text-h3 font-semibold text-text-primary hover:text-primary">
                  {label}
                  <ArrowRight size={14} className="transition-transform group-hover:translate-x-0.5" />
                </Link>
                <p className="mt-1 font-body text-[12px] leading-relaxed text-text-muted">{detail}</p>
              </div>
            </div>
          );
        })}
      </div>
    </Card>
  );
}

export function ResearchLoopNote() {
  return (
    <div className="flex items-start gap-2 rounded-md border border-accent-teal/20 bg-accent-teal/5 px-3 py-2.5">
      <FlaskConical size={15} className="mt-0.5 shrink-0 text-accent-teal" />
      <p className="font-body text-[12px] leading-relaxed text-text-secondary">
        SkoLab keeps the reasoning trail connected: a field signal can become a project, an opportunity, and eventually a reproducible research record.
      </p>
    </div>
  );
}
