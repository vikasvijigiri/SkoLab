"use client";

import { useState } from "react";
import {
  ClipboardList,
  MessageSquare,
  Sigma,
  ListChecks,
  Share2,
  PanelRightClose,
  PanelRightOpen,
  X,
} from "lucide-react";
import { cn } from "@/lib/utils";
import { ChatTab } from "@/components/workspace/ChatTab";
import { EquationsTab } from "@/components/workspace/EquationsTab";
import { TasksMeetingsTab } from "@/components/workspace/TasksMeetingsTab";
import { QuickReferencePanel, computeQuickReferenceStatus } from "@/components/workspace/QuickReferencePanel";
import type { JournalTemplate } from "@/components/workspace/ResearchTools";
import type { CollabMember } from "@/lib/types";

type DockTab = "quickref" | "chat" | "equations" | "tasks";

const DOCK_TABS: { id: DockTab; label: string; Icon: typeof ClipboardList }[] = [
  { id: "quickref", label: "Quick reference", Icon: ClipboardList },
  { id: "chat", label: "Chat", Icon: MessageSquare },
  { id: "equations", label: "Equations", Icon: Sigma },
  { id: "tasks", label: "Tasks", Icon: ListChecks },
];

/**
 * The unified dock: Quick reference / Chat / Equations / Tasks share one
 * panel next to the editor (decisions/0019 moved Chat and Equations here off
 * their own destinations; Tasks & Meetings joined the same pattern later —
 * a project's tasks/meetings don't need a whole separate screen any more
 * than its chat or equations did). Collapsible to a 40px rail with status
 * dots rather than disappearing outright, so a live signal and one click to
 * reopen are always available.
 */
export function DocumentDock({
  projectId,
  members,
  documentBody,
  initialLatex,
  template,
  onOpenTemplates,
  onOpenShare,
  className,
  hideCollapse = false,
  onRequestClose,
}: {
  projectId: string;
  /** The project's real member list — forwarded to ChatTab for @mention
   *  parsing (decisions/0022). */
  members: CollabMember[];
  documentBody: string;
  initialLatex: string;
  template: JournalTemplate | undefined;
  onOpenTemplates: () => void;
  onOpenShare: () => void;
  /** Merged onto the panel's root — lets the mobile bottom sheet drop the
   *  left border and stretch to fill its container. */
  className?: string;
  /** Mobile bottom-sheet mode: dismissal is closing the sheet, not
   *  collapsing to a rail, so the collapse control becomes a close button. */
  hideCollapse?: boolean;
  onRequestClose?: () => void;
}) {
  const [collapsed, setCollapsed] = useState(false);
  const [activeTab, setActiveTab] = useState<DockTab>("quickref");
  const [chatUnread, setChatUnread] = useState(false);

  const abstractStatus = computeQuickReferenceStatus(documentBody, template);
  const abstractDotColor = abstractStatus.abstractOverLimit
    ? "bg-notification"
    : abstractStatus.abstractNearLimit
      ? "bg-accent-amber"
      : "bg-accent-emerald";

  // Chat's `onSnapshot` listener (unlike Equations' remount-to-refresh
  // blackboard below) stays mounted at all times — collapsed rail, expanded
  // panel on another tab, doesn't matter — purely hidden via CSS. That's
  // what lets the unread dot stay accurate no matter what the dock is
  // currently showing.
  const chatPanel = (
    <div className={cn("h-full p-3", !collapsed && activeTab === "chat" ? "" : "hidden")}>
      <ChatTab
        projectId={projectId}
        members={members}
        active={!collapsed && activeTab === "chat"}
        onUnreadChange={setChatUnread}
      />
    </div>
  );

  if (collapsed && !hideCollapse) {
    return (
      <aside className="flex w-10 shrink-0 flex-col items-center gap-3.5 border-l border-border bg-surface-subtle py-3.5">
        <button
          type="button"
          onClick={() => setCollapsed(false)}
          aria-label="Show panel"
          title="Show panel"
          className="flex h-7 w-7 items-center justify-center rounded-md text-text-muted transition-colors hover:bg-surface hover:text-text-primary"
        >
          <PanelRightOpen size={15} />
        </button>
        {template?.specs?.abstractCharLimit && (
          <span
            className={cn("h-2 w-2 shrink-0 rounded-full", abstractDotColor)}
            title="Abstract length status"
            aria-label="Abstract length status"
          />
        )}
        {chatUnread && (
          <span
            className="h-2 w-2 shrink-0 rounded-full bg-primary"
            title="Unread chat message"
            aria-label="Unread chat message"
          />
        )}
        {chatPanel}
      </aside>
    );
  }

  return (
    <aside
      className={cn(
        "flex w-full shrink-0 flex-col border-l border-border bg-surface-subtle md:w-[320px]",
        className,
      )}
    >
      <div className="flex shrink-0 items-center gap-0.5 px-2 pt-2">
        {DOCK_TABS.map((t) => (
          <button
            key={t.id}
            type="button"
            onClick={() => setActiveTab(t.id)}
            className={cn(
              "relative flex items-center gap-1.5 rounded-md px-2.5 py-2 font-body text-[12px] font-medium transition-colors",
              activeTab === t.id
                ? "bg-surface text-text-primary"
                : "text-text-secondary hover:text-text-primary",
            )}
          >
            <t.Icon size={13} />
            {t.label === "Quick reference" ? "Quick ref" : t.label}
            {t.id === "chat" && chatUnread && activeTab !== "chat" && (
              <span className="absolute right-1 top-1.5 h-1.5 w-1.5 rounded-full bg-primary" />
            )}
          </button>
        ))}
        <button
          type="button"
          onClick={onOpenShare}
          aria-label="Share project"
          title="Share project"
          className="ml-auto flex h-[26px] w-[26px] shrink-0 items-center justify-center rounded-md text-text-muted transition-colors hover:bg-surface hover:text-text-primary"
        >
          <Share2 size={14} />
        </button>
        {hideCollapse ? (
          onRequestClose && (
            <button
              type="button"
              onClick={onRequestClose}
              aria-label="Close panel"
              title="Close panel"
              className="flex h-[26px] w-[26px] shrink-0 items-center justify-center rounded-md text-text-muted transition-colors hover:bg-surface hover:text-text-primary"
            >
              <X size={14} />
            </button>
          )
        ) : (
          <button
            type="button"
            onClick={() => setCollapsed(true)}
            aria-label="Collapse panel"
            title="Collapse panel"
            className="flex h-[26px] w-[26px] shrink-0 items-center justify-center rounded-md text-text-muted transition-colors hover:bg-surface hover:text-text-primary"
          >
            <PanelRightClose size={14} />
          </button>
        )}
      </div>
      <div className="mt-2 h-px shrink-0 bg-border" />

      <div className="min-h-0 flex-1 overflow-y-auto">
        {activeTab === "quickref" && (
          <QuickReferencePanel documentBody={documentBody} template={template} onOpenTemplates={onOpenTemplates} />
        )}
        {chatPanel}
        {activeTab === "equations" && (
          <div className="p-3">
            <EquationsTab projectId={projectId} initialLatex={initialLatex} />
          </div>
        )}
        {activeTab === "tasks" && (
          <div className="p-3">
            <TasksMeetingsTab projectId={projectId} />
          </div>
        )}
      </div>
    </aside>
  );
}
