"use client";

import { useMemo, useState } from "react";
import { useRouter } from "next/navigation";
import { motion } from "framer-motion";
import { useQuery } from "@tanstack/react-query";
import { Pencil, TrendingUp, ShieldAlert, Check } from "lucide-react";
import { Card } from "@/components/ui/Card";
import { Badge, Chip } from "@/components/ui/Badge";
import { Button } from "@/components/ui/Button";
import { AnimatedCounter } from "@/components/ui/AnimatedCounter";
import { ErrorBanner, friendlyFirestoreError } from "@/components/ui/ErrorBanner";
import { Reveal } from "@/components/ui/Reveal";
import { useAuth } from "@/lib/hooks/AuthProvider";
import { useMyProfile } from "@/lib/hooks/useMyProfile";
import { updateResearcherProfile, deleteResearcherProfile } from "@/lib/firebase/auth";
import { syncUserProfile, deleteUserAccount } from "@/lib/api/endpoints";
import {
  openAlexFieldsQuery,
  openAlexSubfieldsQuery,
  openAlexAuthorMatchQuery,
} from "@/lib/api/queries";
import type { OpenAlexAuthorHit, OpenAlexTaxon } from "@/lib/types";

const STATUS_OPTIONS = [
  "PhD Student",
  "Postdoc",
  "Research Scientist",
  "Assistant Professor",
  "Professor",
  "Lecturer",
  "Industry Researcher",
  "Independent Researcher",
];

