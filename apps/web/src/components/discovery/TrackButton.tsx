"use client";

import { Bookmark } from "lucide-react";
import { cn, focusRing } from "@/lib/utils";
import { useAuth } from "@/lib/hooks/AuthProvider";
import { useTrackedResearchers } from "@/lib/hooks/useTrackedResearchers";

/**
 * "Track" — a bookmark on a researcher, distinct from "Start a project with
 * X" (decision 0021). Persists straight to Firestore
 * (`users/{uid}/tracked_researchers/{authorId}`) via `useTrackedResearchers`,
 * so this button's filled/outline state is always the live Firestore truth —
 * toggling it on the Discovery card and on that researcher's own Highlights
 * page never disagree.
 *
 * Renders nothing for a signed-out visitor, same as the "Start a project"
 * button it sits beside — there's no uid to key the bookmark on.
 */
export function TrackButton({
  authorId,
  name,
  className,
  iconOnly = false,
}: {
  authorId: string;
  name: string;
  className?: string;
  /** Icon-only square button (card corner); otherwise a labelled pill. */
  iconOnly?: boolean;
}) {
  const { user } = useAuth();
  const { isTracked, toggle } = useTrackedResearchers(user?.uid);

  if (!user) return null;
  const tracked = isTracked(authorId);

  function handleClick(e: React.MouseEvent) {
    e.preventDefault();
    e.stopPropagation();
    void toggle(authorId, name);
  }

  return (
    <button
      type="button"
      aria-pressed={tracked}
      aria-label={tracked ? `Tracking ${name} — click to untrack` : `Track ${name}`}
      title={tracked ? "Tracking — click to untrack" : "Track this researcher"}
      onClick={handleClick}
      className={cn(
        "flex shrink-0 cursor-pointer items-center justify-center gap-1.5 rounded-md border font-body text-[11.5px] font-medium transition-colors",
        iconOnly ? "h-9 w-9" : "px-3 py-1.5",
        tracked
          ? "border-primary bg-primary/10 text-primary"
          : "border-border text-text-secondary hover:border-primary/40 hover:text-primary",
        focusRing,
        className,
      )}
    >
      <Bookmark size={13} fill={tracked ? "currentColor" : "none"} />
      {!iconOnly && (tracked ? "Tracking" : "Track")}
    </button>
  );
}
