"use client";

import { use, useState } from "react";
import { useRouter } from "next/navigation";
import { motion, AnimatePresence } from "framer-motion";
import {
  Trash2,
  MessageSquare,
  Sigma,
  FileText,
  ListChecks,
  Users2,
  Share2,
  ArrowLeft,
} from "lucide-react";
import { useFirestoreDoc } from "@/lib/hooks/useFirestoreDoc";
import { deleteProject, roleFor, canEdit as roleCanEdit } from "@/lib/firebase/workspace";
import { cn } from "@/lib/utils";
import { ErrorBanner, friendlyFirestoreError } from "@/components/ui/ErrorBanner";
import { ChatTab } from "@/components/workspace/ChatTab";
import { EquationsTab } from "@/components/workspace/EquationsTab";
import { DocumentsTab } from "@/components/workspace/DocumentsTab";
import { TasksMeetingsTab } from "@/components/workspace/TasksMeetingsTab";
import { MembersTab } from "@/components/workspace/MembersTab";
import { ShareModal } from "@/components/workspace/ShareModal";
import { PresenceStack } from "@/components/workspace/PresenceStack";
import { useAuth } from "@/lib/hooks/AuthProvider";
import type { CollabProject, CollabRole } from "@/lib/types";

const TABS = [
  { name: "Documents", Icon: FileText },
  { name: "Chat", Icon: MessageSquare },
  { name: "Equations", Icon: Sigma },
  { name: "Tasks & Meetings", Icon: ListChecks },
  { name: "Members", Icon: Users2 },
] as const;
type Tab = (typeof TABS)[number]["name"];

const ROLE_LABEL: Record<CollabRole, string> = {
  owner: "Owner",
  editor: "Editor",
  reviewer: "Reviewer",
  viewer: "Viewer",
};

/** Auxiliary tabs get a centred, scrollable column so their content is
 *  readable rather than stretched across a wide editor viewport. */
const CENTERED: Partial<Record<Tab, string>> = {
  Equations: "max-w-3xl",
  "Tasks & Meetings": "max-w-3xl",
  Members: "max-w-2xl",
};

export default function WorkspaceDetailPage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = use(params);
  return <WorkspaceDetailContent id={id} />;
}

/** Body split out so tests can render it without the `use(params)` Promise,
 *  which does not settle under jsdom. */
