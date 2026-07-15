"use client";

import { use, useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import { doc, onSnapshot } from "firebase/firestore";
import { motion, AnimatePresence } from "framer-motion";
import { Trash2, MessageSquare, Sigma, FileText, ListChecks, Users2 } from "lucide-react";
import { requireDb } from "@/lib/firebase/client";
import { deleteProject } from "@/lib/firebase/workspace";
import { cn } from "@/lib/utils";
import { ErrorBanner, friendlyFirestoreError } from "@/components/ui/ErrorBanner";
import { RailShell } from "@/components/layout/RailShell";
import { ChatTab } from "@/components/workspace/ChatTab";
import { EquationsTab } from "@/components/workspace/EquationsTab";
import { ManuscriptTab } from "@/components/workspace/ManuscriptTab";
import { TasksMeetingsTab } from "@/components/workspace/TasksMeetingsTab";
import { MembersTab } from "@/components/workspace/MembersTab";
import { useAuth } from "@/lib/hooks/AuthProvider";
import type { CollabProject } from "@/lib/types";

const TABS = [
  { name: "Chat", Icon: MessageSquare },
  { name: "Equations", Icon: Sigma },
  { name: "Manuscript", Icon: FileText },
  { name: "Tasks & Meetings", Icon: ListChecks },
  { name: "Members", Icon: Users2 },
] as const;
type Tab = (typeof TABS)[number]["name"];

export default function WorkspaceDetailPage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = use(params);
  const router = useRouter();
  const { user } = useAuth();
  const [project, setProject] = useState<CollabProject | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [tab, setTab] = useState<Tab>("Chat");
  const [deleting, setDeleting] = useState(false);

  useEffect(() => {
    const unsub = onSnapshot(
      doc(requireDb(), "collabs_groups", id),
      (snap) => {
        setProject(snap.exists() ? ({ id: snap.id, ...snap.data() } as CollabProject) : null);
        setError(null);
      },
      (err) => setError(friendlyFirestoreError(err))
    );
    return unsub;
  }, [id]);

  async function handleDelete() {
    if (!confirm("Delete this workspace for everyone? This can't be undone.")) return;
    setDeleting(true);
    try {
      await deleteProject(id);
      router.push("/workspace");
    } catch (err) {
      setError(friendlyFirestoreError(err as { code?: string; message?: string }));
    } finally {
      setDeleting(false);
    }
  }

  if (error && !project) {
    return (
      <div className="mx-auto max-w-3xl px-4 py-6 md:px-8">
        <ErrorBanner message={error} />
      </div>
    );
  }

  if (!project) {
    return (
      <div className="mx-auto max-w-3xl px-4 py-6 md:px-8">
        <div className="h-64 animate-pulse rounded-[8px] bg-surface-subtle" />
      </div>
    );
  }

  const isOwner = project.ownerUid === user?.uid;

  return (
    <div className="mx-auto flex max-w-3xl flex-col gap-4 px-4 py-6 md:px-8 lg:max-w-5xl">
      {error && <ErrorBanner message={error} />}

      <motion.div
        initial={{ opacity: 0, y: -8 }}
        animate={{ opacity: 1, y: 0 }}
        transition={{ duration: 0.4 }}
        className="flex items-start justify-between gap-3"
      >
        <div>
          <h1 className="font-display text-[20px] font-bold text-text-primary">{project.name}</h1>
          {project.description && (
            <p className="mt-0.5 font-body text-[13.5px] text-text-secondary">{project.description}</p>
          )}
        </div>
        {isOwner && (
          <motion.button
            onClick={handleDelete}
            disabled={deleting}
            whileHover={{ scale: 1.05 }}
            whileTap={{ scale: 0.95 }}
            className="flex shrink-0 cursor-pointer items-center gap-1.5 rounded-md px-2.5 py-1.5 font-body text-[12.5px] font-medium text-notification transition-colors hover:bg-notification/10 disabled:opacity-50"
          >
            <Trash2 size={14} />
            {deleting ? "Deleting…" : "Delete"}
          </motion.button>
        )}
      </motion.div>

      <div className="flex gap-1 overflow-x-auto rounded-full bg-surface-subtle p-1 lg:hidden">
        {TABS.map((t) => (
          <motion.button
            key={t.name}
            onClick={() => setTab(t.name)}
            whileTap={{ scale: 0.96 }}
            transition={{ duration: 0.16, ease: [0.22, 1, 0.36, 1] }}
            className={cn(
              "relative flex shrink-0 items-center gap-1.5 rounded-full px-3.5 py-1.5 font-body text-[12.5px] font-medium transition-colors duration-[var(--motion-fast)]",
              tab === t.name
                ? "text-text-on-primary"
                : "text-text-secondary hover:bg-surface/60 hover:text-text-primary"
            )}
            style={{ transitionTimingFunction: "var(--ease-standard)" }}
          >
            {tab === t.name && (
              <motion.span
                layoutId="workspace-tab-active-mobile"
                className="absolute inset-0 rounded-full bg-primary"
                transition={{ type: "spring", stiffness: 400, damping: 32 }}
              />
            )}
            <t.Icon size={13} className="relative z-10" />
            <span className="relative z-10">{t.name}</span>
          </motion.button>
        ))}
      </div>

      <RailShell
        rail={
          <nav className="flex flex-col gap-1 rounded-lg bg-surface-subtle p-1.5">
            {TABS.map((t) => (
              <motion.button
                key={t.name}
                onClick={() => setTab(t.name)}
                whileTap={{ scale: 0.97 }}
                transition={{ duration: 0.16, ease: [0.22, 1, 0.36, 1] }}
                className={cn(
                  "relative flex items-center gap-2 rounded-md px-3 py-2 font-body text-[13px] font-medium transition-colors duration-[var(--motion-fast)]",
                  tab === t.name
                    ? "text-text-on-primary"
                    : "text-text-secondary hover:bg-surface/60 hover:text-text-primary"
                )}
                style={{ transitionTimingFunction: "var(--ease-standard)" }}
              >
                {tab === t.name && (
                  <motion.span
                    layoutId="workspace-tab-active-desktop"
                    className="absolute inset-0 rounded-md bg-primary"
                    transition={{ type: "spring", stiffness: 400, damping: 32 }}
                  />
                )}
                <t.Icon size={14} className="relative z-10" />
                <span className="relative z-10">{t.name}</span>
              </motion.button>
            ))}
          </nav>
        }
        railWidth="200px"
        stickyRail
        mobileRail="hidden"
      >
        <AnimatePresence mode="wait">
          <motion.div
            key={tab}
            initial={{ opacity: 0 }}
            animate={{ opacity: 1 }}
            exit={{ opacity: 0 }}
            transition={{ duration: 0.2 }}
          >
            {tab === "Chat" && <ChatTab projectId={project.id} />}
            {tab === "Equations" && <EquationsTab projectId={project.id} initialLatex={project.recentEquations} />}
            {tab === "Manuscript" && (
              <ManuscriptTab
                projectId={project.id}
                initialDraft={project.manuscriptDraft}
                initialProgress={project.manuscriptProgress}
              />
            )}
            {tab === "Tasks & Meetings" && <TasksMeetingsTab projectId={project.id} />}
            {tab === "Members" && <MembersTab project={project} />}
          </motion.div>
        </AnimatePresence>
      </RailShell>
    </div>
  );
}
