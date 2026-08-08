# LOG

> Chronological, append-only. Newest entry at the top. Never rewrite an
> existing entry — if something turns out to be wrong, say so in a new one.
> For current status (not history), see `HANDOFF.md`.

---

## 2026-07-21 17:09 — Agent (Claude)

Implemented the MCP/hooks/skills set recommended earlier in the session:
`postgres` and `playwright` MCP servers (committed, `.mcp.json`), `grafana`
MCP server (local scope only — needs a live service-account token, unlike
Postgres's already-public dev password), a PreToolUse hook blocking raw
`gradlew` (the π-path Gradle crash from `AGENTS.md`), a PostToolUse hook
reminding about the `skolab_python_ai` docker-rebuild step, and two skills
(`backend-rebuild-verify`, `android-build`).

Verified every piece for real rather than trusting config: started
`./gradlew --version` and watched the hook block it; edited a
`services/backend` file and captured the reminder hook's stdin payload;
confirmed Postgres connectivity/credentials with a live `psql` query (20
tables); stood up the `infrastructure/` Prometheus+Alertmanager+Grafana+Loki
stack (user's explicit go-ahead, since that dir is normally off-limits) and
called the Grafana MCP's `query_prometheus` tool over raw JSON-RPC, getting
back real data (`up{job="skolab-backend"}=1`); called Playwright MCP's
`browser_navigate` the same way and got back page title "SkoLab" from the
real running dev server.

Hard blocker hit: newly-added `.mcp.json`/`.claude/skills` entries don't
attach to an already-running Claude Code session — `claude mcp list` still
shows all three servers "Pending approval" even after writing
`enabledMcpjsonServers` directly into `~/.claude.json`, and both `ToolSearch`
and `Skill` fail to find the new entries. The two hooks, by contrast, picked
up live with no restart (settings.json hooks apparently reload without a
restart; MCP/skill registration does not). Everything is proven correct one
layer below the Claude tool surface; the final loop — actually calling these
as native tools — needs a session restart to close out. See `HANDOFF.md` for
the full punch list.

---

## 2026-07-21 — Agent (Codex)

Added a portable repo-level agent contract for cross-tool workflow rules:
`docs/agent-contract.md` captures the shared reading order, portable
behavior rules, session-end expectations, and what remains tool-specific
(MCP servers, skills, plugins, global hooks). Linked it from `README.md`
and clarified the same boundary in `AGENTS.md` so Claude, Codex, and future
agents have a repo-visible source of truth instead of relying on client-only
settings.

---
## 2026-07-21 14:54 — Agent (Claude)

Two related pieces of work: grounding Journal Advisor in real data (planned
and executed first), then a much larger backend personalization/correctness
audit prompted by the user asking to check "literally every backend service
line by line" and make everything "revolve around the user."

**Journal Advisor**: root-caused the garbled-LaTeX bug the user screenshotted
back to the LLM being asked to write free-text "submission tips" for
completely invented journals — not a frontend rendering bug (confirmed via
sandboxed tests that `MathText.tsx`'s own repair/KaTeX pipeline handles bare,
mixed, and fully-escaped LaTeX correctly). Fixed at the root: real OpenAlex
journals via a new `search_sources()`, `type == "journal"` filter (an
unfiltered first pass surfaced "Open Science Framework" and a funding-agency
repository as "journals"), no size-based sort (an early version let *Science*/
*PLoS ONE* dominate every query regardless of topic — confirmed live a
physicist and an unrelated researcher got identical top-3 results before this
fix), and LLM used only for a short no-LaTeX rationale.

**Personalization audit**: a 3-pass Explore audit mapped every user-facing
recommendation path in the backend and scored each on how genuinely grounded
it is in the actual requesting researcher vs. generic/hallucinated. Found and
fixed: hardcoded fake grants (`match_grants`), Horizon predictions ignoring
who's asking entirely, a fabricated-coauthor fallback, a real cross-user chat-
history bug (`chat_with_author` keyed off a hardcoded shared literal user_id),
a conjecture prompt fed raw dict noise instead of a reconstructed abstract,
a collaborator-synergy feature inventing proposals from concept tags alone,
and the same `x_concepts`-is-empty bug (confirmed live: this field is
basically deprecated/empty on current OpenAlex author objects) independently
present in three different services. Also discovered a real, working
per-user "digital twin" foundation already exists (`user_memory_service`,
Postgres-backed activity tracking + LLM bio summary) but is wired up for
Android only — flagged and deliberately deferred building the web-side
equivalent, per the user's explicit call, rather than scope-creeping it in.

Extracted a shared `app/services/ai/user_context.py` module partway through
(needed for the Horizon fix, then reused for two more of the audit's fixes)
instead of writing a fourth slightly-different copy of "resolve this
researcher's concepts and recent-paper keywords" — this is exactly the kind
of drift that caused the `x_concepts` bug to exist in three places
independently.

Also persisted a previously-orphaned `skills`/`tools` LLM-derived field
(existed server-side, Android-only, never saved) to two new
`researcher_metrics` columns, and added a distinct "Research Areas & Skills"
section to the profile page — verified end-to-end with a real enrichment run
(real skills/tools computed and correctly served back through
`search_author`).

Found (not caused) something worth a permanent note: `PgBackedCache` stores
keys as `"{name}::{key}"`, not bare — cost real time this session hunting a
"stale cache" ghost that was actually just a wrong `DELETE` query never
matching the real row. Documented in `HANDOFF.md`'s gotchas.

Diff: uncommitted at time of writing (spans `services/backend` extensively,
smaller touches in `apps/web`).

---

## 2026-07-20 13:48 — Agent (Claude)

Ran two full-app audits (functional-correctness across every page/screen;
separately, a smooth/fast/efficient pass on animation consistency, data-
fetching, and bundle size) via parallel Explore agents, then merged both
into one 6-phase, 23-item plan and executed it phase by phase — the
largest single-session change set so far. Highlights:

- Root-caused and fixed a real production bug: `/api/v1/analyze_paper` 500s
  came from `LLMService` treating an empty OpenRouter `choices` response as
  a successful result, combined with a dead model first in the fallback
  list. Both paths now raise on empty content so the fallback loop actually
  works.
- Fixed a real perf bug, not just a UX one: home/author pages gated 5
  independent backend calls behind one `Promise.allSettled`, so the whole
  section waited on the single slowest call (measured up to 259s) even
  though most resolved in seconds. Split into independent per-section
  state.
- Added `score_candidates_against_profile()` to `embedding_service.py` and
  wired it into `journal_advisor`/`industry_opportunities` — both had been
  trusting the LLM's/scraper's self-reported match score with nothing
  grounding it against the researcher's actual profile. Verified live: a
  96%-match ML journal for a physicist dropped to a grounded 70-75%; a
  contradictory-explanation biomedical job now correctly ranks below
  genuinely on-topic postings instead of scoring the same as them.
- Found (via `main.go`) that the leaderboard's live route is the Go
  gateway's own SQL implementation, not the Python one both `router.py`
  and `main.go` register — the Python leaderboard code is effectively dead
  behind the public gateway. Fixed the `id`-field addition and the leaked
  `"David W. Test"` Postgres fixture row (deleted after explicit user
  approval — the auto-mode classifier correctly blocked the first,
  unapproved attempt) on the side that's actually live.
- Built `apps/web/src/lib/motion.ts` as the JS-side counterpart to
  `globals.css`'s motion tokens and delegated the ~35-site mechanical
  migration to a background agent while working Phase 4/5 items that
  didn't touch the same files in parallel — no merge conflicts, verified
  by re-running `tsc --noEmit` after both landed.
- Phase 5 bundle claims were verified against real `next build` output, not
  assumed: katex's chunk is confirmed absent from `rootMainFiles`; firebase
  tree-shaking confirmed via chunk contents (used APIs present, unused
  products' actual code absent, only stray string literals). Font
  `display: "swap"` turned out to already be Next 16.2.10's default —
  caught by checking the installed source before adding a no-op prop.

Diff: uncommitted at time of writing (large — spans `apps/web`,
`services/backend`, `services/backend-go`).

---

## 2026-07-19 21:20 — Agent (Claude)

Worked the two remaining `HANDOFF.md` items that were actually code-fixable
(the other two — CPU allocation, Firebase Web app registration — need a
human decision/console action, confirmed out of scope for this pass).

Retired the dormant `/api/v1/recommendations` unified endpoint: confirmed
via grep across both clients that neither ever called it, removed
`get_unified_recommendations` + its 3 builders from `service.py`, the
unified route from `router.py`, 6 now-fully-unused `engine.py` techniques
+ their schemas/tests — while keeping the live `/peers`/`/peers/invite`/
`/peers/check-registered` routes and the `cosine_similarity`/`mmr_diversify`
functions still imported by `pipeline_services.py`. Recorded as
`decisions/0007-retire-dormant-unified-recommendations.md`.

Documented `POST /api/v1/daily_feed/dismiss` in `api-contracts/openapi.yaml`
— the specific gap the new docs-sync hook caught. Left the broader
pre-existing gap (openapi.yaml only covers a handful of the real endpoint
surface) as a separate, larger-scope task per the plan's agreed boundary.

Diff: uncommitted at time of writing.

---

## 2026-07-17 14:45 — Agent (Claude)

Built the global docs-sync Stop hook (`~/.claude/hooks/check_docs_sync.py`,
5 new mtime-comparison rules: decisions/ index, dependency manifests, infra
files, env templates, API routes vs. `api-contracts/openapi.yaml` — all
gated on their reference file existing, so they no-op in other repos), plus
a "Keeping docs in sync" checklist section in `AGENTS.md` for the judgment
calls a hook can't make, and a "Project Documentation" pointer table in
`README.md`. Verified live: the hook's first real run caught 3 genuine
things, including a real pre-existing gap (the `/daily_feed/dismiss`
endpoint was never added to `openapi.yaml`).

Then worked the `HANDOFF.md` "unverified" list: re-verified the desktop-
native redesign rollout page-by-page against the original plan (confirmed
Phases 1-3 fully shipped) and found one real gap — `next.config.ts`'s
`experimental.viewTransition` flag was never added (verified against the
installed Next 16.2.10 docs before fixing, not assumed). Fixed it; Phase 4
(cross-page view-transition motion) itself was never started and remains a
deferred, optional-polish decision. The other three "unverified" items
(dormant `/api/v1/recommendations` endpoint, backend CPU allocation,
Firebase Web app registration) each need a decision or action from the repo
owner, not just more agent investigation — surfaced back to them rather
than picked unilaterally.

Diff: uncommitted at time of writing.

---

## 2026-07-17 00:52 — Agent (Claude)

Added the repo's core doc set: `PLAN.md` (retrospective founding plan),
`AGENTS.md` (agent cold-start guide) + `CLAUDE.md` (`@AGENTS.md` import,
matching the existing `apps/web/CLAUDE.md` convention), `HANDOFF.md`
(current-state snapshot), `decisions/` (6 ADRs covering the caching split,
the Go/Python service split, the self-hosted-embeddings call, the
Firebase-for-CoLab/Profile call, and two decisions from the most recent
recommendation-engine session), and this file. Requested by the repo owner
to formalize documentation practice going forward.

---

## 2026-07-14 ~11:00 IST — Agent (Claude) + human (Vikas)

Continued the recommendation-engine session below:
- Root-caused an embedding-latency bug: `sentence-transformers` pads every
  text in a batch to the length of the longest one, so full-length abstracts
  among ~150 candidates were inflating batches from single-digit seconds to
  80-180s. Fixed via candidate-text truncation (300 chars) and moving the
  CPU-bound `.encode()` call off the event loop (`asyncio.to_thread`).
  See `decisions/0003-self-hosted-embeddings.md`.
- Found and fixed the similar-researchers channel misusing OpenAlex's
  author-name search on topic phrases (returned nothing/garbage); rebuilt it
  to derive peers from authorships of already-matched papers instead.
  See `decisions/0005-similar-researchers-via-authorship.md`.
- Decoupled the displayed match % from the pool-relative ranking score,
  fixing an artificial "96% then a cliff to 80%/66%" pattern.
  See `decisions/0006-decoupled-ranking-vs-display-score.md`.
- Fixed a transient paper-detail-page 404 by adding retry affordance to
  `ErrorBanner` and wiring it into `paper/[id]/page.tsx` — the underlying
  route was confirmed working; there was previously no way to recover from
  a transient failure without manual navigation.

Diff: squashed into [`26d37e2`](https://github.com/NG-VikasV/ResQit/commit/26d37e2ab3458bb88a514570dbe6ff328ffc168a).

---

## 2026-07-13 ~13:00 IST — Agent (Claude) + human (Vikas)

Recommendation engine overhaul, prompted by a user complaint that daily-feed
recommendations for a condensed-matter-physics researcher were surfacing
unrelated papers (e.g. ML papers, then later a solar-cell/materials paper).
Traced through several distinct root causes across the session:
- Replaced naive bag-of-words similarity with self-hosted `bge-small`
  embeddings (`app/services/ai/embedding_service.py`).
  See `decisions/0003-self-hosted-embeddings.md`.
- Fixed a mean-centering bug causing every candidate to score ~97% regardless
  of true relevance (bge's raw cosine similarity has a high anisotropic
  floor); added pool-mean-centering to restore real discrimination.
- Found the `is_relevant` hard-gate was driven by a disconnected, overly
  generic keyword-substring matcher (`is_work_relevant_to_discipline`)
  instead of the actual embedding signal — this is what let an unrelated
  solar-cell paper through despite good embedding infrastructure already
  being in place. Switched the gate to use the embedding signal directly.
- Broadened candidate retrieval (more search terms, LLM-extracted
  recent-paper keywords now actually drive search instead of only feeding
  the embedding text) and added the similar-researchers candidate channel
  (later fixed on 07-14, see above).
- Added a per-author dismiss/feedback loop (`POST /api/v1/daily_feed/dismiss`)
  with frontend wiring in `PulseFeedCard`.

Diff: squashed into [`26d37e2`](https://github.com/NG-VikasV/ResQit/commit/26d37e2ab3458bb88a514570dbe6ff328ffc168a).

---

## 2026-06-30 17:37 — Human (Vikas)

Android: harmonized typography to Space Grotesk (Display/Syne), Inter (Body),
JetBrains Mono (Metrics); fixed `HomeTopWidget` fallback to global trending
papers when user memory is empty; `run-app.ps1` now resolves the ABI-split
APK dynamically.

Diff: [`a7a578b`](https://github.com/NG-VikasV/ResQit/commit/a7a578b), [`f74663e`](https://github.com/NG-VikasV/ResQit/commit/f74663e).

---

## 2026-06-30 16:05–16:16 — Human (Vikas)

Android APK size/perf pass: R8 resource shrinking, ABI splits, tighter
ProGuard rules, packaging exclusions; removed unused `appcompat`/legacy
Material dependencies (~1.2MB saved); added centralized `SkoLabIconSize` /
`SkoLabIconButtonSize` / `SkoLabRadius` / `SkoLabFontSize` design tokens.

Diff: [`9da7411`](https://github.com/NG-VikasV/ResQit/commit/9da7411)..[`1ff59c7`](https://github.com/NG-VikasV/ResQit/commit/1ff59c7).

---

## 2026-06-30 15:32–15:58 — Human (Vikas)

Android contacts/invite flow: native SMS/Email invite intents, backend
recommendation-log triggers for unregistered contacts, simulated academic/
Gmail contact sync, registered-contacts-first sorting, single toggle-state
add/remove button, `LazyColumn` perf (stable keys, memoized regex); migrated
check-registered logic to a high-speed Postgres endpoint.

Diff: [`b7fe563`](https://github.com/NG-VikasV/ResQit/commit/b7fe563)..[`c7ac19d`](https://github.com/NG-VikasV/ResQit/commit/c7ac19d).

