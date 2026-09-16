"use client";

import { useEffect, useState } from "react";
import { History, FileText } from "lucide-react";
import { subscribeHistory } from "@/lib/firebase/workspace";
import type { CollabHistoryEntry } from "@/lib/types";
import { ErrorBanner, friendlyFirestoreError } from "@/components/ui/ErrorBanner";

function savedLabel(ts: number) {
  return new Intl.DateTimeFormat(undefined, { dateStyle: "medium", timeStyle: "short" }).format(new Date(ts));
}

export function HistoryTab({ projectId, docId }: { projectId: string; docId: string }) {
  const [entries, setEntries] = useState<CollabHistoryEntry[]>([]);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => subscribeHistory(projectId, docId, (next) => { setEntries(next); setError(null); }, (err) => setError(friendlyFirestoreError(err))), [projectId, docId]);

  return <div className="space-y-4 p-3">
    {error && <ErrorBanner message={error} />}
    <div><p className="font-mono text-[10px] uppercase tracking-[0.16em] text-text-muted">Revision history</p><p className="mt-1 font-body text-[12px] leading-5 text-text-secondary">Durable save points for this manuscript.</p></div>
    {entries.length === 0 ? <div className="rounded-md border border-dashed border-border px-3 py-6 text-center"><History size={18} className="mx-auto text-text-muted" /><p className="mt-2 font-body text-[12px] text-text-muted">Your first snapshot appears after editing.</p></div> : <div className="space-y-2">
      {entries.map((entry) => <article key={entry.id} className="rounded-md border border-border bg-surface p-3">
        <div className="flex items-start gap-2"><FileText size={14} className="mt-0.5 shrink-0 text-primary" /><div className="min-w-0"><p className="font-body text-[12px] font-semibold text-text-primary">{entry.title || "Manuscript snapshot"}</p><p className="mt-1 font-mono text-[10px] text-text-muted">{savedLabel(entry.savedAt)} · {entry.savedByName}</p></div></div>
        <p className="mt-2 line-clamp-2 font-body text-[11px] leading-4 text-text-secondary">{entry.body || "Empty draft"}</p>
      </article>)}
    </div>}
  </div>;
}
