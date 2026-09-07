"use client";

import { Settings2 } from "lucide-react";
import { Card } from "@/components/ui/Card";
import { roleFor } from "@/lib/firebase/workspace";
import { useAuth } from "@/lib/hooks/AuthProvider";
import type { CollabProject, CollabRole } from "@/lib/types";

const ROLE_LABEL: Record<CollabRole, string> = {
  owner: "Owner",
  editor: "Editor",
  reviewer: "Reviewer",
  viewer: "Viewer",
};

/** Read-only roster. Adding/removing collaborators and changing roles lives in
 *  the Share modal (Overleaf-style), opened via onManageSharing. */
export function MembersTab({
  project,
  onManageSharing,
}: {
  project: CollabProject;
  onManageSharing: () => void;
}) {
  const { user } = useAuth();
  const canManage = roleFor(project, user?.uid) === "owner";

  return (
    <Card>
      <div className="flex items-center justify-between">
        <h3 className="font-display text-body font-semibold text-text-primary">
          Members ({project.members.length})
        </h3>
        <button
          type="button"
          onClick={onManageSharing}
          className="flex items-center gap-2 rounded-md border border-border px-3 py-1 font-body text-[12px] font-medium text-text-secondary transition-colors hover:border-primary/40 hover:text-text-primary"
        >
          <Settings2 size={13} />
          {canManage ? "Manage sharing" : "Sharing"}
        </button>
      </div>
      <div className="mt-2 flex flex-col gap-2">
        {project.members.map((m) => {
          const role: CollabRole = m.uid === project.ownerUid ? "owner" : m.role ?? "editor";
          return (
            <div key={m.uid} className="flex items-center gap-3 rounded-md bg-surface-subtle px-3 py-2">
              <div className="flex h-8 w-8 items-center justify-center rounded-full bg-primary font-display text-[12px] font-bold text-text-on-primary">
                {m.name.slice(0, 1).toUpperCase()}
              </div>
              <div className="min-w-0">
                <p className="truncate font-body text-body-s font-medium text-text-primary">{m.name}</p>
                <p className="truncate font-body text-[12px] text-text-secondary">{m.email}</p>
              </div>
              <span className="ml-auto data text-[10px] uppercase tracking-wide text-text-muted">
                {ROLE_LABEL[role]}
              </span>
            </div>
          );
        })}
      </div>
    </Card>
  );
}
