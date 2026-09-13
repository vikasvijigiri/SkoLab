"use client";

import { useEffect, useMemo, useRef, useState } from "react";
import { FileText, Plus, Eye, EyeOff, Maximize2, Minimize2, PanelRight } from "lucide-react";
import { useFirestoreCollection } from "@/lib/hooks/useFirestoreCollection";
import { MarkdownDoc } from "@/components/workspace/MarkdownDoc";
import { ErrorBanner, friendlyFirestoreError } from "@/components/ui/ErrorBanner";
import { useKeyboardShortcut } from "@/lib/hooks/useKeyboardShortcut";
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
import { WorkspaceResearchActions, JOURNAL_TEMPLATES } from "@/components/workspace/ResearchTools";
import { DocumentDock } from "@/components/workspace/DocumentDock";
import { SlashMenu } from "@/components/workspace/SlashMenu";

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
  onFocusChange,
  onOpenShare,
}: {
  project: CollabProject;
  canEdit: boolean;
  /** Reports which doc the user is looking at, for presence. */
  onActiveDocChange?: (docId: string | null) => void;
  /** Reports focus (distraction-free) mode so the page can hide its rail. */
  onFocusChange?: (focus: boolean) => void;
  /** Opens the project's Share modal — the dock's Share icon needs this,
   *  same as the page toolbar's own Share button (decisions/0019). */
  onOpenShare: () => void;
}) {
  const { user } = useAuth();
  const by = useMemo<Author>(
    () => ({ uid: user?.uid ?? "anon", name: user?.displayName ?? "Researcher" }),
    [user?.uid, user?.displayName],
  );

  const { data: documents, error: subError } = useFirestoreCollection<CollabDocument>(
    (next, onErr) => subscribeDocuments(project.id, next, onErr),
    { deps: [project.id] },
  );

  const [activeId, setActiveId] = useState<string | null>(null);
  const [renamingId, setRenamingId] = useState<string | null>(null);
  const [renameDraft, setRenameDraft] = useState("");
  const [confirmDeleteId, setConfirmDeleteId] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [focus, setFocus] = useState(false);
  const migrated = useRef(false);

  useKeyboardShortcut({ key: ".", meta: true }, () => toggleFocus());

  function toggleFocus() {
    setFocus((f) => {
      onFocusChange?.(!f);
      return !f;
    });
  }

  // Backfill a `main` doc for pre-multi-document projects, once.
  useEffect(() => {
    if (migrated.current || !user) return;
    migrated.current = true;
    ensureMainDocument(project, by).catch(() => {});
  }, [project, by, user]);

  const active = documents.find((d) => d.id === activeId) ?? documents[0] ?? null;

  useEffect(() => {
    onActiveDocChange?.(active?.id ?? null);
  }, [active?.id, onActiveDocChange]);

  async function addDoc() {
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
    <div className="flex h-full flex-col md:flex-row">
      {/* ── File panel (one rung down the surface ladder) ─────────────── */}
      {/* The 204px file rail + the 56px project icon rail = the same 260px
          side system used by Discovery and other research surfaces. */}
      {!focus && (
        <aside className="flex shrink-0 flex-col border-b border-border bg-surface-subtle md:w-[204px] md:border-b-0 md:border-r">
          <div className="flex items-center justify-between px-3 pb-2 pt-3">
            <span className="font-mono text-[10px] font-semibold uppercase tracking-[0.08em] text-text-muted">
              Files
            </span>
            <span className="font-mono text-[10px] text-text-muted">{documents.length}</span>
          </div>

          <div className="flex-1 overflow-y-auto px-1.5">
            {documents.map((d) => (
              <div
                key={d.id}
                className={cn(
                  "group relative flex items-center gap-2 rounded-md px-3 py-2 text-left transition-colors",
                  d.id === active?.id
                    ? "bg-primary/10 text-primary"
                    : "text-text-secondary hover:bg-surface hover:text-text-primary",
                )}
              >
                {d.id === active?.id && (
                  <span className="absolute left-0 top-1.5 bottom-1.5 w-[2px] rounded-r bg-primary" />
                )}
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
                    className="min-w-0 flex-1 rounded border border-primary bg-surface px-1.5 py-0.5 font-body text-body-s text-text-primary outline-none"
                  />
                ) : (
                  <button
                    type="button"
                    onClick={() => setActiveId(d.id)}
                    onDoubleClick={() => canEdit && beginRename(d)}
                    className="flex min-w-0 flex-1 items-center gap-2"
                  >
                    <FileText size={13} className="shrink-0 opacity-70" />
                    <span className="truncate font-body text-body-s">{d.title}</span>
                  </button>
                )}
                {canEdit && documents.length > 1 && renamingId !== d.id && (
                  confirmDeleteId === d.id ? (
                    <span className="flex shrink-0 items-center gap-1">
                      <button
                        type="button"
                        onClick={() => removeDoc(d)}
                        className="rounded px-1 font-body text-[10px] font-semibold text-notification hover:bg-notification/10"
                      >
                        Delete
                      </button>
                      <button
                        type="button"
                        onClick={() => setConfirmDeleteId(null)}
                        className="rounded px-1 font-body text-[10px] text-text-muted hover:text-text-primary"
                      >
                        Keep
                      </button>
                    </span>
                  ) : (
                    <button
                      type="button"
                      onClick={() => setConfirmDeleteId(d.id)}
                      aria-label={`Delete ${d.title}`}
                      className="shrink-0 text-text-muted opacity-0 transition-opacity hover:text-notification group-hover:opacity-100"
                    >
                      <span className="text-[15px] leading-none">×</span>
                    </button>
                  )
                )}
              </div>
            ))}
          </div>

          {canEdit && (
            <div className="p-1.5">
              <button
                type="button"
                onClick={addDoc}
                className="flex w-full items-center justify-center gap-2 rounded-md border border-dashed border-border py-2 font-body text-[11.5px] font-medium text-text-muted transition-colors hover:border-primary/50 hover:text-text-primary"
              >
                <Plus size={13} />
                New document
              </button>
            </div>
          )}
        </aside>
      )}

      {/* ── Editor canvas (the protagonist) ──────────────────────────── */}
      <div className="flex min-w-0 flex-1 flex-col overflow-hidden bg-surface">
        {(error || subError) && (
          <div className="shrink-0 p-3">
            <ErrorBanner message={error ?? subError!} />
          </div>
        )}
        {active ? (
          <DocEditorPane
            key={active.id}
            projectId={project.id}
            projectName={project.name}
            initialLatex={project.recentEquations}
            doc={active}
            documents={documents}
            canEdit={canEdit}
            by={by}
            focus={focus}
            onToggleFocus={toggleFocus}
            onError={setError}
            onOpenShare={onOpenShare}
          />
        ) : (
          <div className="flex h-full items-center justify-center font-body text-body-s text-text-muted">
            No documents yet.
          </div>
        )}
      </div>
    </div>
  );
}

