"use client";

import { useEffect, useMemo, useRef, useState } from "react";
import { FileText, Plus, Trash2, Eye, EyeOff } from "lucide-react";
import { useFirestoreCollection } from "@/lib/hooks/useFirestoreCollection";
import { MarkdownText } from "@/components/ui/MathText";
import { ErrorBanner, friendlyFirestoreError } from "@/components/ui/ErrorBanner";
import {
  subscribeDocuments,
  createDocument,
  updateDocument,
  deleteDocument,
  ensureMainDocument,
} from "@/lib/firebase/workspace";
import { useAuth } from "@/lib/hooks/AuthProvider";
import { cn } from "@/lib/utils";
import type { CollabProject, CollabDocument } from "@/lib/types";

function relTime(ts: number) {
  const s = Math.round((Date.now() - ts) / 1000);
  if (s < 45) return "just now";
  if (s < 3600) return `${Math.round(s / 60)}m ago`;
  if (s < 86400) return `${Math.round(s / 3600)}h ago`;
  return `${Math.round(s / 86400)}d ago`;
}

type Author = { uid: string; name: string };

export function DocumentsTab({
  project,
  canEdit,
  onActiveDocChange,
}: {
  project: CollabProject;
  canEdit: boolean;
  /** Reports which doc the user is looking at, for presence. */
  onActiveDocChange?: (docId: string | null) => void;
}) {
  const { user } = useAuth();
  const by = useMemo<Author>(
    () => ({ uid: user?.uid ?? "anon", name: user?.displayName ?? "Researcher" }),
    [user?.uid, user?.displayName]
  );

  const { data: documents, error: subError } = useFirestoreCollection<CollabDocument>(
    (next, onErr) => subscribeDocuments(project.id, next, onErr),
    { deps: [project.id] }
  );

  const [activeId, setActiveId] = useState<string | null>(null);
  const [renamingId, setRenamingId] = useState<string | null>(null);
  const [renameDraft, setRenameDraft] = useState("");
  const [confirmDeleteId, setConfirmDeleteId] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const migrated = useRef(false);

  // Backfill a `main` doc for pre-multi-document projects, once.
  useEffect(() => {
    if (migrated.current || !user) return;
    migrated.current = true;
    ensureMainDocument(project, by).catch(() => {});
  }, [project, by, user]);

  // `active` is derived — the user's explicit pick when it still exists,
  // otherwise the first document. No state to sync, so no effect.
  const active = documents.find((d) => d.id === activeId) ?? documents[0] ?? null;

  useEffect(() => {
    onActiveDocChange?.(active?.id ?? null);
  }, [active?.id, onActiveDocChange]);

  async function addDoc() {
    // Auto-named; the user renames inline afterwards — no prompt.
    const title = `Section ${documents.length + 1}`;
    const id = await createDocument(project.id, title, documents.length, by).catch((err) => {
      setError(friendlyFirestoreError(err as { code?: string; message?: string }));
      return null;
    });
    if (id) {
      setActiveId(id);
      setRenamingId(id);
      setRenameDraft(title);
    }
  }

  function beginRename(d: CollabDocument) {
    setRenamingId(d.id);
    setRenameDraft(d.title);
  }

  async function commitRename(d: CollabDocument) {
    const title = renameDraft.trim();
    setRenamingId(null);
    if (!title || title === d.title) return;
    await updateDocument(project.id, d.id, { title }, by).catch(() => {});
  }

  async function removeDoc(d: CollabDocument) {
    setConfirmDeleteId(null);
    if (documents.length <= 1) return;
    await deleteDocument(project.id, d.id).catch(() => {});
    if (activeId === d.id) setActiveId(documents.find((x) => x.id !== d.id)?.id ?? null);
  }

  return (
    <div className="flex min-h-[26rem] flex-col gap-3 md:flex-row">
      {/* File list */}
      <aside className="flex shrink-0 flex-col gap-1 md:w-52">
        <div className="flex items-center justify-between px-1 pb-1">
          <span className="font-mono text-[10.5px] font-semibold uppercase tracking-wide text-text-muted">
            Documents
          </span>
          {canEdit && (
            <button
              type="button"
              onClick={addDoc}
              aria-label="New document"
              className="flex h-6 w-6 items-center justify-center rounded text-text-muted transition-colors hover:bg-surface-subtle hover:text-text-primary"
            >
              <Plus size={14} />
            </button>
          )}
        </div>
        {documents.map((d) => (
          <div
            key={d.id}
            className={cn(
              "group flex items-center gap-2 rounded-md px-2.5 py-2 text-left transition-colors",
              d.id === active?.id ? "bg-primary/10 text-primary" : "text-text-secondary hover:bg-surface-subtle"
            )}
          >
            {renamingId === d.id ? (
              <input
                ref={(el) => el?.focus()}
                value={renameDraft}
                onChange={(e) => setRenameDraft(e.target.value)}
                onBlur={() => commitRename(d)}
                onKeyDown={(e) => {
                  if (e.key === "Enter") commitRename(d);
                  if (e.key === "Escape") setRenamingId(null);
                }}
                aria-label={`Rename ${d.title}`}
                className="min-w-0 flex-1 rounded border border-primary bg-surface-input px-1.5 py-0.5 font-body text-[12.5px] text-text-primary outline-none"
              />
            ) : (
              <button
                type="button"
                onClick={() => setActiveId(d.id)}
                onDoubleClick={() => canEdit && beginRename(d)}
                className="flex min-w-0 flex-1 items-center gap-2"
              >
                <FileText size={13} className="shrink-0" />
                <span className="truncate font-body text-[12.5px]">{d.title}</span>
              </button>
            )}
            {canEdit && documents.length > 1 && renamingId !== d.id && (
              confirmDeleteId === d.id ? (
                <div className="flex shrink-0 items-center gap-1">
                  <button
                    type="button"
                    onClick={() => removeDoc(d)}
                    className="rounded px-1 font-body text-[10.5px] font-semibold text-notification hover:bg-notification/10"
                  >
                    Delete
                  </button>
                  <button
                    type="button"
                    onClick={() => setConfirmDeleteId(null)}
                    className="rounded px-1 font-body text-[10.5px] text-text-muted hover:text-text-primary"
                  >
                    Keep
                  </button>
                </div>
              ) : (
                <button
                  type="button"
                  onClick={() => setConfirmDeleteId(d.id)}
                  aria-label={`Delete ${d.title}`}
                  className="shrink-0 opacity-0 transition-opacity hover:text-notification group-hover:opacity-100"
                >
                  <Trash2 size={12} />
                </button>
              )
            )}
          </div>
        ))}
      </aside>

      {/* Editor + preview */}
      <div className="flex min-w-0 flex-1 flex-col gap-2">
        {(error || subError) && <ErrorBanner message={error ?? subError!} />}
        {active ? (
          <DocEditorPane
            key={active.id}
            projectId={project.id}
            doc={active}
            canEdit={canEdit}
            by={by}
            onError={setError}
          />
        ) : (
          <div className="flex min-h-[24rem] items-center justify-center rounded-md border border-border bg-surface font-body text-[13px] text-text-muted">
            No documents yet.
          </div>
        )}
      </div>
    </div>
  );
}

