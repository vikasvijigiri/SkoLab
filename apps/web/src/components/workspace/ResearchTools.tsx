"use client";

import { useMemo, useState } from "react";
import {
  ExternalLink,
  FileStack,
  Phone,
  Share2,
  ShieldCheck,
  Sparkles,
  ChevronDown,
} from "lucide-react";
import { Button } from "@/components/ui/Button";
import { Card } from "@/components/ui/Card";
import { cn } from "@/lib/utils";
import type { CollabDocument } from "@/lib/types";

export type TemplateDomain = "Physics" | "Chemistry" | "Biology" | "Computer Science" | "General";

export const TEMPLATE_DOMAINS: TemplateDomain[] = [
  "Physics",
  "Chemistry",
  "Biology",
  "Computer Science",
  "General",
];

/**
 * Real submission specs, sourced from each publisher's public author
 * guidelines. Only fields the guidelines actually state a number for are
 * filled in — a journal that caps length by page count rather than word
 * count (PRB, NJP, EPL) intentionally has no `wordLimit`/`abstractCharLimit`
 * rather than a guessed conversion, matching this file's existing
 * originality-preflight rule: counts and structure only, never a fabricated
 * number.
 */
export interface JournalTemplateSpec {
  publisher: string;
  /** One-line summary shown in the picker, e.g. "4-page limit · REVTeX 4.2 · abstract ≤600 characters". */
  summary: string;
  pageLimit?: number;
  wordLimit?: number;
  abstractCharLimit?: number;
  referenceStyle: string;
}

export interface JournalTemplate {
  id: string;
  domain: TemplateDomain;
  label: string;
  specs?: JournalTemplateSpec;
  body: string;
}

