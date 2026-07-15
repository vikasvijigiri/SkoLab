"use client";

import { useState } from "react";
import { UserPlus } from "lucide-react";
import { Input } from "@/components/ui/Input";
import { Button } from "@/components/ui/Button";
import { Card } from "@/components/ui/Card";
import { ErrorBanner, friendlyFirestoreError } from "@/components/ui/ErrorBanner";
import { findResearcherByEmail, inviteMember } from "@/lib/firebase/workspace";
import { logPeerInvite } from "@/lib/api/endpoints";
import { useAuth } from "@/lib/hooks/AuthProvider";
import type { CollabProject } from "@/lib/types";

export function MembersTab({ project }: { project: CollabProject }) {
  const { user } = useAuth();
  const [email, setEmail] = useState("");
  const [status, setStatus] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [inviting, setInviting] = useState(false);

  async function handleInvite(e: React.FormEvent) {
    e.preventDefault();
    if (!email.trim()) return;
    setInviting(true);
    setStatus(null);
    setError(null);
    try {
      const researcher = await findResearcherByEmail(email.trim());
      if (researcher) {
        await inviteMember(project.id, {
          uid: researcher.uid,
          name: researcher.name,
          email: researcher.email,
          phone: researcher.phone,
        });
        setStatus(`${researcher.name} added to the workspace.`);
        setEmail("");
      } else {
        if (user) await logPeerInvite(user.uid, { email: email.trim() }).catch(() => {});
        setStatus("No SkoLab account found for that email — an invite was logged.");
      }
    } catch (err) {
      setError(friendlyFirestoreError(err as { code?: string; message?: string }));
    } finally {
      setInviting(false);
    }
  }

  return (
    <div className="flex flex-col gap-4">
      <Card>
        <h3 className="font-display text-[14px] font-semibold text-text-primary">
          Members ({project.members.length})
        </h3>
        <div className="mt-2 flex flex-col gap-1.5">
          {project.members.map((m) => (
            <div key={m.uid} className="flex items-center gap-3 rounded-[8px] bg-surface-subtle px-3 py-2">
              <div className="flex h-8 w-8 items-center justify-center rounded-full bg-primary font-display text-[12px] font-bold text-text-on-primary">
                {m.name.slice(0, 1).toUpperCase()}
              </div>
              <div className="min-w-0">
                <p className="truncate font-body text-[13px] font-medium text-text-primary">{m.name}</p>
                <p className="truncate font-body text-[12px] text-text-secondary">{m.email}</p>
              </div>
              {m.uid === project.ownerUid && (
                <span className="ml-auto font-mono text-[10px] uppercase tracking-wide text-text-muted">Owner</span>
              )}
            </div>
          ))}
        </div>
      </Card>

      <Card>
        <h3 className="flex items-center gap-1.5 font-display text-[14px] font-semibold text-text-primary">
          <UserPlus size={15} className="text-primary" />
          Invite a collaborator
        </h3>
        {error && (
          <div className="mt-2.5">
            <ErrorBanner message={error} />
          </div>
        )}
        <form onSubmit={handleInvite} className="mt-2.5 flex gap-2">
          <Input
            type="email"
            placeholder="colleague@university.edu"
            value={email}
            onChange={(e) => setEmail(e.target.value)}
          />
          <Button type="submit" fullWidth={false} className="w-28" loading={inviting}>
            Invite
          </Button>
        </form>
        {status && <p className="mt-2 font-body text-[12.5px] text-text-secondary">{status}</p>}
      </Card>
    </div>
  );
}