/** One document's editor — keyed by doc id in the parent so switching
 *  documents remounts it and the draft re-initialises from `doc.body`. */
function DocEditorPane({
  projectId,
  projectName,
  initialLatex,
  doc,
  documents,
  canEdit,
  by,
  focus,
  onToggleFocus,
  onError,
  onOpenShare,
}: {
  projectId: string;
  projectName: string;
  initialLatex: string;
  doc: CollabDocument;
  documents: CollabDocument[];
  canEdit: boolean;
  by: Author;
  focus: boolean;
  onToggleFocus: () => void;
  onError: (msg: string) => void;
  onOpenShare: () => void;
}) {
  const [draft, setDraft] = useState(doc.body);
  const [savedAt, setSavedAt] = useState<number>(doc.updatedAt);
  const [saving, setSaving] = useState(false);
  const [justSaved, setJustSaved] = useState(false);
  const [preview, setPreview] = useState(false);
  const [selectedTemplateId, setSelectedTemplateId] = useState<string | null>(null);
  const [templatesOpen, setTemplatesOpen] = useState(false);
  const [slashMenuOpen, setSlashMenuOpen] = useState(false);
  const [slashTriggerPos, setSlashTriggerPos] = useState<number | null>(null);
  const [mobileDockOpen, setMobileDockOpen] = useState(false);
  const textareaRef = useRef<HTMLTextAreaElement>(null);
  const saveTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const selectedTemplate = JOURNAL_TEMPLATES.find((t) => t.id === selectedTemplateId);

  useEffect(
    () => () => {
      if (saveTimer.current) clearTimeout(saveTimer.current);
    },
    [],
  );

  const words = draft.trim() ? draft.trim().split(/\s+/).length : 0;
  const readMin = Math.max(1, Math.round(words / 200));

  function scheduleSave(next: string) {
    setDraft(next);
    if (!canEdit) return;
    if (saveTimer.current) clearTimeout(saveTimer.current);
    setSaving(true);
    saveTimer.current = setTimeout(async () => {
      try {
        await updateDocument(projectId, doc.id, { body: next }, by);
        setSavedAt(Date.now());
        setJustSaved(true);
        setTimeout(() => setJustSaved(false), 1400);
      } catch (err) {
        onError(friendlyFirestoreError(err as { code?: string; message?: string }));
      } finally {
        setSaving(false);
      }
    }, 800);
  }

  async function applyTemplate(body: string) {
    if (!canEdit) return;
    setDraft(body);
    setSaving(true);
    try {
      await updateDocument(projectId, doc.id, { body }, by);
      setSavedAt(Date.now());
      setJustSaved(true);
      setTimeout(() => setJustSaved(false), 1400);
    } catch (err) {
      onError(friendlyFirestoreError(err as { code?: string; message?: string }));
    } finally {
      setSaving(false);
    }
  }

  /** `/` slash-command insert menu (decisions/0019): fires when the last two
   *  characters typed are a newline-or-start followed by "/", same trigger
   *  convention as Notion/Slack-style editors. */
  function handleTextareaChange(e: React.ChangeEvent<HTMLTextAreaElement>) {
    const value = e.target.value;
    const pos = e.target.selectionStart ?? value.length;
    scheduleSave(value);
    const beforeCaret = value.slice(0, pos);
    if (/(^|\n)\/$/.test(beforeCaret)) {
      setSlashTriggerPos(pos);
      setSlashMenuOpen(true);
    } else if (slashMenuOpen) {
      setSlashMenuOpen(false);
      setSlashTriggerPos(null);
    }
  }

  /** Replaces the triggering "/" with `text` and restores the caret after it. */
  function insertAtSlash(text: string) {
    if (slashTriggerPos == null) return;
    const before = draft.slice(0, slashTriggerPos - 1);
    const after = draft.slice(slashTriggerPos);
    const next = `${before}${text}${after}`;
    scheduleSave(next);
    setSlashMenuOpen(false);
    setSlashTriggerPos(null);
    requestAnimationFrame(() => {
      const el = textareaRef.current;
      if (!el) return;
      el.focus();
      const caret = before.length + text.length;
      el.setSelectionRange(caret, caret);
    });
  }

  function openTemplatesFromDock() {
    setSlashMenuOpen(false);
    setTemplatesOpen(true);
  }

  const dock = (
    <DocumentDock
      projectId={projectId}
      documentBody={draft}
      initialLatex={initialLatex}
      template={selectedTemplate}
      onOpenTemplates={openTemplatesFromDock}
      onOpenShare={onOpenShare}
    />
  );

  return (
    <div className="flex h-full min-h-0 flex-col">
      {/* Meta strip */}
      <div className="flex min-h-9 shrink-0 flex-wrap items-center justify-between gap-2 border-b border-border px-4 py-1.5 font-body text-[11.5px] text-text-muted">
        <span className="flex items-center gap-2 tabular-nums">
          <span
            className={cn(
              "inline-block h-1.5 w-1.5 rounded-full transition-colors",
              saving ? "bg-accent-amber" : justSaved ? "bg-accent-emerald" : "bg-border",
            )}
          />
          {saving ? "Saving…" : `Saved ${relTime(savedAt)} · ${doc.updatedByName}`}
          {words > 0 && <span className="text-border">·</span>}
          {words > 0 && <span>{words} words · ~{readMin} min</span>}
        </span>
        <span className="flex items-center gap-1">
          <WorkspaceResearchActions
            projectId={projectId}
            projectName={projectName}
            documentTitle={doc.title}
            documentBody={draft}
            documents={documents}
            canEdit={canEdit}
            onApplyTemplate={applyTemplate}
            selectedTemplateId={selectedTemplateId}
            onSelectTemplate={setSelectedTemplateId}
            templatesOpen={templatesOpen}
            onTemplatesOpenChange={setTemplatesOpen}
          />
          <button
            type="button"
            onClick={() => setPreview((v) => !v)}
            className="flex items-center gap-2 rounded-md px-2 py-1 transition-colors hover:bg-surface-subtle hover:text-text-primary"
          >
            {preview ? <EyeOff size={12} /> : <Eye size={12} />}
            {preview ? "Hide preview" : "Preview"}
          </button>
          <button
            type="button"
            onClick={onToggleFocus}
            title="Focus mode (⌘.)"
            className="flex items-center gap-2 rounded-md px-2 py-1 transition-colors hover:bg-surface-subtle hover:text-text-primary"
          >
            {focus ? <Minimize2 size={12} /> : <Maximize2 size={12} />}
            {focus ? "Exit focus" : "Focus"}
          </button>
          {!focus && (
            <button
              type="button"
              onClick={() => setMobileDockOpen(true)}
              className="flex items-center gap-2 rounded-md px-2 py-1 transition-colors hover:bg-surface-subtle hover:text-text-primary md:hidden"
            >
              <PanelRight size={12} />
              Panel
            </button>
          )}
        </span>
      </div>

      {/* Sheet(s) + dock */}
      <div className={cn("flex min-h-0 flex-1", preview && "lg:divide-x lg:divide-border")}>
        <div className="relative min-h-0 flex-1 overflow-y-auto">
          <textarea
            ref={textareaRef}
            value={draft}
            onChange={handleTextareaChange}
            readOnly={!canEdit}
            spellCheck
            placeholder={
              canEdit
                ? "Start writing. Markdown for structure, $…$ / $$…$$ for math. Type / for equations, figures, citations and section templates…"
                : "You have read-only access to this document."
            }
            className="mx-auto block min-h-full w-full max-w-[68ch] resize-none border-0 bg-transparent px-6 py-8 font-mono text-[15px] leading-[1.75] text-text-primary outline-none placeholder:text-text-muted/60 read-only:opacity-80 md:px-10"
          />
          {slashMenuOpen && canEdit && (
            <SlashMenu
              onInsertText={insertAtSlash}
              onOpenTemplates={openTemplatesFromDock}
              onClose={() => {
                setSlashMenuOpen(false);
                setSlashTriggerPos(null);
              }}
            />
          )}
        </div>
        {preview && (
          <div className="hidden min-h-0 flex-1 overflow-y-auto lg:block">
            <div className="mx-auto max-w-[68ch] px-6 py-8 md:px-10">
              <MarkdownDoc source={draft} />
            </div>
          </div>
        )}
        {/* Desktop: the dock sits inline, beside the editor. Collapsible to a
            40px rail (see DocumentDock) rather than disappearing outright. */}
        {!focus && <div className="hidden md:flex">{dock}</div>}
      </div>

      {/* Mobile: the dock has no room inline, so it's a bottom-sheet trigger
          (the "Panel" button above) instead — a real layout-state change,
          same treatment as the desktop collapse/expand. */}
      {!focus && mobileDockOpen && (
        <div className="fixed inset-0 z-40 md:hidden">
          <div
            className="absolute inset-0 bg-[rgba(15,12,8,0.4)]"
            onClick={() => setMobileDockOpen(false)}
            aria-hidden="true"
          />
          <div className="absolute inset-x-0 bottom-0 flex max-h-[70vh] flex-col overflow-hidden rounded-t-2xl bg-surface-subtle shadow-elevated">
            <div className="flex shrink-0 justify-center pb-1 pt-2">
              <span className="h-1 w-9 rounded-full bg-border" />
            </div>
            <DocumentDock
              projectId={projectId}
              documentBody={draft}
              initialLatex={initialLatex}
              template={selectedTemplate}
              onOpenTemplates={openTemplatesFromDock}
              onOpenShare={onOpenShare}
              hideCollapse
              onRequestClose={() => setMobileDockOpen(false)}
              className="min-h-0 flex-1 border-l-0"
            />
          </div>
        </div>
      )}
    </div>
  );
}
