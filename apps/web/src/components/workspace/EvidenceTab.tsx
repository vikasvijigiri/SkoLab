"use client";

import { FormEvent, useState } from "react";
import { BookOpen, ExternalLink, Loader2, Search } from "lucide-react";

type Paper = { id?: string; title?: string; publication_year?: number; cited_by_count?: number; doi?: string | null; authorships?: Array<{ author?: { display_name?: string } }> };
function paperId(id: string) { return id.replace(/^https?:\/\/openalex\.org\//i, "").split("/").pop() ?? id; }
function isPaper(value: unknown): value is Paper {
  if (!value || typeof value !== "object") return false;
  const paper = value as Paper;
  return (paper.id === undefined || typeof paper.id === "string") &&
    (paper.title === undefined || typeof paper.title === "string");
}

export function EvidenceTab({ onInsertCitation }: { onInsertCitation: (citation: string) => void }) {
  const [query, setQuery] = useState("");
  const [papers, setPapers] = useState<Paper[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function search(event: FormEvent) {
    event.preventDefault();
    const value = query.trim();
    if (!value) return;
    setLoading(true); setError(null);
    try {
      const response = await fetch(`/api/openalex/works?q=${encodeURIComponent(value)}`);
      if (!response.ok) throw new Error("Evidence search is unavailable right now.");
      const data: unknown = await response.json();
      if (!Array.isArray(data)) throw new Error("The evidence response was invalid.");
      setPapers(data.filter(isPaper).slice(0, 8));
    } catch (err) {
      setError(err instanceof Error ? err.message : "Evidence search failed."); setPapers([]);
    } finally { setLoading(false); }
  }

  return (
    <section className="flex h-full flex-col gap-3 p-3" aria-label="Evidence search">
      <div className="flex items-start gap-2"><BookOpen size={15} className="mt-0.5 shrink-0 text-primary" aria-hidden="true" /><div><h2 className="font-body text-[13px] font-semibold text-text-primary">Evidence desk</h2><p className="mt-1 font-body text-[11.5px] leading-relaxed text-text-muted">Find papers without leaving the manuscript.</p></div></div>
      <form onSubmit={search} className="flex gap-2"><label htmlFor="evidence-search" className="sr-only">Search papers</label><input id="evidence-search" value={query} onChange={(event) => setQuery(event.target.value)} placeholder="Search title or topic…" className="min-w-0 flex-1 rounded-xs border border-border-input bg-surface-input px-2.5 py-2 font-body text-[11.5px] text-text-primary outline-none placeholder:text-text-muted focus:ring-1 focus:ring-ring" /><button type="submit" aria-label="Search evidence" disabled={loading} className="flex h-8 w-8 shrink-0 items-center justify-center rounded-xs bg-primary text-text-on-primary hover:bg-primary-dark disabled:opacity-60">{loading ? <Loader2 size={13} className="animate-spin" /> : <Search size={13} />}</button></form>
      {error && <p role="alert" className="rounded-xs border border-danger/40 bg-danger/10 p-2 font-body text-[11px] text-danger">{error}</p>}
      {!loading && !error && papers.length === 0 && <div className="flex flex-1 items-center justify-center text-center"><p className="max-w-[22ch] font-body text-[11.5px] leading-relaxed text-text-muted">Search a claim, method, or topic to find supporting literature.</p></div>}
      <div className="min-h-0 flex-1 space-y-2 overflow-y-auto" aria-live="polite">{papers.map((paper, index) => { const id = paper.id ? paperId(paper.id) : `paper-${index}`; const authors = (paper.authorships ?? []).slice(0, 2).map((a) => a.author?.display_name).filter(Boolean).join(", "); return <article key={id} className="rounded-xs border border-border bg-surface p-2.5"><h3 className="font-body text-[11.5px] font-semibold leading-snug text-text-primary">{paper.title ?? "Untitled work"}</h3><p className="mt-1 font-body text-[10.5px] text-text-muted">{authors || "Author metadata unavailable"} · {paper.publication_year ?? "Year unknown"}</p><div className="mt-2 flex items-center justify-between gap-2"><span className="font-mono text-[10px] text-text-muted">{paper.cited_by_count ?? 0} citations</span><div className="flex items-center gap-1">{paper.doi && <a href={paper.doi} target="_blank" rel="noreferrer" aria-label="Open DOI" className="flex h-6 w-6 items-center justify-center rounded-xs text-text-muted hover:bg-surface-subtle hover:text-text-primary"><ExternalLink size={12} /></a>}<button type="button" onClick={() => onInsertCitation(`\\cite{openalex:${id}}`)} className="rounded-xs border border-primary/50 px-2 py-1 font-mono text-[10px] font-semibold text-primary hover:bg-primary/10">Cite</button></div></div></article>; })}</div>
    </section>
  );
}
