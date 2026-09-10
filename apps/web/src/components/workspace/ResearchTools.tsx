"use client";

import { useMemo, useState } from "react";
import {
  ExternalLink,
  FileStack,
  Phone,
  Share2,
  ShieldCheck,
  Sparkles,
} from "lucide-react";
import { Button } from "@/components/ui/Button";
import { Card } from "@/components/ui/Card";
import type { CollabDocument } from "@/lib/types";

export const JOURNAL_TEMPLATES = [
  {
    id: "imrad",
    label: "IMRaD · General research",
    body: "# Title\n\n**Authors:** Add author names and affiliations.\n\n## Abstract\n\nSummarise the question, method, result and conclusion in 150–250 words.\n\n## 1. Introduction\n\nState the problem, gap and contribution.\n\n## 2. Methods\n\nDescribe data, materials, protocol and analysis so another researcher can reproduce it.\n\n## 3. Results\n\nReport findings with figures, tables and uncertainty.\n\n## 4. Discussion\n\nInterpret the result, limitations and competing explanations.\n\n## Conclusion\n\nState the answer and the next experiment.\n\n## References\n\n- Add DOI-backed references here.\n",
  },
  {
    id: "ieee",
    label: "IEEE-style technical paper",
    body: "# Paper Title\n\n**Author One**, **Author Two**\n\n## Abstract\n\nWrite a concise technical abstract.\n\n## Index Terms\n\nterm one · term two · term three\n\n## I. Introduction\n\nMotivation, prior work and contribution.\n\n## II. Related Work\n\nCompare the closest methods and cite each claim.\n\n## III. Methodology\n\nDefine the system, assumptions and evaluation protocol.\n\n## IV. Results\n\nPresent quantitative results and statistical uncertainty.\n\n## V. Conclusion\n\nSummarise findings and future work.\n\n## References\n\n[1] Add a DOI-backed reference.\n",
  },
  {
    id: "nature",
    label: "Nature-style research article",
    body: "# Title\n\n**Authors and affiliations**\n\n## Abstract\n\nA brief, accessible summary of the finding and why it matters.\n\n## Main\n\n### Background\n\nExplain the context for a broad scientific audience.\n\n### Results\n\nLead with the central result, then supporting analyses.\n\n### Interpretation\n\nExplain implications, limitations and alternative interpretations.\n\n## Methods\n\nProvide enough detail to reproduce the work.\n\n## Data availability\n\nState where data and code can be accessed.\n\n## References\n\n- Add DOI-backed references here.\n",
  },
  {
    id: "review",
    label: "Systematic review",
    body: "# Review Title\n\n## Abstract\n\nBackground · objectives · search strategy · results · conclusion.\n\n## Research question\n\nDefine the scope and inclusion criteria.\n\n## Search strategy\n\nList databases, dates, query and screening protocol.\n\n## Study selection\n\nRecord exclusions and a PRISMA-style flow.\n\n## Evidence synthesis\n\nCompare findings, quality and contradictions.\n\n## Limitations\n\nState search, publication and methodological bias.\n\n## Conclusion\n\nState what the evidence supports and what remains unknown.\n\n## References\n\n- Add DOI-backed references here.\n",
  },
] as const;

function projectRoomUrl(projectId: string) {
  return `https://meet.jit.si/skolab-${projectId}`;
}