export function WorkspaceDetailContent({ id }: { id: string }) {
  const router = useRouter();
  const { user } = useAuth();
  const { data: project, error: subError } = useFirestoreDoc<CollabProject>(
    `collabs_groups/${id}`,
  );
  const [deleteError, setDeleteError] = useState<string | null>(null);
  const error = deleteError ?? subError;
  const [tab, setTab] = useState<Tab>("Documents");
  const [deleting, setDeleting] = useState(false);
  const [confirmDelete, setConfirmDelete] = useState(false);
  const [shareOpen, setShareOpen] = useState(false);
  const [activeDocId, setActiveDocId] = useState<string | null>(null);
  const [docFocus, setDocFocus] = useState(false);

  async function handleDelete() {
    setDeleting(true);
    try {
      await deleteProject(id);
      router.push("/workspace");
    } catch (err) {
      setDeleteError(friendlyFirestoreError(err as { code?: string; message?: string }));
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
      <div className="flex h-full flex-col gap-3 p-4 md:p-6">
        <div className="h-12 animate-pulse rounded-md bg-surface-subtle" />
        <div className="flex-1 animate-pulse rounded-md bg-surface-subtle" />
      </div>
    );
  }

  const myRole = roleFor(project, user?.uid);
  const isOwner = myRole === "owner";
  const canEdit = roleCanEdit(myRole);
  const centeredClass = CENTERED[tab];
  const chromeHidden = docFocus && tab === "Documents";

  return (
    <div className="flex h-full flex-col overflow-hidden bg-page-bg">
      <ShareModal project={project} open={shareOpen} onClose={() => setShareOpen(false)} />

      {/* ── Toolbar ─────────────────────────────────────────────────────── */}
      <header className="flex h-[52px] shrink-0 items-center gap-3 border-b border-border bg-surface px-3 md:px-4">
        <button
          type="button"
          onClick={() => router.push("/workspace")}
          aria-label="Back to workspaces"
          className="flex h-8 w-8 items-center justify-center rounded-md text-text-muted transition-colors hover:bg-surface-subtle hover:text-text-primary"
        >
          <ArrowLeft size={16} />
        </button>
        <h1 className="truncate font-display text-h3 font-semibold text-text-primary">
          {project.name}
        </h1>
        {myRole && (
          <span className="hidden shrink-0 rounded-full bg-surface-subtle px-2 py-0.5 data text-[10px] uppercase tracking-wide text-text-muted sm:inline">
            {ROLE_LABEL[myRole]}
          </span>
        )}

        <div className="ml-auto flex shrink-0 items-center gap-2">
          <PresenceStack projectId={project.id} activeDocId={activeDocId} />
          <button
            type="button"
            onClick={() => setShareOpen(true)}
            className="flex items-center gap-2 rounded-md border border-border px-3 py-1.5 font-body text-body-s font-medium text-text-secondary transition-colors hover:border-primary/40 hover:text-text-primary"
          >
            <Share2 size={14} />
            <span className="hidden sm:inline">Share</span>
          </button>
          {isOwner &&
            (confirmDelete ? (
              <div className="flex items-center gap-2">
                <button
                  type="button"
                  onClick={handleDelete}
                  disabled={deleting}
                  className="rounded-md px-2 py-1.5 font-body text-[12px] font-semibold text-notification transition-colors hover:bg-notification/10 disabled:opacity-50"
                >
                  {deleting ? "Deleting…" : "Delete for everyone"}
                </button>
                <button
                  type="button"
                  onClick={() => setConfirmDelete(false)}
                  disabled={deleting}
                  className="rounded-md px-2 py-1.5 font-body text-[12px] text-text-muted transition-colors hover:text-text-primary disabled:opacity-50"
                >
                  Keep
                </button>
              </div>
            ) : (
              <button
                type="button"
                onClick={() => setConfirmDelete(true)}
                aria-label="Delete project"
                className="flex h-8 w-8 items-center justify-center rounded-md text-text-muted transition-colors hover:bg-notification/10 hover:text-notification"
              >
                <Trash2 size={15} />
              </button>
            ))}
        </div>
      </header>

      {error && (
        <div className="shrink-0 border-b border-border px-4 py-2">
          <ErrorBanner message={error} />
        </div>
      )}

      {/* ── Mobile tab strip ────────────────────────────────────────────── */}
      {!chromeHidden && (
        <nav className="flex shrink-0 gap-1 overflow-x-auto border-b border-border bg-surface p-1.5 md:hidden">
          {TABS.map((t) => (
            <button
              key={t.name}
              type="button"
              onClick={() => setTab(t.name)}
              className={cn(
                "flex shrink-0 items-center gap-2 rounded-md px-3 py-1.5 font-body text-body-s font-medium transition-colors",
                tab === t.name
                  ? "bg-primary text-text-on-primary"
                  : "text-text-secondary hover:bg-surface-subtle hover:text-text-primary",
              )}
            >
              <t.Icon size={13} />
              {t.name}
            </button>
          ))}
        </nav>
      )}

      {/* ── Body: activity rail + content ───────────────────────────────── */}
      <div className="flex min-h-0 flex-1">
        {!chromeHidden && (
          <nav className="hidden w-14 shrink-0 flex-col items-center gap-1 border-r border-border bg-surface py-2 md:flex">
            {TABS.map((t) => (
              <button
                key={t.name}
                type="button"
                onClick={() => setTab(t.name)}
                title={t.name}
                aria-label={t.name}
                aria-current={tab === t.name ? "page" : undefined}
                className={cn(
                  "group relative flex h-10 w-10 items-center justify-center rounded-md transition-colors",
                  tab === t.name
                    ? "bg-primary text-text-on-primary"
                    : "text-text-muted hover:bg-surface-subtle hover:text-text-primary",
                )}
              >
                {tab === t.name && (
                  <motion.span
                    layoutId="workspace-rail-active"
                    className="absolute -left-2 h-5 w-[2px] rounded-r bg-primary"
                    transition={{ type: "spring", stiffness: 400, damping: 32 }}
                  />
                )}
                <t.Icon size={18} />
                <span className="pointer-events-none absolute left-full z-20 ml-2 hidden whitespace-nowrap rounded-md bg-text-primary px-2 py-1 font-body text-[11px] font-medium text-surface group-hover:block">
                  {t.name}
                </span>
              </button>
            ))}
          </nav>
        )}

        <div className="min-w-0 flex-1 overflow-hidden">
          <AnimatePresence mode="wait">
            <motion.div
              key={tab}
              initial={{ opacity: 0 }}
              animate={{ opacity: 1 }}
              exit={{ opacity: 0 }}
              transition={{ duration: 0.15 }}
              className="h-full"
            >
              {tab === "Documents" ? (
                <DocumentsTab
                  project={project}
                  canEdit={canEdit}
                  onActiveDocChange={setActiveDocId}
                  onFocusChange={setDocFocus}
                />
              ) : tab === "Chat" ? (
                <ChatTab projectId={project.id} />
              ) : (
                <div
                  className={cn(
                    "mx-auto h-full overflow-y-auto p-4 md:p-6",
                    centeredClass,
                  )}
                >
                  {tab === "Equations" && (
                    <EquationsTab
                      projectId={project.id}
                      initialLatex={project.recentEquations}
                    />
                  )}
                  {tab === "Tasks & Meetings" && <TasksMeetingsTab projectId={project.id} />}
                  {tab === "Members" && (
                    <MembersTab project={project} onManageSharing={() => setShareOpen(true)} />
                  )}
                </div>
              )}
            </motion.div>
          </AnimatePresence>
        </div>
      </div>
    </div>
  );
}
