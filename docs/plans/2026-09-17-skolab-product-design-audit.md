# SkoLab Product Design Audit

**Status:** all seven batches created and visually verified in Penpot on 2026-09-22.
**Date:** 2026-09-17
**Decision required:** approve the capability map and inventory before Penpot production begins.

## Purpose

This audit defines the complete SkoLab design surface before UI implementation.
It separates existing implementation from the planned product inventory. SkoLab is
treated as an **evidence-linked research workspace**, not an autonomous
researcher, generic social network, or manuscript-writing chatbot.

## Evidence and standards

The audit draws on the current codebase and public primary sources:

- [OpenAlex](https://help.openalex.org/api/), [Crossref](https://www.crossref.org/documentation/retrieve-metadata/), [PubMed](https://pubmed.ncbi.nlm.nih.gov/help/), and [Unpaywall](https://data.unpaywall.org/products/api/) for research discovery data.
- [Overleaf](https://www.overleaf.com/about/features-overview), [Zotero](https://www.zotero.org/about/), [ResearchRabbit](https://www.researchrabbit.ai/features), [Connected Papers](https://www.connectedpapers.com/about/), [Elicit](https://elicit.com/blog/systematic-review), [Consensus](https://help.consensus.app/en/articles/9922673-how-consensus-works), and [scite](https://scite.ai/features) for current product boundaries.
- [ORCID](https://www.orcid.org/), [research-software citation](https://cite.research-software.org/researchers/), [GitHub citation files](https://docs.github.com/en/enterprise-cloud%40latest/repositories/managing-your-repositorys-settings-and-features/customizing-your-repository/about-citation-files), [Zenodo](https://zenodo.org/), and [protocols.io](https://www.protocols.io/) for trustworthy research identity, code, data, and method provenance.
- [WCAG 2.2](https://www.w3.org/TR/wcag/), [WAI-ARIA APG](https://www.w3.org/WAI/ARIA/apg/), [GOV.UK Design System](https://design-system.service.gov.uk/), [OWASP ASVS](https://owasp.github.io/www-project-application-security-verification-standard/), [NIST Privacy Framework](https://www.nist.gov/privacy-framework), and [web.dev performance guidance](https://web.dev/performance) as design and release gates.

Researcher discussions are qualitative signal, not prevalence evidence.

## Current repository inventory — verified

| Surface | Count | Meaning |
| --- | ---: | --- |
| Next.js page routes | 15 | Current route files under `apps/web/src/app` |
| Next.js route handlers | 4 | OpenAlex query/proxy handlers |
| Web component source files | 89 | Existing components, including their colocated variants |
| Go route declarations | 47 | Includes backward-compatible aliases |
| Python route declarations | 27 | Includes health, internal, and AI/ML routes |
| Existing registered backend paths | 78 | Go + Python declarations; aliases overlap |

These figures are not design-completeness measures. They are a baseline for
identifying reuse, technical debt, and net-new scope.

## Capability map — approval gate 1

| Module | Responsibility | Depends on |
| --- | --- | --- |
| platform-foundation | Design system, accessibility, search, system states, telemetry | — |
| identity-trust | Account, consent, profile matching, ORCID/OpenAlex verification | — |
| research-graph | Papers, authors, topics, venues, citations, open-access metadata, alerts | identity-trust |
| research-brief | Daily relevance, following feed, trends, explainable recommendations | research-graph |
| evidence-workspace | Evidence records, claims, comparisons, verification, decisions | research-graph, identity-trust |
| colab | Projects, manuscripts, references, review, collaboration, compilation | evidence-workspace, identity-trust |
| methodology-learning | Methods, self-directed paths, protocol and code links | research-graph, evidence-workspace |
| researcher-network | Profiles, follow, collaboration requests, connections, groups | identity-trust, research-graph |
| settings-governance | Privacy, notifications, exports, integrations, audit history | identity-trust |

Build order: platform-foundation + identity-trust → research-graph →
research-brief + evidence-workspace → colab + methodology-learning +
researcher-network → settings-governance.

## Proposed Penpot design inventory

| Inventory | Planned count | Counting rule |
| --- | ---: | --- |
| Primary desktop screens | 76 | Route, primary panel mode, or full-page task with distinct IA |
| Mobile adaptations | 34 | Layouts that cannot safely scale from desktop |
| Full-screen system states | 28 | Loading, empty, permission, offline, error, not-found, first-use |
| Persistent overlays | 42 | Dialogs, drawers, sheets, inspectors, confirmations, command palette |
| Interaction prototypes | 18 | Critical end-to-end flows |
| **Total Penpot frames** | **198** | Variants are visible rather than hidden implementation assumptions |

### Primary screen distribution

| Area | Screens |
| --- | ---: |
| Public, auth, onboarding | 9 |
| Home and research brief | 8 |
| Discovery and paper intelligence | 11 |
| Evidence workspace | 9 |
| CoLab project and manuscript | 15 |
| Methodology, code, self-directed learning | 8 |
| Researcher network and profile | 9 |
| Settings, governance, support | 7 |
| **Total** | **76** |

## Component and action inventory

### Reusable component contracts

| Layer | Count | Examples |
| --- | ---: | --- |
| Foundations | 13 | Tokens, icon rules, elevation, focus, motion, grid |
| Primitive controls | 18 | Button, icon button, fields, select, combobox, checkbox, switch, tabs |
| Navigation and shells | 10 | Product ribbon, rail, breadcrumbs, page header, mobile nav |
| Feedback and state | 9 | Alert, toast, banner, skeleton, empty, error, progress, permission |
| Research primitives | 18 | Paper row, author card, citation badge, evidence, claim, decision |
| Complex patterns | 16 | Filter builder, compare table, graph legend, review thread, sharing |
| **Total reusable components** | **84** | Screen compositions are not double-counted |

An exact rendered button count cannot be honest before layouts, permissions, and
data-driven lists are designed. The auditable unit is a **unique action
contract**, and the current design budget is **176**.

| Action family | Contracts |
| --- | ---: |
| Identity and account | 14 |
| Discovery, filters, saves, alerts | 24 |
| Papers, citations, evidence, claims | 31 |
| CoLab writing, review, references, collaboration | 38 |
| Methods, code, protocols, learning | 18 |
| Network, profile, invitations, groups | 22 |
| Settings, export, privacy, administration | 17 |
| Shared navigation and recovery | 12 |
| **Total** | **176** |

Every action contract needs: trigger, destination/state change, permission
condition, loading behavior, success feedback, error behavior, keyboard
behavior, and relevant unavailable/no-access state.

## Backend contract budget

The final endpoint count cannot be locked before the data model is approved.
This is a design-contract budget counting a REST `method + resource path`,
not aliases, health checks, or implementation internals.

| API domain | Existing reusable semantics | New/redesigned contracts | Target |
| --- | ---: | ---: | ---: |
| Identity, profile, consent, settings | 9 | 13 | 22 |
| Scholarly graph, search, feeds, alerts | 20 | 18 | 38 |
| Papers, citations, evidence, claims | 8 | 22 | 30 |
| Projects, manuscripts, collaboration, review | 10 | 24 | 34 |
| Methods, code, protocols, learning | 4 | 13 | 17 |
| Network, invitations, groups | 7 | 14 | 21 |
| **Total product contracts** | **58** | **104** | **162** |

This is a maximum design-contract budget, not an instruction to build 162
endpoints. Resource APIs, batch requests, and query parameters can reduce paths
without deleting capability. Every list needs pagination and typed filters;
every state-changing contract needs explicit retry/idempotency behavior.

## Non-negotiable audit checks

### Product and scientific integrity

- [ ] Every module maps to an evidenced researcher job-to-be-done.
- [ ] Every recommendation says why it is shown.
- [ ] Every generated/extracted statement exposes source, location, and verification state.
- [ ] Supporting, contrasting, and missing evidence are distinct states.
- [ ] Citation counts never become a quality score.
- [ ] Code links preserve repository, version/commit when available, license, citation metadata, and a non-endorsement note.
- [ ] Retractions, corrections, and contradictory new evidence can affect existing claims.

### Accessibility and interaction

- [ ] WCAG 2.2 AA is the baseline.
- [ ] Custom controls follow a WAI-ARIA APG pattern or use native HTML.
- [ ] Keyboard journeys cover editor, command search, filters, dialogs, graphs, and comments.
- [ ] Graphs and PDF/manuscript output have text alternatives.
- [ ] Every relevant screen includes loading, empty, partial/stale, error, permission, and success states.
- [ ] Overlays define focus, Escape, return focus, and mobile-sheet behavior.

### Privacy, security, performance

- [ ] Projects are private by default.
- [ ] Contact details are never exposed merely because public metadata contains them.
- [ ] Consent, imports, retention, deletion, and export are explicit product flows.
- [ ] Roles cover owner, editor, commenter, viewer, and removed/blocked collaborator.
- [ ] High-volume lists, maps, and feeds are paginated, virtualized, or capped.
- [ ] Cached/stale data has timestamp and refresh behavior.
- [ ] Integration failure and degraded modes are designed.

## Penpot delivery batches — approval gate 2 before each batch

| Batch | Linked surfaces | Frames |
| --- | --- | ---: |
| 0 | Foundations, tokens, component anatomy, application shell | 24 |
| 1 | Auth, identity, onboarding, profile import | 22 |
| 2 | Home, research brief, following, trends, notifications | 29 |
| 3 | Discovery, paper detail, reader, citations, compare | 34 |
| 4 | Evidence inbox, claim ledger, evidence records, contradictions, decisions | 27 |
| 5 | CoLab project, manuscript, references, review, history, sharing | 36 |
| 6 | Methodology, code, protocols, learning | 15 |
| 7 | Network, groups, collaboration requests, settings, governance | 11 |
| **Total** | | **198** |

A batch is not complete merely because every frame looks polished. It must pass
component, action, state, accessibility, privacy, and API/data-dependency
review.

## Decisions required before Penpot begins

1. **Platform:** web-first responsive product, or native Android parity from
   the first design batch? This audit assumes web-first responsive.
2. **Identity:** optional ORCID verification, or mandatory verification for
   collaboration requests? This audit assumes optional but encouraged.
3. **Content:** permitted full-PDF upload, or metadata/links plus explicit
   imports only? This audit assumes metadata, permitted PDFs, and external
   code/data links only.
4. **Collaboration contact:** platform requests only, or user-initiated
   mailto/SMS handoff? This audit assumes platform requests plus user-initiated
   handoff, never scraped or exposed contact details.
5. **AI:** sourced assistance and explicit verification states only? This audit
   assumes yes: AI may retrieve, structure, and propose; it cannot silently
   write verified claims.

## Outcome

The complete design should be built around inspectable research objects and calm,
high-density work surfaces. The differentiating path remains:

`source → evidence → claim → uncertainty → decision → manuscript → citation history`

**Batch 0 delivery evidence:** 24 named foundation frames were created on the
Penpot page `00 · Foundations & Product Shell`. The frame inventory, expected
names, containment, local token catalog, and live PNG exports for the design
direction, product ribbon, paper/author primitive, and evidence/decision
primitive were checked. The product-ribbon specimen was revised after visual
inspection. Approval of each remaining batch is still required before it starts.

**Batch 1 delivery evidence:** 22 named identity and onboarding frames were
created on the Penpot page `01 · Identity & Onboarding`. The frame inventory
and containment checks passed; every one of the 22 frames was rendered as a
live PNG and visually reviewed. The flow preserves optional ORCID/OpenAlex
enrichment, explicit data consent, non-automatic identity matching, and
private-by-default collaboration.

**Batch 2 delivery evidence:** 29 named home, research-brief, following,
trend, alert, state, mobile, keyboard, and accessibility frames were created
on the Penpot page `02 · Research Brief & Discovery`. The frame inventory and
containment checks passed; every frame was rendered as a live PNG and visually
reviewed. Seven frames were explicitly iterated after visual defects or
insufficient task specificity were found: method signal, paper alert, stale
brief, no-results, trend comparison, topic-alert creation, and offline brief.

**Batch 3 delivery evidence:** 34 named discovery, paper-detail, reader,
citation-network, comparison, reference-management, export, mobile,
keyboard, offline, and permission frames were created on the Penpot page
`03 · Discovery & Paper Intelligence`. All 34 frames were rendered as live
PNGs and visually reviewed. The final containment audit found 34 boards and
zero out-of-board descendants. The paper-citation, author, reader-source,
reader-annotation, comparison-table, reference-import, reference-conflict,
citation-preview, export, mobile, and permission states were iterated after
visual review to remove collisions and replace generic or incomplete task
content.

**Batch 4 delivery evidence:** 27 named evidence, claim, contradiction,
decision, import, recovery, mobile, and keyboard frames were created on the
Penpot page `04 · Evidence & Decisions`. Every frame was rendered as a live
PNG and reviewed. The review found left-edge clipping in contradiction,
decision, import, gap, verification, permission, and related state frames;
the affected layouts were iterated and re-exported. The final containment
audit found 27 boards and zero out-of-board descendants.

**Batches 5–7 delivery evidence:** 62 named frames were created across the
Penpot pages `05 · CoLab Manuscript & Collaboration` (36), `06 · Methods,
Code & Learning` (15), and `07 · Network & Governance` (11). Every frame was
rendered as a live PNG and visually reviewed. Batch 5 covers manuscript
writing, compiling, citation, review, collaboration, mobile, recovery, and
activity states. Batch 6 covers protocols, code links, self-directed learning,
reproducibility, mobile, and resilient access. Batch 7 covers profiles,
groups, requests, visibility, privacy, security, preferences, and offline
network access. The review found and corrected three title-edge crops in Batch
6 and one in Batch 7; all four affected frames were re-exported cleanly.