export const JOURNAL_TEMPLATES: JournalTemplate[] = [
  {
    id: "imrad",
    domain: "General",
    label: "IMRaD · General research",
    body: "# Title\n\n**Authors:** Add author names and affiliations.\n\n## Abstract\n\nSummarise the question, method, result and conclusion in 150–250 words.\n\n## 1. Introduction\n\nState the problem, gap and contribution.\n\n## 2. Methods\n\nDescribe data, materials, protocol and analysis so another researcher can reproduce it.\n\n## 3. Results\n\nReport findings with figures, tables and uncertainty.\n\n## 4. Discussion\n\nInterpret the result, limitations and competing explanations.\n\n## Conclusion\n\nState the answer and the next experiment.\n\n## References\n\n- Add DOI-backed references here.\n",
  },
  {
    id: "ieee",
    domain: "General",
    label: "IEEE-style technical paper",
    body: "# Paper Title\n\n**Author One**, **Author Two**\n\n## Abstract\n\nWrite a concise technical abstract.\n\n## Index Terms\n\nterm one · term two · term three\n\n## I. Introduction\n\nMotivation, prior work and contribution.\n\n## II. Related Work\n\nCompare the closest methods and cite each claim.\n\n## III. Methodology\n\nDefine the system, assumptions and evaluation protocol.\n\n## IV. Results\n\nPresent quantitative results and statistical uncertainty.\n\n## V. Conclusion\n\nSummarise findings and future work.\n\n## References\n\n[1] Add a DOI-backed reference.\n",
  },
  {
    id: "nature",
    domain: "General",
    label: "Nature-style research article",
    body: "# Title\n\n**Authors and affiliations**\n\n## Abstract\n\nA brief, accessible summary of the finding and why it matters.\n\n## Main\n\n### Background\n\nExplain the context for a broad scientific audience.\n\n### Results\n\nLead with the central result, then supporting analyses.\n\n### Interpretation\n\nExplain implications, limitations and alternative interpretations.\n\n## Methods\n\nProvide enough detail to reproduce the work.\n\n## Data availability\n\nState where data and code can be accessed.\n\n## References\n\n- Add DOI-backed references here.\n",
  },
  {
    id: "review",
    domain: "General",
    label: "Systematic review",
    body: "# Review Title\n\n## Abstract\n\nBackground · objectives · search strategy · results · conclusion.\n\n## Research question\n\nDefine the scope and inclusion criteria.\n\n## Search strategy\n\nList databases, dates, query and screening protocol.\n\n## Study selection\n\nRecord exclusions and a PRISMA-style flow.\n\n## Evidence synthesis\n\nCompare findings, quality and contradictions.\n\n## Limitations\n\nState search, publication and methodological bias.\n\n## Conclusion\n\nState what the evidence supports and what remains unknown.\n\n## References\n\n- Add DOI-backed references here.\n",
  },
  {
    id: "prl",
    domain: "Physics",
    label: "Physical Review Letters",
    specs: {
      publisher: "APS",
      summary: "4-page limit · REVTeX 4.2 · abstract ≤600 characters",
      pageLimit: 4,
      wordLimit: 3750,
      abstractCharLimit: 600,
      referenceStyle: "REVTeX 4.2 (APS numbered)",
    },
    body: "# Title\n\n**Authors:** Add author names and affiliations.\n\n## Abstract\n\n(≤600 characters) State the result and its significance in one paragraph.\n\n## Letter\n\nPresent the motivation, method, central result and its interpretation as a single flowing narrative — PRL's Letter format does not require numbered sections.\n\n## References\n\n[1] Add a REVTeX-formatted reference.\n",
  },
  {
    id: "prb",
    domain: "Physics",
    label: "Physical Review B",
    specs: {
      publisher: "APS",
      summary: "Sectioned I / II / III · REVTeX 4.2 · no page cap",
      referenceStyle: "REVTeX 4.2 (APS numbered)",
    },
    body: "# Title\n\n**Authors:** Add author names and affiliations.\n\n## Abstract\n\nSummarise the problem, method and central result.\n\n## I. Introduction\n\nState the problem, prior work and contribution.\n\n## II. Methods\n\nDescribe the model, computational or experimental protocol.\n\n## III. Results\n\nPresent the findings with figures and uncertainty.\n\n## IV. Discussion\n\nInterpret the result against existing theory or experiment.\n\n## V. Conclusion\n\nState the outcome and open questions.\n\n## References\n\n[1] Add a REVTeX-formatted reference.\n",
  },
  {
    id: "njp",
    domain: "Physics",
    label: "New Journal of Physics",
    specs: {
      publisher: "IOP",
      summary: "Harvard/Vancouver refs · declarations required",
      referenceStyle: "Harvard or Vancouver (IOP house style)",
    },
    body: "# Title\n\n**Authors:** Add author names and affiliations.\n\n## Abstract\n\nSummarise the question, method and finding for a broad physics audience.\n\n## Introduction\n\nMotivate the problem and state the contribution.\n\n## Methods\n\nDescribe data, apparatus or model in enough detail to reproduce it.\n\n## Results\n\nReport findings with figures and uncertainty.\n\n## Discussion\n\nInterpret the result and its limitations.\n\n## Conclusion\n\nState what was learned and what remains open.\n\n## Data availability statement\n\nState where the underlying data can be accessed.\n\n## Competing interests\n\nDeclare any competing interests, or state that there are none.\n\n## References\n\n- Add a Harvard- or Vancouver-style reference.\n",
  },
  {
    id: "epl",
    domain: "Physics",
    label: "EPL",
    specs: {
      publisher: "Europhysics Letters",
      summary: "≤7 pages · two-column · AIP numeric refs",
      pageLimit: 7,
      referenceStyle: "AIP numeric",
    },
    body: "# Title\n\n**Authors:** Add author names and affiliations.\n\n## Abstract\n\nA one-paragraph summary suitable for a two-column Letter.\n\n## Letter\n\nPresent the motivation, method and central result concisely — EPL favours a compact, unsectioned Letter format.\n\n## References\n\n[1] Add an AIP-numeric-style reference.\n",
  },
];

/** Citation-marker count shared by the originality preflight and the
 *  Quick Reference panel — one definition of "a citation" for both. */
