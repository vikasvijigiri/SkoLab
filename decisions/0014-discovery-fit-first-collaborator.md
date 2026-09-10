# 0014. Discovery — fit-first collaborator finder

**Date:** 2026-09-10
**Status:** Accepted

## Context

Discovery's "Top researchers in <field>" surface made the user click through a
`field → subfield → topic` taxonomy the app already knows (onboarding resolves
the user to an OpenAlex author), then listed authors by `cited_by_count:desc`.
That sort is monotonic with career length and field size, so it structurally
surfaces old, famous, oversubscribed people — the live site showed N. F. Mott
(d. 1996), P. G. de Gennes (d. 2007) and P. W. Anderson (d. 2020) as
"researchers you could collaborate with". The only metric was a raw `H-154`,
which is not field-normalised, never decreases, and says nothing about whether a
person is active, reachable, or a topical match to *this* user.

## Decision

Rebuild the researcher surface around **fit to the signed-in user**, entirely in
the `apps/web` Next.js + OpenAlex route-handler layer.

### Where it lives

| Concern | Location | Why |
|---|---|---|
| Signal derivation, fit score, OpenAlex mapping | `apps/web/src/lib/discovery/*.ts` (pure, unit-tested) | No model call; keeps every threshold in one place. |
| OpenAlex proxy + Wikidata + collab-flag routes | `apps/web/src/app/api/**` route handlers | Discovery already calls OpenAlex via same-origin Next routes, not the Go gateway (`endpoints.ts` comment). |
| — | **not** `services/backend-go` / `services/backend` | No Go toolchain on the build box (`decisions/0009`); the embedding-cosine fit term stays a future enhancement behind the `fit.ts` interface. |

### Signals per researcher (all from one `/authors` call)

- **Activity** — `active` / `winding_down` / `dormant` from the latest
  `counts_by_year` year with `works_count > 0`, vs `DISCOVERY_ACTIVE_WITHIN_YEARS`
  (2) / `DISCOVERY_WINDING_DOWN_YEARS` (5).
- **Momentum** — OLS slope of `cited_by_count` over the ~10-year window,
  normalised by the mean, vs `DISCOVERY_MOMENTUM_EPSILON` (0.15); `< 3` points
  → `steady`.
- **Career stage** — `emerging` / `established` / `senior` from visible
  years-active (`DISCOVERY_EMERGING_MAX_YEARS` 8 / `DISCOVERY_SENIOR_MIN_YEARS`
  16). A coarse estimate — OpenAlex only exposes ~10 years of history, so a long
  career's true start is invisible; the label is deliberately fuzzy.
- **Active decades**, **topical focus** (per-topic share = `count / Σcount`,
  scoped to the viewer's subfield), **field-normalised standing** (h-index
  percentile *within the returned page*, shown as "top N%").

### Fit score

`scoreFit` blends, with `DISCOVERY_FIT_W_*` weights (env-overridable, mirroring
the Go engine's `SIM_W_*`, `decisions/0011`):
`0.55 · concept-Jaccard(viewer.expertise × candidate topics)`
`+ 0.15 · same-institution + 0.15 · shared-institution-token + 0.15 · topical-focus`,
scaled to 0–100. A cold viewer (no `expertise`) is ranked by topical focus +
standing only, and the list is flagged "ranked by topical overlap" — this is the
degraded mode `decisions/0011` already specifies.

### Deceased flag

OpenAlex has **no death or "is active" field**. For the verifiable few — mostly
prominent names with a Wikidata entry — `GET /api/enrich/deaths` cross-references
Wikidata `P570` by ORCID (`P496`), batched per visible page, week-cached, and
returns `{}` on any upstream failure so the grid never blocks. Everyone else
relies on the `activity` proxy. A missing key means "not known to be deceased",
never "alive".

### "Open to collaboration"

A self-declared signal with no data source yet. `resolveCollabFlags` /
`GET /api/enrich/collab-flags` is a typed seam returning `{}` today; the card
shows the chip only when a flag is strictly `true`. It will be populated from
`researchers/{uid}.openToCollaboration` once professors set it and an
OpenAlex-id → SkoLab-uid index exists. **Nothing is fabricated or inferred.**

### Filters — click-only

Every control is a `Chip` / button toggle with `aria-pressed`, or the
`SegmentedControl` (topical focus: `Any / ≥25% / ≥50% / ≥75%`). No `<input>` of
any kind (memory `no-typing-click-only-ux`; the existing "no text inputs" test
still passes). Country / institution-type options are **derived from the current
result set**, never a hard-coded list. Filter/threshold definitions live once in
`lib/discovery/config.ts`.

### Connection distance

True 2nd-degree (shared co-author) needs the Go `network_collaborators` graph,
which can't be built or verified here. Ships as a **"Shares your institution"**
toggle from the single `/authors` payload; real 2nd-degree is deferred.

## Alternatives considered

- **Add a "score this set" mode to the Go similarity engine** for a real
  embedding-cosine fit term. Rejected for now: no Go toolchain on the build box,
  so it would ship unverified (`decisions/0009`). The `fit.ts` interface is
  shaped so the vector term can be added later without touching callers.
- **Keep the citation sort, just add badges + filters.** Rejected: still
  Einstein-first by default; the "everybody wants Einstein" problem is the whole
  point of the rebuild.
- **A real range `<input>` slider for topical focus** (as first sketched).
  Rejected: breaks the click-only invariant and its test for no real gain; a
  4-stop segmented control reads the same.
- **`topics.subfield.id` / `topics.field.id` author filters.** Not valid on
  OpenAlex `/authors` (verified live) — the route expands a subfield/field to its
  topic-id list via `/topics` and filters `topics.id:T1|T2|…` instead. This also
  fixes the previously-broken drilldown, which was 400ing on those filters.

## Consequences

- **Better:** the default view answers "who works on my problem, is active, and
  could realistically collaborate with me" without a click; titans are one
  toggle away, not the front page.
- **Better:** a student is never told to email a dead researcher (verified
  cases) or a decade-dormant one (activity proxy).
- **New surface:** two `app/api/enrich/*` routes and a Wikidata dependency
  (degrades to `{}`), plus `lib/discovery/` (6 pure modules + tests).
- **Watch:** h-index percentile is computed over a 36-row page, not a true field
  distribution — noisy at the tails. Career stage is bounded by OpenAlex's
  ~10-year window. Author disambiguation errors still show through; the ORCID
  badge + "ORCID-verified" filter are the mitigation.
- **Cost:** one `/authors` call + one `/topics` expansion (cached 24 h) + one
  batched Wikidata call (cached 7 d) per view. No per-card fetch.
- The sparkline uses `--primary` (`--metric-influence` is only defined as the
  Tailwind `--color-metric-influence` token, not a raw CSS var).