export default function ProfilePage() {
  const router = useRouter();
  const { user, getIdToken, signOut } = useAuth();
  const { firestoreProfile, author, loading, error: profileError, refetch } = useMyProfile();

  const [editing, setEditing] = useState(false);
  const [field, setField] = useState<OpenAlexTaxon | null>(null);
  const [subfield, setSubfield] = useState<OpenAlexTaxon | null>(null);
  const [academicStatus, setAcademicStatus] = useState("");
  const [about, setAbout] = useState("");
  const [me, setMe] = useState<OpenAlexAuthorHit | null>(null);
  const [showLink, setShowLink] = useState(false);
  const [saving, setSaving] = useState(false);
  const [saveError, setSaveError] = useState<string | null>(null);
  const [deleting, setDeleting] = useState(false);
  const [confirmDelete, setConfirmDelete] = useState(false);

  const displayName = firestoreProfile?.name || user?.displayName || "Researcher";

  const fieldsQ = useQuery({ ...openAlexFieldsQuery(), enabled: editing });
  const subfieldsQ = useQuery(openAlexSubfieldsQuery(field?.id));
  const matchQ = useQuery({
    ...openAlexAuthorMatchQuery(displayName),
    enabled: editing && showLink,
  });

  // The focus string we'll persist: a fresh taxonomy pick, else the current one.
  const nextFocus = useMemo(() => {
    const parts = [subfield?.display_name || field?.display_name].filter(Boolean);
    return parts.length ? parts.join(" · ") : (firestoreProfile?.researchFocus ?? "");
  }, [field, subfield, firestoreProfile?.researchFocus]);

  function startEditing() {
    setField(null);
    setSubfield(null);
    setMe(null);
    setShowLink(false);
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
      await updateResearcherProfile(user.uid, {
        name: displayName,
        authorName: me?.display_name || firestoreProfile?.authorName || displayName,
        researchFocus: nextFocus,
        academicStatus,
        about,
        // Only overwrite the linked OpenAlex id when the user re-picked one.
        ...(me ? { openAlexId: me.id } : {}),
      });
      const idToken = await getIdToken();
      if (idToken) {
        await syncUserProfile(idToken, user.uid, displayName, nextFocus).catch((err) => {
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
        <div className="h-64 animate-pulse rounded-md bg-surface-subtle" />
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
              className="flex h-16 w-16 shrink-0 items-center justify-center rounded-full font-display text-display-m font-bold text-white shadow-card"
              style={{ background: "var(--primary)" }}
            >
              {(firestoreProfile?.name || user?.displayName || "?").slice(0, 1).toUpperCase()}
            </div>
            <div className="min-w-0 flex-1">
              <h1 className="truncate font-display text-h2 font-bold text-text-primary">
                {firestoreProfile?.name || user?.displayName || "Researcher"}
              </h1>
              <p className="truncate font-body text-body-s text-text-secondary">{user?.email}</p>
              <Badge accentColor="var(--accent-teal)" className="mt-1.5 w-fit">
                {firestoreProfile?.academicStatus ?? "Researcher"}
              </Badge>
            </div>
          </div>
          {!editing && (
            <Button variant="outlined" fullWidth={false} onClick={startEditing} className="mt-4 gap-2 lg:w-full">
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
            <h2 className="font-display text-h3 font-semibold text-text-primary">Edit profile</h2>
            {saveError && (
              <div className="mt-3">
                <ErrorBanner message={saveError} />
              </div>
            )}
            <div className="mt-3 flex flex-col gap-4">
              {/* Name comes from your sign-in provider — shown, not edited. */}
              <div>
                <span className="mb-1 block font-body text-body-s font-medium text-text-secondary">Name</span>
                <p className="font-body text-body text-text-primary">{displayName}</p>
              </div>

              {/* Linked OpenAlex profile — re-link by picking, never typing. */}
              <div>
                <span className="mb-2 block font-body text-body-s font-medium text-text-secondary">
                  Linked OpenAlex profile
                </span>
                {!showLink ? (
                  <div className="flex items-center gap-2">
                    <p className="font-body text-body-s text-text-secondary">
                      {me?.display_name ||
                        (firestoreProfile?.openAlexId
                          ? firestoreProfile.authorName || firestoreProfile.openAlexId
                          : "Not linked")}
                    </p>
                    <button
                      type="button"
                      onClick={() => setShowLink(true)}
                      className="cursor-pointer font-body text-[12px] font-medium text-primary hover:underline"
                    >
                      {firestoreProfile?.openAlexId || me ? "Change" : "Link"}
                    </button>
                  </div>
                ) : matchQ.isPending ? (
                  <div className="flex flex-col gap-2">
                    {[0, 1, 2].map((i) => (
                      <div key={i} className="h-14 animate-pulse rounded-md bg-surface-subtle" />
                    ))}
                  </div>
                ) : (
                  <div className="flex flex-col gap-2">
                    {(matchQ.data ?? []).slice(0, 5).map((a) => (
                      <button
                        key={a.id}
                        type="button"
                        onClick={() => {
                          setMe(a);
                          setShowLink(false);
                        }}
                        className={`flex items-start justify-between gap-3 rounded-md border p-2.5 text-left transition-colors ${
                          me?.id === a.id
                            ? "border-primary bg-primary/10"
                            : "border-border-input bg-surface-input hover:border-primary/40"
                        }`}
                      >
                        <div className="min-w-0">
                          <p className="truncate font-body text-body-s font-medium text-text-primary">
                            {a.display_name}
                          </p>
                          <p className="truncate font-mono text-[10.5px] uppercase tracking-wide text-text-muted">
                            {a.institution || "Independent"} · {a.works_count} works · h {a.h_index}
                          </p>
                        </div>
                        {me?.id === a.id && <Check size={15} className="mt-1 shrink-0 text-primary" />}
                      </button>
                    ))}
                    {(matchQ.data ?? []).length === 0 && (
                      <p className="font-body text-[12px] text-text-muted">
                        No matching OpenAlex profile found for &ldquo;{displayName}&rdquo;.
                      </p>
                    )}
                    <button
                      type="button"
                      onClick={() => setShowLink(false)}
                      className="cursor-pointer self-start font-body text-[12px] text-text-muted hover:text-text-secondary hover:underline"
                    >
                      Cancel
                    </button>
                  </div>
                )}
              </div>

              {/* Research focus — pick a field, optionally a sub-field. */}
              <div>
                <span className="mb-2 block font-body text-body-s font-medium text-text-secondary">
                  Research focus
                </span>
                <p className="mb-2 font-body text-body-s text-text-muted">
                  Current: {firestoreProfile?.researchFocus || "not set"}
                  {nextFocus !== (firestoreProfile?.researchFocus ?? "") && (
                    <span className="text-primary"> → {nextFocus}</span>
                  )}
                </p>
                {fieldsQ.isPending ? (
                  <div className="flex flex-wrap gap-2">
                    {[0, 1, 2, 3, 4].map((i) => (
                      <div key={i} className="h-7 w-24 animate-pulse rounded-full bg-surface-subtle" />
                    ))}
                  </div>
                ) : (
                  <div className="flex flex-wrap gap-2">
                    {(fieldsQ.data ?? []).map((t) => (
                      <Chip
                        key={t.id}
                        selected={field?.id === t.id}
                        onClick={() => {
                          setField(field?.id === t.id ? null : t);
                          setSubfield(null);
                        }}
                      >
                        {t.display_name}
                      </Chip>
                    ))}
                  </div>
                )}
                {field && (
                  <div className="mt-2 flex flex-wrap gap-2">
                    {(subfieldsQ.data ?? []).map((t) => (
                      <Chip
                        key={t.id}
                        selected={subfield?.id === t.id}
                        onClick={() => setSubfield(subfield?.id === t.id ? null : t)}
                      >
                        {t.display_name}
                      </Chip>
                    ))}
                  </div>
                )}
              </div>

              <div>
                <span className="mb-2 block font-body text-body-s font-medium text-text-secondary">
                  Academic status
                </span>
                <div className="flex flex-wrap gap-2">
                  {STATUS_OPTIONS.map((opt) => (
                    <button
                      key={opt}
                      type="button"
                      onClick={() => setAcademicStatus(opt)}
                      className={`cursor-pointer rounded-full border px-3 py-1 font-body text-[11.5px] transition-colors ${
                        academicStatus === opt
                          ? "border-primary bg-primary/10 text-primary"
                          : "border-border text-text-muted hover:border-primary/40 hover:text-text-primary"
                      }`}
                    >
                      {opt}
                    </button>
                  ))}
                </div>
              </div>

              <div>
                <label
                  htmlFor="profile-about"
                  className="mb-2 block font-body text-body-s font-medium text-text-secondary"
                >
                  About <span className="font-normal text-text-muted">(free text — the one place you write)</span>
                </label>
                <textarea
                  id="profile-about"
                  value={about}
                  onChange={(e) => setAbout(e.target.value)}
                  rows={4}
                  className="w-full rounded-sm border border-transparent bg-surface-subtle px-4 py-3 font-body text-body text-text-primary outline-none focus:border-primary"
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
            <h2 className="font-display text-h3 font-semibold text-text-primary">Research focus</h2>
            <p className="mt-1.5 font-body text-body-s text-text-secondary">
              {firestoreProfile?.researchFocus || "Not set yet."}
            </p>
            {firestoreProfile?.about && (
              <>
                <h2 className="mt-4 font-display text-h3 font-semibold text-text-primary">About</h2>
                <p className="mt-1.5 font-body text-body-s leading-relaxed text-text-secondary">
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
            <h2 className="font-display text-h3 font-semibold text-text-primary">Research Areas & Skills</h2>
            {author.expertise.length > 0 && (
              <div className="mt-3">
                <h3 className="font-body text-[11px] font-medium uppercase tracking-wide text-text-muted">
                  Research Areas
                </h3>
                <div className="mt-1.5 flex flex-wrap gap-2">
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
                <div className="mt-1.5 flex flex-wrap gap-2">
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
            <h2 className="flex items-center gap-2 font-display text-h3 font-semibold text-text-primary">
              <TrendingUp size={15} className="text-primary" />
              Metrics snapshot
            </h2>
            <div className="mt-3 flex gap-3">
              {[
                { label: "H-Index", value: author.h_index },
                { label: "i10-Index", value: author.i10_index },
                { label: "Works", value: author.works_count },
                { label: "Citations", value: author.cited_by_count },
              ].map((s) => (
                <div key={s.label} className="flex-1 rounded-md bg-surface-subtle px-3 py-3 text-center">
                  <p className="font-mono text-[17px] font-semibold text-text-primary">
                    <AnimatedCounter to={s.value} />
                  </p>
                  <p className="mt-1 font-body text-[10.5px] uppercase tracking-wide text-text-muted">
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
          <h2
            id="danger"
            className="flex scroll-mt-6 items-center gap-2 font-display text-h3 font-semibold text-notification"
          >
            <ShieldAlert size={15} />
            Danger zone
          </h2>
          <p className="mt-1.5 font-body text-body-s text-text-secondary">
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