export function countCitationMarkers(body: string): number {
  return (body.match(/doi\.org\/|https?:\/\/|\[[0-9]+\]/gi) ?? []).length;
}

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
  selectedTemplateId,
  onSelectTemplate,
  templatesOpen,
  onTemplatesOpenChange,
}: {
  projectId: string;
  projectName: string;
  documentTitle: string;
  documentBody: string;
  documents: CollabDocument[];
  canEdit: boolean;
  onApplyTemplate: (body: string) => Promise<void>;
  /** Controlled selection, shared with the Quick Reference panel so both
   *  read the same journal. Uncontrolled (internal state) when omitted. */
  selectedTemplateId?: string | null;
  onSelectTemplate?: (id: string | null) => void;
  /** Controlled open state so "Browse all domains & journals" in the Quick
   *  Reference panel can open this same dropdown. Uncontrolled when omitted. */
  templatesOpen?: boolean;
  onTemplatesOpenChange?: (open: boolean) => void;
}) {
  const [internalTemplateOpen, setInternalTemplateOpen] = useState(false);
  const templateOpen = templatesOpen ?? internalTemplateOpen;
  const setTemplateOpen = onTemplatesOpenChange ?? setInternalTemplateOpen;

  const [internalSelected, setInternalSelected] = useState<string | null>(null);
  const currentSelectedId = selectedTemplateId !== undefined ? selectedTemplateId : internalSelected;
  const setCurrentSelectedId = onSelectTemplate ?? setInternalSelected;

  const [domain, setDomain] = useState<TemplateDomain>("General");
  const [originalityOpen, setOriginalityOpen] = useState(false);
  const [status, setStatus] = useState<string | null>(null);

  const selectedTemplate = JOURNAL_TEMPLATES.find((t) => t.id === currentSelectedId);

  const preflight = useMemo(() => {
    const words = documentBody.trim().split(/\s+/).filter(Boolean);
    const quotedWords = (documentBody.match(/"[^"\n]{20,}"/g) ?? []).join(" ").split(/\s+/).filter(Boolean).length;
    const citations = countCitationMarkers(documentBody);
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
    setCurrentSelectedId(templateId);
    setTemplateOpen(false);
    setStatus(`${template.label} applied to ${documentTitle}.`);
  }

  const domainTemplates = JOURNAL_TEMPLATES.filter((t) => t.domain === domain);

  return (
    <div className="relative flex flex-wrap items-center gap-1.5">
      <Button type="button" variant="outlined" fullWidth={false} onClick={startCall} className="h-8! gap-1.5 px-2.5! text-[11px]!">
        <Phone size={12} /> Call
      </Button>
      <Button type="button" variant="outlined" fullWidth={false} onClick={sharePaper} className="h-8! gap-1.5 px-2.5! text-[11px]!">
        <Share2 size={12} /> Share paper
      </Button>
      <div className="relative">
        <Button
          type="button"
          variant="outlined"
          fullWidth={false}
          disabled={!canEdit}
          onClick={() => setTemplateOpen(!templateOpen)}
          className="h-8! gap-1.5 px-2.5! text-[11px]!"
        >
          <FileStack size={12} /> Templates
          {selectedTemplate?.specs && (
            <span className="mono rounded-full bg-primary px-1.5 py-0.5 text-[9px] font-bold text-text-on-primary">
              {selectedTemplate.id.toUpperCase()}
            </span>
          )}
          <ChevronDown size={10} className={cn("transition-transform", templateOpen && "rotate-180")} />
        </Button>
        {templateOpen && (
          <div className="absolute right-0 top-10 z-30 w-[340px] rounded-md border border-border bg-surface shadow-elevated">
            <div className="flex flex-wrap gap-1 border-b border-border p-2">
              {TEMPLATE_DOMAINS.map((d) => (
                <button
                  key={d}
                  type="button"
                  onClick={() => setDomain(d)}
                  className={cn(
                    "rounded-full px-2.5 py-1 font-body text-[11px] font-medium transition-colors",
                    domain === d
                      ? "bg-primary text-text-on-primary"
                      : "bg-surface-subtle text-text-secondary hover:text-text-primary",
                  )}
                >
                  {d}
                </button>
              ))}
            </div>
            <p className="px-3 pb-1 pt-2 font-mono text-[10px] uppercase tracking-wide text-text-muted">
              Start from a {domain === "General" ? "standard structure" : `${domain} journal`}
            </p>
            {domainTemplates.length === 0 ? (
              <p className="px-3 pb-3 font-body text-[12px] leading-relaxed text-text-muted">
                No journal-specific template for {domain} yet — use a General structure instead.
              </p>
            ) : (
              <div className="max-h-72 overflow-y-auto pb-1.5">
                {domainTemplates.map((template) => (
                  <button
                    key={template.id}
                    type="button"
                    onClick={() => applyTemplate(template.id)}
                    className="flex w-full items-center gap-2.5 border-b border-border px-3 py-2.5 text-left transition-colors last:border-b-0 hover:bg-surface-subtle"
                  >
                    <div className="min-w-0 flex-1">
                      <p className="truncate font-body text-[13px] font-semibold text-text-primary">
                        {template.label}
                        {template.specs && (
                          <span className="font-normal text-text-muted"> · {template.specs.publisher}</span>
                        )}
                      </p>
                      {template.specs && (
                        <p className="mt-0.5 truncate font-body text-[11px] text-text-muted">
                          {template.specs.summary}
                        </p>
                      )}
                    </div>
                    {currentSelectedId === template.id && (
                      <span className="mono shrink-0 text-[9.5px] font-bold text-primary">SELECTED</span>
                    )}
                  </button>
                ))}
              </div>
            )}
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
