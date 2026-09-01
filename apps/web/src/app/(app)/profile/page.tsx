"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import { motion } from "framer-motion";
import { Pencil, TrendingUp, ShieldAlert } from "lucide-react";
import { Card } from "@/components/ui/Card";
import { Badge } from "@/components/ui/Badge";
import { Input } from "@/components/ui/Input";
import { Button } from "@/components/ui/Button";
import { AnimatedCounter } from "@/components/ui/AnimatedCounter";
import { ErrorBanner, friendlyFirestoreError } from "@/components/ui/ErrorBanner";
import { Reveal } from "@/components/ui/Reveal";
import { useAuth } from "@/lib/hooks/AuthProvider";
import { useMyProfile } from "@/lib/hooks/useMyProfile";
import { updateResearcherProfile, deleteResearcherProfile } from "@/lib/firebase/auth";
import { syncUserProfile, deleteUserAccount } from "@/lib/api/endpoints";

export default function ProfilePage() {
  const router = useRouter();
  const { user, getIdToken, signOut } = useAuth();
  const { firestoreProfile, author, loading, error: profileError, refetch } = useMyProfile();

  const [editing, setEditing] = useState(false);
  const [name, setName] = useState("");
  const [researchFocus, setResearchFocus] = useState("");
  const [academicStatus, setAcademicStatus] = useState("");
  const [about, setAbout] = useState("");
  const [saving, setSaving] = useState(false);
  const [saveError, setSaveError] = useState<string | null>(null);
  const [deleting, setDeleting] = useState(false);
  const [confirmDelete, setConfirmDelete] = useState(false);

  function startEditing() {
    setName(firestoreProfile?.name ?? "");
    setResearchFocus(firestoreProfile?.researchFocus ?? "");
    setAcademicStatus(firestoreProfile?.academicStatus ?? "Researcher");
    setAbout(firestoreProfile?.about ?? "");
    setSaveError(null);
    setEditing(true);
  }

  async function handleSave() {
    if (!user) return;
    setSaving(true);
    setSaveError(null);
    try {
      await updateResearcherProfile(user.uid, { name, researchFocus, academicStatus, about });
      const idToken = await getIdToken();
      if (idToken) {
        await syncUserProfile(idToken, user.uid, name, researchFocus).catch((err) => {
          console.warn("[Profile] Backend profile sync failed:", err);
        });
      }
      setEditing(false);
    } catch (err) {
      setSaveError(friendlyFirestoreError(err as { code?: string; message?: string }));
    } finally {
      setSaving(false);
    }
  }

  async function handleDeleteAccount() {
    if (!user) return;
    setDeleting(true);
    try {
      const idToken = await getIdToken();
      if (idToken) {
        await deleteUserAccount(idToken, user.uid).catch(() => {
          // Best-effort — Postgres cleanup shouldn't block removing the Firestore profile / auth account.
        });
      }
      await deleteResearcherProfile(user.uid).catch(() => {});
      await user.delete().catch(() => {});
      await signOut();
      router.push("/");
    } finally {
      setDeleting(false);
    }
  }

  if (loading) {
    return (
      <div className="mx-auto max-w-2xl px-4 py-6 md:px-8">
        <div className="h-64 animate-pulse rounded-[8px] bg-surface-subtle" />
      </div>
    );
  }

  return (
    <div className="mx-auto max-w-2xl px-4 py-6 md:px-8 lg:max-w-4xl">
      {profileError && (
        <div className="mb-4">
          <ErrorBanner message={profileError} onRetry={refetch} />
        </div>
      )}
      <div className="grid grid-cols-1 gap-4 lg:grid-cols-[280px_1fr] lg:items-start lg:gap-6">
      <motion.div
        initial={{ opacity: 0, y: -8 }}
        animate={{ opacity: 1, y: 0 }}
        transition={{ duration: 0.4 }}
        className="lg:sticky lg:top-6"
      >
        <Card accentColor="var(--primary)">
          <div className="flex items-center gap-4 lg:flex-col lg:items-start lg:text-left">
            <div
              className="flex h-16 w-16 shrink-0 items-center justify-center rounded-full font-display text-[22px] font-bold text-white shadow-card"
              style={{ background: "var(--gradient-hero)" }}
            >
              {(firestoreProfile?.name || user?.displayName || "?").slice(0, 1).toUpperCase()}
            </div>
            <div className="min-w-0 flex-1">
              <h1 className="truncate font-display text-[19px] font-bold text-text-primary">
                {firestoreProfile?.name || user?.displayName || "Researcher"}
              </h1>
              <p className="truncate font-body text-[13px] text-text-secondary">{user?.email}</p>
              <Badge accentColor="var(--accent-teal)" className="mt-1.5 w-fit">
                {firestoreProfile?.academicStatus ?? "Researcher"}
              </Badge>
            </div>
          </div>
          {!editing && (
            <Button variant="outlined" fullWidth={false} onClick={startEditing} className="mt-4 gap-1.5 lg:w-full">
              <Pencil size={14} />
              Edit
            </Button>
          )}
        </Card>
      </motion.div>

      <div className="flex min-w-0 flex-col gap-4">
      {editing ? (
        <Reveal>
          <Card>
            <h2 className="font-display text-[15px] font-semibold text-text-primary">Edit profile</h2>
            {saveError && (
              <div className="mt-3">
                <ErrorBanner message={saveError} />
              </div>
            )}
            <div className="mt-3 flex flex-col gap-3">
              <Input label="Name" value={name} onChange={(e) => setName(e.target.value)} />
              <Input
                label="Research focus"
                value={researchFocus}
                onChange={(e) => setResearchFocus(e.target.value)}
              />
              <Input
                label="Academic status"
                value={academicStatus}
                onChange={(e) => setAcademicStatus(e.target.value)}
              />
              <div>
                <label
                  htmlFor="profile-about"
                  className="mb-1.5 block font-body text-[13px] font-medium text-text-secondary"
                >
                  About
                </label>
                <textarea
                  id="profile-about"
                  value={about}
                  onChange={(e) => setAbout(e.target.value)}
                  rows={4}
                  className="w-full rounded-sm border border-transparent bg-surface-subtle px-4 py-3 font-body text-[14px] text-text-primary outline-none focus:border-primary"
                />
              </div>
              <div className="flex gap-2">
                <Button fullWidth={false} onClick={handleSave} loading={saving} className="px-8">
                  Save
                </Button>
                <Button variant="text" fullWidth={false} onClick={() => setEditing(false)}>
                  Cancel
                </Button>
              </div>
            </div>
          </Card>
        </Reveal>
      ) : (
        <Reveal>
          <Card>
            <h2 className="font-display text-[15px] font-semibold text-text-primary">Research focus</h2>
            <p className="mt-1.5 font-body text-[13.5px] text-text-secondary">
              {firestoreProfile?.researchFocus || "Not set yet."}
            </p>
            {firestoreProfile?.about && (
              <>
                <h2 className="mt-4 font-display text-[15px] font-semibold text-text-primary">About</h2>
                <p className="mt-1.5 font-body text-[13.5px] leading-relaxed text-text-secondary">
                  {firestoreProfile.about}
                </p>
              </>
            )}
          </Card>
        </Reveal>
      )}

      {author && (author.expertise.length > 0 || author.skills.length > 0) && (
        <Reveal delay={0.04}>
          <Card>
            <h2 className="font-display text-[15px] font-semibold text-text-primary">Research Areas & Skills</h2>
            {author.expertise.length > 0 && (
              <div className="mt-3">
                <h3 className="font-body text-[11px] font-medium uppercase tracking-wide text-text-muted">
                  Research Areas
                </h3>
                <div className="mt-1.5 flex flex-wrap gap-1.5">
                  {author.expertise.map((e) => (
                    <Badge key={e} accentColor="var(--accent-indigo)">
                      {e}
                    </Badge>
                  ))}
                </div>
              </div>
            )}
            {author.skills.length > 0 && (
              <div className="mt-3">
                <h3 className="font-body text-[11px] font-medium uppercase tracking-wide text-text-muted">
                  Skills
                </h3>
                <div className="mt-1.5 flex flex-wrap gap-1.5">
                  {author.skills.map((s) => (
                    <Badge key={s} accentColor="var(--accent-amber)">
                      {s}
                    </Badge>
                  ))}
                </div>
              </div>
            )}
          </Card>
        </Reveal>
      )}

      {author && (
        <Reveal delay={0.08}>
          <Card>
            <h2 className="flex items-center gap-1.5 font-display text-[15px] font-semibold text-text-primary">
              <TrendingUp size={15} className="text-primary" />
              Metrics snapshot
            </h2>
            <div className="mt-3 flex gap-2.5">
              {[
                { label: "H-Index", value: author.h_index },
                { label: "i10-Index", value: author.i10_index },
                { label: "Works", value: author.works_count },
                { label: "Citations", value: author.cited_by_count },
              ].map((s) => (
                <div key={s.label} className="flex-1 rounded-[8px] bg-surface-subtle px-3 py-2.5 text-center">
                  <p className="font-mono text-[17px] font-semibold text-text-primary">
                    <AnimatedCounter to={s.value} />
                  </p>
                  <p className="mt-0.5 font-body text-[10.5px] uppercase tracking-wide text-text-muted">
                    {s.label}
                  </p>
                </div>
              ))}
            </div>
          </Card>
        </Reveal>
      )}

      <Reveal delay={0.16}>
        <Card>
          <h2 className="flex items-center gap-1.5 font-display text-[15px] font-semibold text-notification">
            <ShieldAlert size={15} />
            Danger zone
          </h2>
          <p className="mt-1.5 font-body text-[13px] text-text-secondary">
            Permanently delete your account and all associated data. This can&apos;t be undone.
          </p>
          {confirmDelete ? (
            <div className="mt-3 flex gap-2">
              <Button variant="primary" error fullWidth={false} onClick={handleDeleteAccount} loading={deleting} className="px-8">
                Confirm delete
              </Button>
              <Button variant="text" fullWidth={false} onClick={() => setConfirmDelete(false)}>
                Cancel
              </Button>
            </div>
          ) : (
            <Button variant="outlined" error onClick={() => setConfirmDelete(true)} className="mt-3">
              Delete account
            </Button>
          )}
        </Card>
      </Reveal>
      </div>
    </div>
    </div>
  );
}
