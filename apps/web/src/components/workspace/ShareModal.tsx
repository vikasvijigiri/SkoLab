"use client";

import { useState } from "react";
import { Link2, Check } from "lucide-react";
import { Modal } from "@/components/ui/Modal";
import { Input } from "@/components/ui/Input";
import { Button } from "@/components/ui/Button";
import { ErrorBanner, friendlyFirestoreError } from "@/components/ui/ErrorBanner";
import {
  findResearcherByEmail,
  inviteMember,
  updateMemberRole,
  removeMemberByUid,
  roleFor,
} from "@/lib/firebase/workspace";
import { logPeerInvite } from "@/lib/api/endpoints";
import { useAuth } from "@/lib/hooks/AuthProvider";
import type { CollabProject, CollabRole } from "@/lib/types";

const ASSIGNABLE: CollabRole[] = ["editor", "reviewer", "viewer"];
const cap = (s: string) => s.charAt(0).toUpperCase() + s.slice(1);
const ROLE_HINT: Record<CollabRole, string> = {
  owner: "Full access · manages sharing",
  editor: "Can edit documents",
  reviewer: "Can read and comment, not edit",
  viewer: "Read-only",
};

export function ShareModal({
  project,
  open,
  onClose,
}: {
  project: CollabProject;
  open: boolean;
  onClose: () => void;
}) {
  const { user, getIdToken } = useAuth();
  const myRole = roleFor(project, user?.uid);
  const canManage = myRole === "owner";

  const [email, setEmail] = useState("");
  const [inviteRole, setInviteRole] = useState<CollabRole>("editor");
  const [status, setStatus] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [copied, setCopied] = useState(false);

  async function handleInvite(e: React.FormEvent) {
    e.preventDefault();
    if (!email.trim() || !canManage) return;
    setBusy(true);
    setStatus(null);
    setError(null);
    try {
      const researcher = await findResearcherByEmail(email.trim());
      if (researcher) {
        if (project.memberUids.includes(researcher.uid)) {
          setStatus(`${researcher.name} is already a collaborator.`);
        } else {
          await inviteMember(
            project.id,
            { uid: researcher.uid, name: researcher.name, email: researcher.email, phone: researcher.phone },
            inviteRole
          );
          setStatus(`${researcher.name} added as ${inviteRole}.`);
        }
        setEmail("");
      } else {
        if (user) {
          const idToken = await getIdToken();
          await logPeerInvite(idToken, user.uid, { email: email.trim() }).catch(() => {});
        }
        setStatus("No SkoLab account for that email yet — an invite was logged.");
      }
    } catch (err) {
      setError(friendlyFirestoreError(err as { code?: string; message?: string }));
    } finally {
      setBusy(false);
    }
  }

  async function copyLink() {
    try {
      await navigator.clipboard.writeText(`${window.location.origin}/workspace/${project.id}`);
      setCopied(true);
      setTimeout(() => setCopied(false), 1800);
    } catch {
      /* clipboard blocked — no-op */
    }
  }

  return (
    <Modal open={open} onClose={onClose} title={`Share “${project.name}”`} widthClass="max-w-lg">
      {error && (
        <div className="mb-3">
          <ErrorBanner message={error} />
        </div>
      )}

      {canManage && (
        <form onSubmit={handleInvite} className="mb-4 flex flex-col gap-2">
          <p className="font-body text-body-s font-medium text-text-secondary">
            Invite a collaborator
          </p>
          <div className="flex gap-2">
            <Input
              type="email"
              placeholder="colleague@university.edu"
              value={email}
              onChange={(e) => setEmail(e.target.value)}
            />
            <select
              value={inviteRole}
              onChange={(e) => setInviteRole(e.target.value as CollabRole)}
              className="shrink-0 rounded-md border border-border-input bg-surface-input px-3 font-body text-body-s text-text-primary outline-none focus:border-primary"
            >
              {ASSIGNABLE.map((r) => (
                <option key={r} value={r}>
                  {cap(r)}
                </option>
              ))}
            </select>
            <Button type="submit" fullWidth={false} className="w-24" loading={busy}>
              Invite
            </Button>
          </div>
          {status && <p className="font-body text-[12px] text-text-secondary">{status}</p>}
        </form>
      )}

      <div className="flex flex-col gap-2">
        <p className="font-body text-body-s font-medium text-text-secondary">
          People with access ({project.members.length})
        </p>
        {project.members.map((m) => {
          const role: CollabRole = m.uid === project.ownerUid ? "owner" : m.role ?? "editor";
          const editable = canManage && m.uid !== project.ownerUid;
          return (
            <div
              key={m.uid}
              className="flex items-center gap-3 rounded-md bg-surface-subtle px-3 py-2"
            >
              <div className="flex h-8 w-8 shrink-0 items-center justify-center rounded-full bg-primary font-display text-[12px] font-bold text-text-on-primary">
                {m.name.slice(0, 1).toUpperCase()}
              </div>
              <div className="min-w-0 flex-1">
                <p className="truncate font-body text-body-s font-medium text-text-primary">{m.name}</p>
                <p className="truncate font-body text-[11.5px] text-text-muted">
                  {m.email || ROLE_HINT[role]}
                </p>
              </div>
              {editable ? (
                <>
                  <select
                    value={role}
                    onChange={(e) => updateMemberRole(project, m.uid, e.target.value as CollabRole)}
                    className="rounded-md border border-border-input bg-surface-input px-2 py-1 font-body text-[12px] text-text-primary outline-none focus:border-primary"
                  >
                    {ASSIGNABLE.map((r) => (
                      <option key={r} value={r}>
                        {cap(r)}
                      </option>
                    ))}
                  </select>
                  <button
                    type="button"
                    onClick={() => removeMemberByUid(project, m.uid)}
                    className="font-body text-[12px] font-medium text-text-muted transition-colors hover:text-notification"
                  >
                    Remove
                  </button>
                </>
              ) : (
                <span className="font-mono text-[10.5px] uppercase tracking-wide text-text-muted">
                  {role}
                </span>
              )}
            </div>
          );
        })}
      </div>

      <button
        type="button"
        onClick={copyLink}
        className="mt-4 flex w-full items-center justify-center gap-2 rounded-md border border-border px-3 py-2 font-body text-body-s font-medium text-text-secondary transition-colors hover:border-primary/40 hover:text-text-primary"
      >
        {copied ? <Check size={14} className="text-accent-teal" /> : <Link2 size={14} />}
        {copied ? "Link copied" : "Copy project link"}
      </button>
      <p className="mt-2 text-center font-body text-[11px] text-text-muted">
        Anyone you invite can open this link; it does not grant access on its own.
      </p>
    </Modal>
  );
}
