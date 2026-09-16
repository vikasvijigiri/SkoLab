"use client";

import { useEffect, useState } from "react";
import { Check, MessageSquarePlus } from "lucide-react";
import { useAuth } from "@/lib/hooks/AuthProvider";
import { addComment, resolveComment, subscribeComments } from "@/lib/firebase/workspace";
import type { CollabComment } from "@/lib/types";
import { ErrorBanner, friendlyFirestoreError } from "@/components/ui/ErrorBanner";
import { Button } from "@/components/ui/Button";
import { cn } from "@/lib/utils";

export function CommentsTab({ projectId, docId }: { projectId: string; docId: string }) {
  const { user } = useAuth();
  const [comments, setComments] = useState<CollabComment[]>([]);
  const [body, setBody] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [sending, setSending] = useState(false);

  useEffect(() => subscribeComments(projectId, docId, setComments, (err) => setError(friendlyFirestoreError(err))), [projectId, docId]);

  async function submit() {
    if (!user || !body.trim()) return;
    setSending(true);
    try {
      await addComment(projectId, { docId, authorUid: user.uid, authorName: user.displayName ?? "Researcher", body: body.trim(), line: null });
      setBody("");
      setError(null);
    } catch (err) { setError(friendlyFirestoreError(err as { code?: string; message?: string })); }
    finally { setSending(false); }
  }

  return <div className="space-y-4 p-3">
    {error && <ErrorBanner message={error} />}
    <div><p className="font-mono text-[10px] uppercase tracking-[0.16em] text-text-muted">Review thread</p><p className="mt-1 font-body text-[12px] leading-5 text-text-secondary">Leave focused feedback without changing the manuscript.</p></div>
    <div className="space-y-2">
      {comments.length === 0 && <p className="rounded-md border border-dashed border-border px-3 py-4 text-center font-body text-[12px] text-text-muted">No comments on this document.</p>}
      {comments.map((comment) => <article key={comment.id} className={cn("rounded-md border border-border bg-surface p-3", comment.resolved && "opacity-60")}>
        <div className="flex items-center justify-between gap-2"><span className="font-body text-[12px] font-semibold text-text-primary">{comment.authorName}</span>{comment.resolved ? <span className="font-mono text-[10px] uppercase text-accent-live">Resolved</span> : user && <button type="button" onClick={() => void resolveComment(projectId, comment.id, user.uid, true)} className="inline-flex items-center gap-1 font-mono text-[10px] uppercase tracking-wide text-text-muted hover:text-text-primary" aria-label={`Resolve comment by ${comment.authorName}`}><Check size={12} /> Resolve</button>}</div>
        <p className="mt-2 whitespace-pre-wrap font-body text-[12px] leading-5 text-text-secondary">{comment.body}</p>
      </article>)}
    </div>
    <div className="rounded-md border border-border bg-surface p-2"><textarea value={body} onChange={(event) => setBody(event.target.value)} placeholder="Comment on this draft…" rows={3} className="w-full resize-none bg-transparent px-1 py-1 font-body text-[12px] text-text-primary outline-none placeholder:text-text-muted" /><div className="flex justify-end"><Button type="button" onClick={() => void submit()} loading={sending} fullWidth={false} className="h-8 gap-1.5 px-3 text-[12px]"><MessageSquarePlus size={13} /> Add comment</Button></div></div>
  </div>;
}
