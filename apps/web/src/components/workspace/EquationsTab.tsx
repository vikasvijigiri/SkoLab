"use client";

import { useState } from "react";
import { AnimatePresence, motion } from "framer-motion";
import { updateEquations } from "@/lib/firebase/workspace";
import { Button } from "@/components/ui/Button";
import { ErrorBanner, friendlyFirestoreError } from "@/components/ui/ErrorBanner";

/**
 * Remounted (via the parent's conditional tab render) whenever the Equations
 * tab is reselected, which is what picks up remote Firestore updates — the
 * local draft otherwise stays independent of the live doc while editing.
 */
export function EquationsTab({ projectId, initialLatex }: { projectId: string; initialLatex: string }) {
  const [value, setValue] = useState(initialLatex);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function handleSave() {
    setSaving(true);
    setError(null);
    try {
      await updateEquations(projectId, value);
    } catch (err) {
      setError(friendlyFirestoreError(err as { code?: string; message?: string }));
    } finally {
      setSaving(false);
    }
  }

  return (
    <div className="flex flex-col gap-3">
      <p className="font-body text-[12.5px] text-text-muted">
        Shared LaTeX blackboard — last write wins, same as the mobile app.
      </p>
      <AnimatePresence>
        {error && (
          <motion.div initial={{ opacity: 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0 }}>
            <ErrorBanner message={error} />
          </motion.div>
        )}
      </AnimatePresence>
      <textarea
        value={value}
        onChange={(e) => setValue(e.target.value)}
        rows={10}
        placeholder="\\int_0^\\infty e^{-x^2}\\,dx = \\frac{\\sqrt{\\pi}}{2}"
        className="w-full rounded-sm bg-surface-subtle p-3 font-mono text-[13px] text-text-primary outline-none focus:ring-1 focus:ring-primary"
      />
      <Button fullWidth={false} className="w-32" onClick={handleSave} loading={saving}>
        Save
      </Button>
    </div>
  );
}
