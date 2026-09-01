"use client";

import { useState } from "react";
import { AnimatePresence, motion } from "framer-motion";
import { updateManuscript } from "@/lib/firebase/workspace";
import { Button } from "@/components/ui/Button";
import { ErrorBanner, friendlyFirestoreError } from "@/components/ui/ErrorBanner";

/** Remounted (via the parent's conditional tab render) whenever this tab is reselected — see EquationsTab. */
export function ManuscriptTab({
  projectId,
  initialDraft,
  initialProgress,
}: {
  projectId: string;
  initialDraft: string;
  initialProgress: number;
}) {
  const [draft, setDraft] = useState(initialDraft);
  const [progress, setProgress] = useState(Math.round(initialProgress * 100));
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function handleSave() {
    setSaving(true);
    setError(null);
    try {
      await updateManuscript(projectId, draft, progress / 100);
    } catch (err) {
      setError(friendlyFirestoreError(err as { code?: string; message?: string }));
    } finally {
      setSaving(false);
    }
  }

  return (
    <div className="flex flex-col gap-3">
      <div>
        <div className="flex items-center justify-between">
          <label
            htmlFor="manuscript-progress"
            className="font-body text-[12.5px] font-medium text-text-secondary"
          >
            Progress
          </label>
          <span className="font-mono text-[12px] text-text-primary">{progress}%</span>
        </div>
        <input
          id="manuscript-progress"
          type="range"
          min={0}
          max={100}
          value={progress}
          onChange={(e) => setProgress(Number(e.target.value))}
          className="mt-1.5 w-full accent-accent-teal"
        />
      </div>
      <AnimatePresence>
        {error && (
          <motion.div initial={{ opacity: 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0 }}>
            <ErrorBanner message={error} />
          </motion.div>
        )}
      </AnimatePresence>
      <textarea
        value={draft}
        onChange={(e) => setDraft(e.target.value)}
        rows={12}
        placeholder="Start drafting your manuscript..."
        className="w-full rounded-sm bg-surface-subtle p-3 font-body text-[13.5px] leading-relaxed text-text-primary outline-none focus:ring-1 focus:ring-primary"
      />
      <Button fullWidth={false} className="w-32" onClick={handleSave} loading={saving}>
        Save
      </Button>
    </div>
  );
}
