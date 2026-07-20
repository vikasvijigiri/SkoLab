"use client";

import Link from "next/link";
import { motion } from "framer-motion";
import { Search, Users2, LineChart, FileText } from "lucide-react";
import { TRANSITION_FAST } from "@/lib/motion";

const ACTIONS = [
  { label: "Discovery", href: "/discovery", accent: "var(--primary)", Icon: Search },
  { label: "Team Pulse", href: "/workspace", accent: "var(--accent-teal)", Icon: Users2 },
  { label: "My Metrics", href: "/profile", accent: "var(--accent-indigo)", Icon: LineChart },
  { label: "Papers", href: "/discovery?tab=papers", accent: "var(--accent-cyan)", Icon: FileText },
] as const;

const MotionLink = motion.create(Link);

/** Web port of ResearchActionRail — pure navigation shortcuts, no API calls. */
export function ResearchActionRail() {
  return (
    <div className="flex gap-2.5 overflow-x-auto pb-1">
      {ACTIONS.map((a) => (
        <MotionLink
          key={a.label}
          href={a.href}
          whileHover={{ scale: 1.05, boxShadow: `0 4px 14px color-mix(in srgb, ${a.accent} 20%, transparent)` }}
          whileTap={{ scale: 0.95 }}
          transition={TRANSITION_FAST}
          className="flex shrink-0 items-center gap-2 rounded-full border border-border bg-surface px-4 py-2 font-body text-[13px] font-medium text-text-primary transition-colors duration-[var(--motion-fast)] hover:border-primary"
        >
          <a.Icon size={14} style={{ color: a.accent }} />
          {a.label}
        </MotionLink>
      ))}
    </div>
  );
}
