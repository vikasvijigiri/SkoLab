"use client";

import { Check, CircleAlert, FileCheck2 } from "lucide-react";

function checks(source: string) {
  const words = source.trim() ? source.trim().split(/\s+/).length : 0;
  const hasAbstract = /^#{1,3}\s+abstract\b/im.test(source);
  const hasReferences = /^#{1,3}\s+references?\b/im.test(source);
  const citations = (source.match(/\\cite\{[^}]+\}|\[[^\]]+\]/g) ?? []).length;
  return [
    { label: `${words.toLocaleString()} words in draft`, ok: words > 0 },
    { label: "Abstract section present", ok: hasAbstract },
    { label: "References section present", ok: hasReferences },
    { label: `${citations} citation marker${citations === 1 ? "" : "s"} found`, ok: citations > 0 },
  ];
}

export function ReviewTab({ documentBody }: { documentBody: string }) {
  const items = checks(documentBody);
  const passed = items.filter((item) => item.ok).length;
  return (
    <section className="flex h-full flex-col gap-4 p-3" aria-label="Manuscript review">
      <div className="flex items-start gap-2"><FileCheck2 size={15} className="mt-0.5 text-primary" aria-hidden="true" /><div><h2 className="font-body text-[13px] font-semibold text-text-primary">Manuscript review</h2><p className="mt-1 font-body text-[11.5px] leading-relaxed text-text-muted">Fast structural checks before you ask for feedback.</p></div></div>
      <div className="rounded-xs border border-border bg-surface p-3"><div className="flex items-end justify-between"><span className="font-mono text-[10px] uppercase tracking-[0.08em] text-text-muted">Readiness</span><span className="font-mono text-[18px] font-semibold text-text-primary">{passed}/{items.length}</span></div><div className="mt-2 h-1 overflow-hidden rounded-full bg-surface-subtle"><div className="h-full bg-accent-live transition-all" style={{ width: `${(passed / items.length) * 100}%` }} /></div></div>
      <ul className="space-y-2" aria-label="Review checks">{items.map((item) => <li key={item.label} className="flex items-start gap-2 rounded-xs border border-border px-2.5 py-2"><span className={item.ok ? "text-accent-live" : "text-warning"}>{item.ok ? <Check size={13} aria-hidden="true" /> : <CircleAlert size={13} aria-hidden="true" />}</span><span className="font-body text-[11.5px] text-text-secondary">{item.label}</span></li>)}</ul>
      <p className="mt-auto border-t border-border pt-3 font-body text-[10.5px] leading-relaxed text-text-muted">These are structural checks, not a fabricated quality score. Review sources and claims with your coauthors.</p>
    </section>
  );
}
