import { UserPlus, FileText, Quote, Bookmark, TrendingUp, MessageSquare, Mail, type LucideIcon } from "lucide-react";
import type { NotificationKind } from "@/lib/hooks/useNotifications";

/** Icon + accent tint per notification kind, shared by the bell dropdown and
 *  the full `/notifications` page so the same kind always reads the same way
 *  everywhere (decisions/0022). Tints are the real live palette tokens
 *  (globals.css `--accent-*`), not the mockup's placeholder LinkedIn blues. */
export const NOTIFICATION_VISUALS: Record<NotificationKind, { Icon: LucideIcon; tint: string }> = {
  connection: { Icon: UserPlus, tint: "var(--accent-teal)" },
  paper: { Icon: FileText, tint: "var(--primary)" },
  citation: { Icon: Quote, tint: "var(--accent-live)" },
  tracked_paper: { Icon: Bookmark, tint: "var(--accent-violet)" },
  tracked_topic: { Icon: TrendingUp, tint: "var(--accent-orange)" },
  mention: { Icon: MessageSquare, tint: "var(--accent-indigo)" },
  invite: { Icon: Mail, tint: "var(--accent-indigo)" },
};

export const FILTER_LABELS: { key: "all" | "citations" | "following" | "colab" | "connections"; label: string }[] = [
  { key: "all", label: "All" },
  { key: "citations", label: "Citations" },
  { key: "following", label: "Following" },
  { key: "colab", label: "CoLab" },
  { key: "connections", label: "Connections" },
];

/** Compact relative-time label for the bell dropdown ("2h", "3d"). */
export function agoCompact(iso: string): string {
  const then = new Date(iso).getTime();
  if (Number.isNaN(then)) return "";
  const h = Math.round((Date.now() - then) / 3_600_000);
  if (h < 1) return "just now";
  if (h < 24) return `${h}h`;
  const d = Math.round(h / 24);
  return d < 30 ? `${d}d` : new Date(iso).toLocaleDateString(undefined, { month: "short", day: "numeric" });
}

/** Verbose relative-time label for the full Notifications page ("2 hours ago"). */
export function agoVerbose(iso: string): string {
  const then = new Date(iso).getTime();
  if (Number.isNaN(then)) return "";
  const h = Math.round((Date.now() - then) / 3_600_000);
  if (h < 1) return "just now";
  if (h < 24) return h === 1 ? "1 hour ago" : `${h} hours ago`;
  const d = Math.round(h / 24);
  if (d < 30) return d === 1 ? "1 day ago" : `${d} days ago`;
  return new Date(iso).toLocaleDateString(undefined, { month: "short", day: "numeric" });
}
