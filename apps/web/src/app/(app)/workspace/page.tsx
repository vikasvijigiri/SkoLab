"use client";

import { useState } from "react";
import Link from "next/link";
import { motion, AnimatePresence } from "framer-motion";
import { Plus, FolderKanban, Users2 } from "lucide-react";
import { Card } from "@/components/ui/Card";
import { Button } from "@/components/ui/Button";
import { Input } from "@/components/ui/Input";
import { ErrorBanner, friendlyFirestoreError } from "@/components/ui/ErrorBanner";
import { useAuth } from "@/lib/hooks/AuthProvider";
import { useFirestoreCollection } from "@/lib/hooks/useFirestoreCollection";
import { subscribeProjects, createProject } from "@/lib/firebase/workspace";
import type { CollabProject } from "@/lib/types";
import { TRANSITION_FAST, DURATION_SLOW, EASE_STANDARD } from "@/lib/motion";

export default function WorkspaceListPage() {
  const { user } = useAuth();
  const {
    data: projects,
    loading,
    error: subError,
  } = useFirestoreCollection<CollabProject>(
    user ? (next, onErr) => subscribeProjects(user.uid, next, onErr) : null,
    { deps: [user?.uid] },
  );
  const [createError, setCreateError] = useState<string | null>(null);
  const error = createError ?? subError;
  const [creating, setCreating] = useState(false);
  const [name, setName] = useState("");
  const [description, setDescription] = useState("");
  const [submitting, setSubmitting] = useState(false);

  async function handleCreate(e: React.FormEvent) {
    e.preventDefault();
    if (!user || !name.trim()) return;
    setSubmitting(true);
    try {
      await createProject({
        name,
        description,
        ownerUid: user.uid,
        ownerName: user.displayName ?? "Researcher",
        ownerEmail: user.email ?? "",
      });
      setName("");
      setDescription("");
      setCreating(false);
    } catch (err) {
      setCreateError(
        friendlyFirestoreError(err as { code?: string; message?: string }),
      );
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div className="mx-auto flex max-w-2xl flex-col gap-4 px-4 py-6 md:px-8 lg:max-w-6xl">
      <motion.div
        initial={{ opacity: 0, y: -8 }}
        animate={{ opacity: 1, y: 0 }}
        transition={{ duration: 0.4 }}
        className="flex items-center justify-between"
      >
        <h1 className="font-display text-[22px] font-bold text-text-primary">CoLab Workspace</h1>
        <Button fullWidth={false} onClick={() => setCreating((v) => !v)} className="gap-1.5">
          <Plus size={16} />
          {creating ? "Cancel" : "New Project"}
        </Button>
      </motion.div>

      <AnimatePresence>
        {error && (
          <motion.div initial={{ opacity: 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0 }}>
            <ErrorBanner message={error} />
          </motion.div>
        )}
      </AnimatePresence>

      <AnimatePresence>
        {creating && (
          <motion.div
            initial={{ opacity: 0, scale: 0.98 }}
            animate={{ opacity: 1, scale: 1 }}
            exit={{ opacity: 0, scale: 0.98 }}
            transition={TRANSITION_FAST}
          >
            <Card accentColor="var(--accent-teal)">
              <form onSubmit={handleCreate} className="flex flex-col gap-3">
                <Input
                  placeholder="Project name"
                  required
                  value={name}
                  onChange={(e) => setName(e.target.value)}
                />
                <Input
                  placeholder="Short description"
                  value={description}
                  onChange={(e) => setDescription(e.target.value)}
                />
                <Button type="submit" loading={submitting}>
                  Create project
                </Button>
              </form>
            </Card>
          </motion.div>
        )}
      </AnimatePresence>

      {loading && (
        <div className="flex flex-col gap-3">
          {[0, 1].map((i) => (
            <div key={i} className="h-24 animate-pulse rounded-[8px] bg-surface-subtle" />
          ))}
        </div>
      )}

      {!loading && !error && projects.length === 0 && !creating && (
        <Card className="flex flex-col items-center gap-2 py-10 text-center">
          <FolderKanban size={28} className="text-text-muted" />
          <p className="font-body text-[13.5px] text-text-muted">
            No workspaces yet. Create one to start collaborating in real time.
          </p>
        </Card>
      )}

      <div className="grid grid-cols-1 gap-3 lg:grid-cols-2 xl:grid-cols-3">
        {projects.map((p, i) => (
          <motion.div
            key={p.id}
            initial={{ opacity: 0, y: 16 }}
            animate={{ opacity: 1, y: 0 }}
            transition={{ duration: DURATION_SLOW, delay: i * 0.06, ease: EASE_STANDARD }}
          >
            <Link href={`/workspace/${p.id}`} className="block h-full">
              <Card glow interactive accentColor="var(--accent-teal)" className="flex h-full flex-col">
                <div className="flex items-center justify-between">
                  <h3 className="font-display text-[15px] font-semibold text-text-primary">{p.name}</h3>
                  <span className="flex items-center gap-1 font-mono text-[11px] text-text-muted">
                    <Users2 size={12} />
                    {p.members.length}
                  </span>
                </div>
                {p.description && (
                  <p className="mt-1 font-body text-[13px] text-text-secondary">{p.description}</p>
                )}
                <div className="mt-2.5 h-1.5 w-full overflow-hidden rounded-full bg-surface-subtle">
                  {/* scaleX transform instead of animating width — avoids layout
                      recalculation on every frame (width triggers reflow, transform doesn't). */}
                  <motion.div
                    className="h-full w-full origin-left rounded-full bg-accent-teal"
                    initial={{ scaleX: 0 }}
                    animate={{ scaleX: (p.manuscriptProgress ?? 0) }}
                    transition={{ duration: 0.6, ease: [0.22, 1, 0.36, 1] }}
                  />
                </div>
              </Card>
            </Link>
          </motion.div>
        ))}
      </div>
    </div>
  );
}