/** One document's editor. Keyed by doc id in the parent, so switching
 *  documents remounts it and the draft re-initialises from `doc.body`
 *  without an effect fighting the user's typing. */
function DocEditorPane({
  projectId,
  doc,
  canEdit,
  by,
  onError,
}: {
  projectId: string;
  doc: CollabDocument;
  canEdit: boolean;
  by: Author;
  onError: (msg: string) => void;
}) {
  const [draft, setDraft] = useState(doc.body);
  const [savedAt, setSavedAt] = useState<number>(doc.updatedAt);
  const [saving, setSaving] = useState(false);
  const [preview, setPreview] = useState(false);
  const saveTimer = useRef<ReturnType<typeof setTimeout> | null>(null);

  useEffect(
    () => () => {
      if (saveTimer.current) clearTimeout(saveTimer.current);
    },
    []
  );

  function scheduleSave(next: string) {
    setDraft(next);
    if (!canEdit) return;
    if (saveTimer.current) clearTimeout(saveTimer.current);
    setSaving(true);
    saveTimer.current = setTimeout(async () => {
      try {
        await updateDocument(projectId, doc.id, { body: next }, by);
        setSavedAt(Date.now());
      } catch (err) {
        onError(friendlyFirestoreError(err as { code?: string; message?: string }));
      } finally {
        setSaving(false);
      }
    }, 800);
  }

  return (
    <>
      <div className="flex items-center justify-between">
        <span className="font-body text-[12px] text-text-muted">
          {saving ? "Saving…" : `Saved ${relTime(savedAt)} · ${doc.updatedByName}`}
        </span>
        <button
          type="button"
          onClick={() => setPreview((v) => !v)}
          className="flex items-center gap-1.5 rounded-md border border-border px-2.5 py-1 font-body text-[11.5px] text-text-secondary transition-colors hover:border-primary/40 hover:text-text-primary"
        >
          {preview ? <EyeOff size={12} /> : <Eye size={12} />}
          {preview ? "Hide preview" : "Preview"}
        </button>
      </div>

      <div className={cn("grid gap-3", preview && "lg:grid-cols-2")}>
        <textarea
          value={draft}
          onChange={(e) => scheduleSave(e.target.value)}
          readOnly={!canEdit}
          spellCheck
          placeholder={
            canEdit
              ? "Write in Markdown. Inline math with $…$, display math with $$…$$."
              : "You have read-only access to this document."
          }
          className="min-h-[24rem] w-full resize-y rounded-md border border-border bg-surface-input p-3.5 font-mono text-[13px] leading-relaxed text-text-primary outline-none focus:border-primary read-only:opacity-80"
        />
        {preview && (
          <div className="min-h-[24rem] overflow-y-auto rounded-md border border-border bg-surface p-4 font-body text-[13.5px] leading-relaxed text-text-primary">
            {draft.trim() ? (
              draft.split(/\n{2,}/).map((para, i) => (
                <p key={i} className={i > 0 ? "mt-3" : undefined}>
                  <MarkdownText text={para} />
                </p>
              ))
            ) : (
              <span className="text-text-muted">Nothing to preview yet.</span>
            )}
          </div>
        )}
      </div>
    </>
  );
}
