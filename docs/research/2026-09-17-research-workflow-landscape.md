# SkoLab research-workflow landscape and product gaps
**Date:** 2026-09-17
**Status:** discovery baseline for the next CoLab design pass

## Executive finding

The market is split by stage. Overleaf optimizes the manuscript and its
compile/review loop; Zotero manages the reference library and annotations;
ResearchRabbit and Litmaps help researchers navigate citation networks; Elicit
supports structured review screening and extraction; scite evaluates citation
context. Each product is useful inside its own stage, but the researcher still
has to manually carry meaning across tools.

The strongest SkoLab opportunity is an **evidence-to-action workspace**: a
researcher should be able to move from a source or observation to a traceable
claim, an explicit uncertainty, an assigned next action, and finally a cited
manuscript passage without reconstructing the chain from memory.

This is a product hypothesis, not a claim of market validation. It must be
tested with interviews and observed workflow sessions before it becomes a
roadmap commitment.

## What existing products do well

| Product | Strongest job | Evidence | Boundary / opening for SkoLab |
| --- | --- | --- | --- |
| Overleaf | Write, compile, review, and share a technical manuscript | Collaborative editing, comments, track changes, history, compile output, and errors are first-class features ([features](https://www.overleaf.com/about/features-overview), [collaboration](https://docs.overleaf.com/collaborating/collaborating-in-overleaf), [history](https://docs.overleaf.com/writing-and-editing/history-and-versioning)) | The writing surface knows the document state, but not the researcher’s evidence decisions or unresolved synthesis questions |
| Zotero | Capture, annotate, retrieve, and cite sources | PDF annotations can be pulled into notes with page links and citations; groups and bibliographies are supported ([PDF reader](https://www.zotero.org/support/pdf_reader), [quick start](https://www.zotero.org/support/quick_start_guide)) | Knowledge remains difficult to retrieve across paper silos; researchers commonly add a second notes/database tool |
| ResearchRabbit | Explore literature through citation relationships | Seed papers produce connected recommendations, collections, iterative exploration, and gap-oriented map reading ([guide](https://www.researchrabbit.ai/articles/guide-to-using-researchrabbit), [features](https://www.researchrabbit.ai/features)) | Discovery is strong, but the output is not yet a claim ledger, evidence record, or writing-ready argument |
| Litmaps | Map a literature set and monitor it over time | Citation-based search, visual maps, annotation, Zotero sync, sharing, and alerts ([features](https://www.litmaps.com/features), [help](https://docs.litmaps.com/en/articles/7240465-introduction-to-litmaps)) | A map can show relatedness without showing what a source actually establishes or what decision it changes |
| Elicit | Conduct structured literature reviews | Search, screening criteria, screening decisions, extraction criteria, and extraction are organized as a workflow ([systematic review](https://elicit.com/blog/systematic-review), [API](https://docs.elicit.com/)) | Strong review pipeline, but synthesis-to-manuscript continuity and team accountability remain separate concerns |
| scite | Check how later work cites a paper | Citation statements are classified as supporting, contrasting, or mentioning; collections can monitor new citations and retractions ([features](https://scite.ai/features)) | Citation context is valuable but still needs to be attached to the researcher’s own claim, argument, and decision |

## Recurring researcher friction

These are triangulated from product capabilities, recent researcher discussions,
and the literature on information-seeking. Reddit is treated as qualitative
signal, not prevalence evidence.

1. **Context is lost between capture, reading, and writing.** Researchers save
   a paper for a reason, then later remember the paper but not the exact claim,
   passage, page, or intended use. A recent PhD workflow discussion explicitly
   describes avoiding repeated rereading and wanting retrieval by topic; another
   describes the reason for saving a paper becoming disconnected across tools
   ([workflow discussion](https://www.reddit.com/r/GradSchool/comments/1svrku9/phd_workflow_advice/),
   [systems discussion](https://www.reddit.com/r/PhD/comments/1svrku9/phd_workflow_advice/)).
2. **The personal stack becomes a translation tax.** Zotero, Obsidian/Notion,
   a PDF reader, a spreadsheet, and Overleaf/Word each hold part of the work.
   Setup and retrieval become work of their own ([workflow pain discussion](https://www.reddit.com/r/PhdProductivity/comments/1tkhqkr/lets_actually_talk-about-research-workflows/),
   [citation discussion](https://www.reddit.com/r/PhdProductivity/comments/1srt9u7/how_do_you_all_actually_manage_your_citations/)).
3. **Discovery is not synthesis.** Citation maps and semantic search help find
   candidates, but the researcher still has to compare populations, methods,
   measures, limitations, and contradictions in a consistent structure.
4. **AI output is useful only when inspectable.** Researchers repeatedly call
   out hallucination and the need to verify generated summaries. A design that
   hides provenance will not earn trust, even if its summary is fluent. This is
   consistent with published evaluations noting manual verification needs for
   ambiguous PDFs and OCR ([review of AI research tools](https://www.csr-pub.eu/files/The_Use_of_AI_in_Academic_Work.pdf)).
5. **Review work is distributed across time and people.** Supervisors and
   coauthors make decisions asynchronously; comments, requests for sources,
   screening disagreements, and unresolved questions can disappear into chat,
   email, or document comments. Overleaf solves document-local review, not the
   broader evidence-decision queue.
6. **Research is nonlinear, but tools present linear stages.** Information
   behavior research describes searching as iterative and dynamic rather than a
   single funnel. SkoLab should support revisiting a question, changing
   inclusion logic, and preserving why the change happened ([researcher
   information-seeking study](https://onlinelibrary.wiley.com/doi/full/10.1002/asi.21307),
   [information behavior review](https://www.journals.uchicago.edu/doi/10.1086/602622)).

## Jobs-to-be-done model

| Moment | Researcher job | Failure today | SkoLab opportunity |
| --- | --- | --- | --- |
| Capture | Save an idea or paper with the reason it matters | Bookmark/PDF without durable intent | Capture with question, context, and source provenance |
| Triage | Decide read / defer / exclude | Long reading lists and weak prioritization | Evidence inbox with explicit decision and rationale |
| Read | Extract only what changes the project | Notes trapped per paper or copied without page context | Highlight-to-evidence record with page/quote/metadata |
| Compare | See agreement, contradiction, and missing evidence | Spreadsheet or memory | Claim ledger / evidence matrix with confidence and conflict states |
| Write | Turn an evidence-backed decision into prose | Tab switching and citation hunting | Insert a traceable evidence block into CoLab |
| Review | Ask for a source, resolve disagreement, assign follow-up | Comments and chat lose project context | Review queue attached to claims, sources, and manuscript locations |
| Maintain | Keep conclusions current as literature changes | Alerts are disconnected from argument | Change impact: new citation, retraction, contradiction, or stale claim |

## Product principles for SkoLab

- **Provenance before polish.** Every generated or extracted statement should
  expose source, location, and the user’s level of verification.
- **Claims are first-class objects.** A paper is not the unit of work; a claim,
  its evidence, and its unresolved objections are.
- **Progress means decisions, not document activity.** “12 papers saved” is
  weaker than “8 sources screened, 3 claims supported, 2 conflicts unresolved.”
- **AI proposes; researchers attest.** Never present an inferred summary as a
  verified finding. Use explicit states such as `unverified`, `researcher
  checked`, `team accepted`, and `needs review`.
- **Keep the manuscript central.** Evidence should appear beside writing when
  needed, then recede when it is not; it should not become a second dashboard.
- **Make disagreement productive.** Contradiction is a navigable research
  state, not a red error.
- **Design for handoff.** A colleague should be able to understand not just the
  current answer, but why the team believes it and what remains open.

## Design gap against the current CoLab board

The existing board is directionally strong: source and preview are central,
the outline is visible, and the right rail already names Sources, Gaps, and
Tasks. The missing layer is operational detail. Those labels do not yet show:

- how a source becomes evidence tied to a claim;
- how conflicting evidence is compared;
- how an AI suggestion is verified or rejected;
- how a gap becomes an assigned, dated next action;
- how review requests persist across manuscript revisions;
- how a citation or retraction changes an existing claim.

Therefore the next design slice should not add more generic panels. It should
make the evidence lifecycle visible in four focused states:

1. **Evidence Inbox:** incoming papers, excerpts, and collaborator requests
   triaged into `keep`, `defer`, or `exclude` with a reason.
2. **Claim Ledger:** claims in the current manuscript mapped to supporting,
   contrasting, and missing evidence, with verification state and provenance.
3. **Evidence record:** the source passage, page, extracted interpretation,
   researcher note, and “insert into manuscript” action in one inspectable view.
4. **Review queue:** requests and disagreements attached to a claim or passage,
   with owner, status, decision, and audit trail.

These states complete the loop around the current editor without displacing it.
They also create a distinctive SkoLab surface that is defensible against
single-stage competitors.

## Validation plan before implementation

Interview or observe at least six researchers across one wet-lab, one
computational, one social-science, one clinical/systematic-review, one
industry-R&D, and one early-career workflow. Ask them to reconstruct a recent
paper or review from the tools they actually used. Measure:

- time from finding a source to using it in a draft;
- number of context switches and duplicate notes;
- how they verify a quoted or summarized claim;
- how unresolved disagreements are tracked;
- what makes them distrust an AI-generated research output;
- whether a claim/evidence ledger is more useful than a paper-centric library.

Do not treat feature requests as validation. The test is whether the proposed
objects and states remove observed workarounds in a real task.