export function WorkspaceResearchActions({
  projectId,
  projectName,
  documentTitle,
  documentBody,
  documents,
  canEdit,
  onApplyTemplate,
}: {
  projectId: string;
  projectName: string;
  documentTitle: string;
  documentBody: string;
  documents: CollabDocument[];
  canEdit: boolean;
  onApplyTemplate: (body: string) => Promise<void>;
}) {
  const [templateOpen, setTemplateOpen] = useState(false);
  const [originalityOpen, setOriginalityOpen] = useState(false);
  const [status, setStatus] = useState<string | null>(null);

  const preflight = useMemo(() => {
    const words = documentBody.trim().split(/\s+/).filter(Boolean);
    const quotedWords = (documentBody.match(/"[^"\n]{20,}"/g) ?? []).join(" ").split(/\s+/).filter(Boolean).length;
    const citations = (documentBody.match(/doi\.org\/|https?:\/\/|\[[0-9]+\]/gi) ?? []).length;
    const headings = (documentBody.match(/^#{1,4}\s/gm) ?? []).length;
    const otherText = documents.filter((d) => d.title !== documentTitle).map((d) => d.body).join(" ").toLowerCase();
    const repeated = words.length >= 12
      ? words.reduce((count, word, index) => count + (index > 0 && otherText.includes(`${words[index - 1]?.toLowerCase()} ${word.toLowerCase()}`) ? 1 : 0), 0)
      : 0;
    return { words: words.length, citations, headings, quotedWords, repeated };
  }, [documentBody, documentTitle, documents]);

  async function copy(value: string, message: string) {
    try {
      await navigator.clipboard.writeText(value);
      setStatus(message);
    } catch {
      setStatus("Copy was blocked by the browser. Use the room link from the address bar.");
    }
  }

  async function sharePaper() {
    const url = window.location.href;
    if (navigator.share) {
      await navigator.share({ title: `${documentTitle} · ${projectName}`, text: "Research document shared from SkoLab", url }).catch(() => {});
      setStatus("Paper link ready to share.");
      return;
    }
    await copy(url, "Paper link copied.");
  }

  function startCall() {
    const room = projectRoomUrl(projectId);
    window.open(room, "_blank", "noopener,noreferrer");
    setStatus("Secure room opened. Share the room link with collaborators.");
  }

  async function applyTemplate(templateId: string) {
    const template = JOURNAL_TEMPLATES.find((item) => item.id === templateId);
    if (!template || !canEdit) return;
    await onApplyTemplate(template.body);
    setTemplateOpen(false);
    setStatus(`${template.label} applied to ${documentTitle}.`);
  }

  return (
    <div className="relative flex flex-wrap items-center gap-1.5">
      <Button type="button" variant="outlined" fullWidth={false} onClick={startCall} className="h-8! gap-1.5 px-2.5! text-[11px]!">
        <Phone size={12} /> Call
      </Button>
      <Button type="button" variant="outlined" fullWidth={false} onClick={sharePaper} className="h-8! gap-1.5 px-2.5! text-[11px]!">
        <Share2 size={12} /> Share paper
      </Button>
      <div className="relative">
        <Button type="button" variant="outlined" fullWidth={false} disabled={!canEdit} onClick={() => setTemplateOpen((open) => !open)} className="h-8! gap-1.5 px-2.5! text-[11px]!">
          <FileStack size={12} /> Templates
        </Button>
        {templateOpen && (
          <div className="absolute right-0 top-10 z-30 w-64 rounded-md border border-border bg-surface p-1.5 shadow-elevated">
            <p className="px-2 py-1.5 font-mono text-[10px] uppercase tracking-wide text-text-muted">Start from a standard structure</p>
            {JOURNAL_TEMPLATES.map((template) => (
              <button key={template.id} type="button" onClick={() => applyTemplate(template.id)} className="flex w-full items-center rounded px-2 py-2 text-left font-body text-[12px] text-text-secondary hover:bg-surface-subtle hover:text-text-primary">
                {template.label}
              </button>
            ))}
          </div>
        )}
      </div>
      <Button type="button" variant="outlined" fullWidth={false} onClick={() => setOriginalityOpen((open) => !open)} className="h-8! gap-1.5 px-2.5! text-[11px]!">
        <ShieldCheck size={12} /> Originality
      </Button>
      {status && <span className="basis-full font-body text-[11px] text-accent-emerald">{status}</span>}
      {originalityOpen && (
        <Card accentColor="var(--accent-teal)" className="absolute right-0 top-10 z-20 w-[min(360px,calc(100vw-2rem))] p-4">
          <div className="flex items-start gap-2">
            <Sparkles size={15} className="mt-0.5 text-accent-teal" />
            <div>
              <h3 className="font-display text-h3 font-semibold text-text-primary">Originality preflight</h3>
              <p className="mt-1 font-body text-[11px] leading-relaxed text-text-muted">This checks structure, citations, quotations and overlap inside this project. It is not an internet plagiarism score.</p>
            </div>
          </div>
          <div className="mt-3 grid grid-cols-2 gap-2 font-mono text-[11px] text-text-secondary">
            <span>{preflight.words} words</span><span>{preflight.headings} headings</span>
            <span>{preflight.citations} citation markers</span><span>{preflight.quotedWords} quoted words</span>
          </div>
          <p className="mt-3 border-t border-border pt-3 font-body text-[11px] leading-relaxed text-text-muted">For publisher-grade similarity checking, connect an institution’s Crossref Similarity Check/iThenticate account. SkoLab will never invent a similarity percentage.</p>
          <a href="https://www.crossref.org/documentation/similarity-check/" target="_blank" rel="noreferrer" className="mt-2 inline-flex items-center gap-1 font-body text-[11px] font-semibold text-primary hover:underline">How scholarly similarity reports work <ExternalLink size={11} /></a>
        </Card>
      )}
    </div>
  );
}
