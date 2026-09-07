"use client";

import { useMemo, useState } from "react";
import Link from "next/link";
import { motion, AnimatePresence } from "framer-motion";
import { Plus, FolderKanban, Users2 } from "lucide-react";
import { Card } from "@/components/ui/Card";
import { Button } from "@/components/ui/Button";
import { Input } from "@/components/ui/Input";
import { ErrorBanner, friendlyFirestoreError } from "@/components/ui/ErrorBanner";
import { useAuth } from "@/lib/hooks/AuthProvider";
import { useFirestoreCollection } from "@/lib/hooks/useFirestoreCollection";
import { subscribeProjects, createProject, roleFor } from "@/lib/firebase/workspace";
import type { CollabProject, CollabRole } from "@/lib/types";
import { cn } from "@/lib/utils";
import { TRANSITION_FAST, DURATION_SLOW, EASE_STANDARD } from "@/lib/motion";

type ScopeFilter = "all" | "owned" | "shared";
const SCOPES: { key: ScopeFilter; label: string }[] = [
  { key: "all", label: "All" },
  { key: "owned", label: "Owned by me" },
  { key: "shared", label: "Shared with me" },
];
const ROLE_LABEL: Record<CollabRole, string> = {
  owner: "Owner",
  editor: "Editor",
  reviewer: "Reviewer",
  viewer: "Viewer",
};

function relTime(ts?: number) {
  if (!ts) return null;
  const s = Math.round((Date.now() - ts) / 1000);
  if (s < 60) return "just now";
  if (s < 3600) return `${Math.round(s / 60)}m ago`;
  if (s < 86400) return `${Math.round(s / 3600)}h ago`;
  return `${Math.round(s / 86400)}d ago`;
}

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
  const [scope, setScope] = useState<ScopeFilter>("all");

  const shownProjects = useMemo(() => {
    return projects
      .filter((p) => {
        if (scope === "owned" && p.ownerUid !== user?.uid) return false;
        if (scope === "shared" && p.ownerUid === user?.uid) return false;
        return true;
      })
      .sort((a, b) => (b.updatedAt ?? 0) - (a.updatedAt ?? 0));
  }, [projects, scope, user?.uid]);

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
        <h1 className="font-display text-display-m font-bold text-text-primary">CoLab Workspace</h1>
        <Button fullWidth={false} onClick={() => setCreating((v) => !v)} className="gap-2">
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
            <div key={i} className="h-24 animate-pulse rounded-md bg-surface-subtle" />
          ))}
        </div>
      )}

      {!loading && !error && projects.length === 0 && !creating && (
        <Card className="flex flex-col items-center gap-2 py-10 text-center">
          <FolderKanban size={28} className="text-text-muted" />
          <p className="font-body text-body-s text-text-muted">
            No workspaces yet. Create one to start collaborating in real time.
          </p>
        </Card>
      )}

      {!loading && projects.length > 0 && (
        <div className="flex gap-1 self-start rounded-full bg-surface-subtle p-1">
          {SCOPES.map((s) => (
            <button
              key={s.key}
              type="button"
              onClick={() => setScope(s.key)}
              className={cn(
                "rounded-full px-3 py-1 font-body text-body-s font-medium transition-colors",
                scope === s.key ? "bg-primary text-text-on-primary" : "text-text-secondary hover:text-text-primary"
              )}
            >
              {s.label}
            </button>
          ))}
        </div>
      )}

      {!loading && projects.length > 0 && shownProjects.length === 0 && (
        <p className="py-8 text-center font-body text-body-s text-text-muted">
          No projects in this view.
        </p>
      )}

      <div className="grid grid-cols-1 gap-3 lg:grid-cols-2 xl:grid-cols-3">
        {shownProjects.map((p, i) => {
          const myRole = roleFor(p, user?.uid);
          const updated = relTime(p.updatedAt);
          return (
          <motion.div
            key={p.id}
            initial={{ opacity: 0, y: 16 }}
            animate={{ opacity: 1, y: 0 }}
            transition={{ duration: DURATION_SLOW, delay: i * 0.06, ease: EASE_STANDARD }}
          >
            <Link href={`/workspace/${p.id}`} className="block h-full">
              <Card glow interactive accentColor="var(--accent-teal)" className="flex h-full flex-col">
                <div className="flex items-start justify-between gap-2">
                  <h3 className="font-display text-h3 font-semibold text-text-primary">{p.name}</h3>
                  <span className="flex shrink-0 items-center gap-1 font-mono text-[11px] text-text-muted">
                    <Users2 size={12} />
                    {p.members.length}
                  </span>
                </div>
                {p.description && (
                  <p className="mt-1 font-body text-body-s text-text-secondary">{p.description}</p>
                )}
                <div className="mt-auto flex items-center gap-2 pt-3 font-body text-[11px] text-text-muted">
                  {myRole && (
                    <span className="rounded-full bg-surface-subtle px-1.5 py-0.5 font-mono uppercase tracking-wide">
                      {ROLE_LABEL[myRole]}
                    </span>
                  )}
                  {updated && <span>Updated {updated}{p.updatedByName ? ` · ${p.updatedByName}` : ""}</span>}
                </div>
              </Card>
            </Link>
          </motion.div>
          );
        })}
      </div>
    </div>
  );
}
