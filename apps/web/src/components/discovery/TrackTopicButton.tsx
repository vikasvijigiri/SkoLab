"use client";

import { Bookmark } from "lucide-react";
import { cn, focusRing } from "@/lib/utils";
import { useAuth } from "@/lib/hooks/AuthProvider";
import { useTrackedTopics } from "@/lib/hooks/useTrackedTopics";

/**
 * "Track" for a Discovery topic — the topic half of decision 0021, mirroring
 * `TrackButton` (the researcher half) exactly: same visual pattern, same
 * always-live-Firestore state, just keyed on `users/{uid}/tracked_topics`
 * instead of `tracked_researchers`. Kept as its own small component rather
 * than parameterising `TrackButton` over a collection, since the two hooks
 * (`useTrackedResearchers` / `useTrackedTopics`) have different id fields
 * (`authorId` vs `topicId`) baked into their Firestore shape.
 *
 * Renders nothing for a signed-out visitor — there's no uid to key the
 * bookmark on.
 */
export function TrackTopicButton({
  topicId,
  name,
  className,
  iconOnly = false,
}: {
  topicId: string;
  name: string;
  className?: string;
  /** Icon-only square button (card corner); otherwise a labelled pill. */
  iconOnly?: boolean;
}) {
  const { user } = useAuth();
  const { isTracked, toggle } = useTrackedTopics(user?.uid);

  if (!user) return null;
  const tracked = isTracked(topicId);

  function handleClick(e: React.MouseEvent) {
    e.preventDefault();
    e.stopPropagation();
    void toggle(topicId, name);
  }

  return (
    <button
      type="button"
      aria-pressed={tracked}
      aria-label={tracked ? `Tracking ${name} — click to untrack` : `Track ${name}`}
      title={tracked ? "Tracking — click to untrack" : "Track this topic"}
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
