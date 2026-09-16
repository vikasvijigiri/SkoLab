"use client";

import { useState } from "react";
import { Lightbulb, Plus } from "lucide-react";

export function IdeasTab({ onInsertIdea }: { onInsertIdea: (idea: string) => void }) {
  const [draft, setDraft] = useState("");
  const [ideas, setIdeas] = useState<string[]>([]);
  function addIdea() {
    const idea = draft.trim();
    if (!idea) return;
    setIdeas((current) => [idea, ...current]);
    setDraft("");
  }
  return (
    <section className="flex h-full flex-col gap-3 p-3" aria-label="Ideas board">
      <div className="flex items-start gap-2"><Lightbulb size={15} className="mt-0.5 text-accent-signal" aria-hidden="true" /><div><h2 className="font-body text-[13px] font-semibold text-text-primary">Ideas board</h2><p className="mt-1 font-body text-[11.5px] leading-relaxed text-text-muted">Capture hypotheses before they become prose.</p></div></div>
      <textarea aria-label="New idea" value={draft} onChange={(event) => setDraft(event.target.value)} onKeyDown={(event) => { if ((event.metaKey || event.ctrlKey) && event.key === "Enter") addIdea(); }} placeholder="What could explain this result?" rows={3} className="resize-none rounded-xs border border-border-input bg-surface-input p-2.5 font-body text-[11.5px] text-text-primary outline-none placeholder:text-text-muted focus:ring-1 focus:ring-ring" />
      <button type="button" onClick={addIdea} className="inline-flex items-center justify-center gap-1.5 rounded-xs border border-border px-3 py-1.5 font-mono text-[10px] font-semibold uppercase tracking-[0.06em] text-text-secondary hover:border-primary hover:text-primary"><Plus size={12} /> Capture idea</button>
      <div className="min-h-0 flex-1 space-y-2 overflow-y-auto" aria-live="polite">{ideas.length === 0 ? <p className="pt-6 text-center font-body text-[11.5px] leading-relaxed text-text-muted">No ideas captured yet. Keep this space loose.</p> : ideas.map((idea, index) => <article key={`${idea}-${index}`} className="rounded-xs border border-border bg-surface p-2.5"><p className="font-body text-[11.5px] leading-relaxed text-text-primary">{idea}</p><button type="button" onClick={() => onInsertIdea(`\n\n> Idea: ${idea}\n`)} className="mt-2 font-mono text-[10px] font-semibold text-primary hover:underline">Insert into draft</button></article>)}</div>
    </section>
  );
}
