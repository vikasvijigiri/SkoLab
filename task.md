# Task Log

> Append-only log of tasks requested in this repo, one entry per task. Never
> edit or delete a past entry — if a task's status changes, append a new
> entry that references the earlier one instead of rewriting it. For overall
> current repo state see `HANDOFF.md`; for session-level history see
> `LOG.md`. See `.claude/skills/log-task/SKILL.md` for the entry format and
> `.claude/hooks/task-log-reminder.js` for the Stop-hook nudge that guards
> against forgetting to log a task.

---

## 2026-07-21 17:20 — UI/UX tooling recommendation

**Asked:** "Any more skills, hooks, MCPs needed for this project? ... world class UI and UX ... like google stitch?"

1. Recommended the Playwright MCP (already configured) as the highest-leverage existing tool for UI/UX verification.
2. Explained Google Stitch isn't an MCP/connector — it's a separate design-mockup tool, not something that plugs into a session.
3. Proposed a Figma Dev Mode MCP server (for design-spec pull) and a Playwright-based visual QA skill as the two concrete additions.
4. No files changed in this turn — discussion/recommendation only.
5. Deferred building either until the user confirmed they wanted them.
6. Not verified (nothing implemented yet).

**Status:** Done — recommendation given, user approved next steps in the following task.

---

## 2026-07-21 17:24 — Add Figma MCP + visual-qa-review skill

**Asked:** "Add Figma as well and add visual QA skill as well"

1. Added a `figma` entry to `.mcp.json` pointing at Figma's local Dev Mode MCP Server (`http://127.0.0.1:3845/mcp`), no API key needed.
2. Chose the local Dev Mode server over the remote/OAuth Figma MCP so no new secret has to be committed or typed in.
3. Wrote `.claude/skills/visual-qa-review/SKILL.md`: drives the real web app via the existing `playwright` MCP across mobile/tablet/desktop breakpoints, checks console errors and the a11y snapshot, and flags repo-specific gotchas (raw LaTeX/HTML bypassing `MathText`, design-token drift).
4. Confirmed `docs/agent-contract.md` explicitly excludes MCP/skills from its scope, so it didn't need an update.
5. Left both unverified inside a live session — same restart caveat as the prior session's MCP/skill additions (new `.mcp.json`/`.claude/skills` entries only attach at session start).
6. Not verified live yet; flagged to the user that a fresh session is needed to confirm both actually attach as callable tools.

**Status:** Done (config/files written) — live verification still pending a session restart.

---

## 2026-07-21 17:32 — Maintain a task.md log via hook/skill

**Asked:** "maintain a task.md file as well, where each task i ask you, you should log it to the file (append) task.md with date-time and details the 6-brief lines and along with it the status of it ... you can use hooks/skill for this"

1. Created root `task.md` as an append-only per-task log, distinct from `HANDOFF.md` (state) and `LOG.md` (session history).
2. Added `.claude/hooks/mark-prompt-time.js` (UserPromptSubmit) to stamp a sentinel file with each prompt's time + text, since hooks can't summarize but can reliably detect "was task.md touched this turn."
3. Added `.claude/hooks/task-log-reminder.js` (Stop) that nudges via `systemMessage` if `task.md`'s mtime is older than the sentinel, skipping obvious filler replies via regex.
4. Wrote `.claude/skills/log-task/SKILL.md` defining the exact entry format (date-time header, 6 numbered brief lines, status, `---` separators) so entries stay consistent.
5. Registered both hooks in `.claude/settings.json`; added `.claude/.task-log-sentinel` to `.gitignore` as ephemeral machine state (same treatment as `settings.local.json`).
6. Not yet live-verified inside a running session (same restart caveat as other recent hook/skill additions) — will confirm the nudge fires correctly next session.

**Status:** Done (config/files written) — live verification pending next session's Stop-hook firing.

---

## 2026-07-21 17:40 — Drop Figma MCP, keep Playwright only

**Asked:** "cant we go with playwright?" then "okay, forget and leave figma for now"

1. Discussed tradeoff: Figma MCP only pays off if there's an actual Figma file acting as SkoLab's design source of truth; Playwright alone covers implementation-verification (screenshots, console, a11y).
2. Confirmed no Figma file/workflow currently exists in the repo — tokens live in `shared/skolab-design-system` instead.
3. Removed the `figma` entry from `.mcp.json`, leaving `playwright` and `postgres` as the only configured MCP servers.
4. No other files needed changes — `visual-qa-review` skill and Playwright MCP config are untouched and still stand on their own.
5. Left the door open: if a real Figma design source shows up later, the same local Dev Mode MCP config can be re-added.
6. Not separately verified beyond re-reading `.mcp.json` after the edit — no live session check needed for a removal.

**Status:** Done.

---

## 2026-07-21 17:48 — Add backend-test-suite skill

**Asked:** "any more skills or MCPs we need for backend and other testing and making them world class?" → "yes"

1. Surveyed existing backend test infra first: `verify.yml` (ruff/bandit/pip-audit gating) + `ci.yml` (pytest tests/) + `tests/load/` k6 scripts + zero tests for `services/backend-go`.
2. Recommended (not yet built) a k6 skill and flagged Go-gateway test coverage as a real gap, separate from tooling; built the highest-value one first: `backend-test-suite`.
3. Wrote `.claude/skills/backend-test-suite/SKILL.md` mirroring verify.yml + ci.yml's pytest step exactly, including the service-account.json dummy-file guard (never overwrite a real one).
4. Actually ran every step locally to verify (not just wrote it): `ruff check`, `ruff format --check`, `bandit -r`, `pytest` all executed successfully against the real repo.
5. Found bare `ruff`/`bandit`/`pip-audit` weren't on PATH in this environment's bash — fixed the skill to use `python -m <tool>` instead, verified that resolves correctly.
6. Verified: pre-existing (unrelated) lint/format findings surfaced correctly (3 ruff errors, 12 files needing reformat, bandit clean, 1 pytest file passed) — confirms the skill actually catches real signal, not just "no errors" by accident.

**Status:** Done — skill built and live-verified end to end this session (no restart needed since I ran the commands directly, only the `Skill`-tool invocation path needs next-session confirmation).

---

## 2026-07-21 17:52 — k6 load-test skill + Go gateway test coverage

**Asked:** "want" (clarified via follow-up question → "Both": k6 skill, then Go gateway tests)

1. Wrote `.claude/skills/k6-load-test/SKILL.md` wrapping `tests/load/*.js`, with an explicit safety rule: never point `ramp_up`/`spike`/`soak` at a staging/shared target without confirmation, since they can trip the real `P95EndpointLatencyHigh` Alertmanager rule or page someone.
2. Could not fully live-verify the k6 skill — `k6` isn't installed on this machine; flagged that installing it is the user's call, didn't install unilaterally.
3. For `services/backend-go` (previously zero tests): added `internal/circuitbreaker/breaker_test.go` (6 cases: closed/open/half-open transitions, reset timeout, single-probe limit), `internal/middleware/cors_test.go` (4 cases), `internal/middleware/ratelimit_test.go` (5 cases incl. per-IP isolation and X-Forwarded-For), and `main_test.go` (3 cases locking in the documented CORS-header-stripping fix in `reverseProxy`).
4. Hit and fixed a real test-infra gotcha: `httptest.ResponseRecorder` isn't an `http.CloseNotifier`, which panics inside gin when proxying — switched `main_test.go` to a real `httptest.Server` instead of a bare recorder.
5. Ran the full suite live: all 15 new tests pass, `go build` and `go vet ./...` both clean.
6. Wired `go vet`/`go test ./...` into `.github/workflows/ci.yml` (previously only `go build` ran), and added a "Go gateway go/no-go check" bullet to `AGENTS.md` alongside the existing frontend `tsc --noEmit` one, noting `auth`/`author`/`db`/`quest`/`user`/`websocket` still have no tests.

**Status:** Done for Go gateway tests (built, live-verified, wired into CI and docs). k6 skill done but unverified live pending k6 install — ask before installing.

---

## 2026-07-22 01:15 — Fix daily-conjecture card mixing multiple papers' concepts

**Asked:** Screenshot of a `DailyChallengeCard` showing a physics question that incoherently blended "deconfined quantum criticality," "Berry phase interference," and "genuine multipartite entanglement" into one fabricated formula — "this has got mixed one paper info to another."

1. Traced the card to `GET /api/v1/daily_conjecture` in `services/backend/app/api/v1/endpoints/feed.py`, which feeds an author's 3 most recent papers' abstracts to `llama-3.3-70b-versatile` and asks it to write one "conjecture/puzzle" — nothing stopped it from blending concepts across all 3 unrelated papers into one scenario.
2. Root cause: the system prompt said "grounded in the research areas of the provided publications" (plural, vague) with no instruction to stay within a single paper.
3. Rewrote the system prompt to require picking exactly ONE numbered paper and explicitly forbid blending terminology/formalism across papers or fabricating formulas inconsistent with that one paper's field; numbered `works_context` entries ("Paper 1/2/3") to match.
4. No schema change (`ConjectureResponse` untouched) — this was a prompt-only fix, kept minimal.
5. Rebuilt `skolab_python_ai` (docker compose build + up -d --no-deps web, per `backend-rebuild-verify` skill) since the container was running the static pre-fix image.
6. Verified live: `curl` against `/api/v1/daily_conjecture?name=T. Senthil` (a real deconfined-quantum-criticality researcher, chosen to stress-test the same domain as the bug report) returned a single coherent scenario (Anomalous Hall crystal elasticity) with no cross-concept mixing.

**Status:** Done — fixed and live-verified against a fresh (uncached) author.

---

## 2026-07-22 01:25 — Same bad conjecture still showing on /home (stale cache follow-up)

**Asked:** User re-shared the exact same broken "Deconfined Quantum Criticality" card, still live on `localhost:3000/home` for their own signed-in profile, after the prompt fix above.

1. Root cause: this is exactly the cache-invalidation gotcha documented in AGENTS.md — `daily_conjecture_cache` is `PgBackedCache` with a 24h TTL, and the user's own profile (OpenAlex author id `A5020214245`, resolved from their signed-in Firestore profile via `apps/web/.../home/page.tsx`) had the bad pre-fix conjecture cached at 2026-07-21 11:18, valid until 2026-07-22 11:18 — my container rebuild didn't touch Postgres data, so the stale row kept being served regardless of the code fix.
2. Queried `cache_entries` via the read-only `postgres` MCP server to confirm and locate the exact stale row: `daily_conjecture::conjecture_A5020214245`.
3. Deleted that one row directly via `docker exec skolab_postgres psql` (the MCP server is read-only by design, can't write) — targeted delete, not a cache flush, so no other cached data was touched.
4. Verified live: re-curled `/api/v1/daily_conjecture?author_id=A5020214245` — freshly regenerated conjecture ("Deconfined Pseudocriticality in Quantum Antiferromagnets") is now a single coherent Néel–VBS transition scenario, no cross-paper mixing.
5. Did not touch the Firestore cache layer — confirmed by re-reading the endpoint code that `daily_conjecture` only uses `daily_conjecture_cache` (Postgres), unlike the dual-layer `daily_feed` recommendation path AGENTS.md warns about.
6. User should refresh `/home` now to see the corrected card; if any other already-viewed profile also shows stale content, the same targeted-delete approach applies per author id.

**Status:** Done — stale row cleared and fresh regeneration verified live for the reporting user's actual profile.

---

## 2026-07-22 02:15 — Fix Citation Trend mislabeled years + Similar Researchers unrelated recommendations

**Asked:** Two screenshots of the author profile page: Citation Trend chart showing bars labeled "18/21/26" instead of years, and Similar Researchers recommending unrelated particle/astro physicists (Zvi Bern, Tao Han, C. Bravo, etc.) for a condensed-matter-physics profile. Asked for root causes and a step-by-step plan (started in Plan Mode, exited mid-investigation).

1. Ran 2 parallel Explore agents (read-only) to trace both bugs before proposing anything: Citation Trend → `apps/web/src/components/author/CitationBarChart.tsx:23` (`{String(year).slice(2)}` truncates real years to 2 digits, no century marker); Similar Researchers → `services/backend/app/api/v1/endpoints/authors.py`'s `fetch_similar_authors()` passing a topic string into `openalex_service.search_authors()` (OpenAlex's `/authors?search=` display-name endpoint) — the exact anti-pattern `decisions/0005-similar-researchers-via-authorship.md` already fixed once, but only for the daily-feed channel, never for this profile-page panel.
2. Asked the user 2 scope questions (label-only vs. also fix bar spacing; profile-page only vs. also fix the identical bug in the Roadmap peer-coauthors feature) — got "label fix only" + "fix both."
3. Fixed `CitationBarChart.tsx` to show the full 4-digit year instead of truncating.
4. Added a shared `derive_similar_authors_from_works()` helper in `openalex_service.py` (per decision 0005: derive candidate author IDs from the authorships of works that already matched a topic search, never from an author-name search) and rewired all 3 call sites in `authors.py`'s `fetch_similar_authors`, refactored the daily-feed's `_find_similar_researchers` in `pipeline_services.py` to delegate to the same shared helper (no behavior change, same limit=4, discipline filter omitted so existing daily-feed behavior is preserved), and fixed the identical bug in `feed.py`'s Roadmap peer-coauthors search.
5. First live-verified result still showed OK-but-broad matches (any "Physics and Astronomy" author, including astronomers) — tightened all 3 profile-page/Roadmap call sites to prefer the specific `expertise[0]` topic (e.g. "Advanced Condensed Matter Physics") over the broad top-level `field_of_study`.
6. Second live-verified result still leaked 2 Immunology-field authors — root cause: reusing the existing `is_work_relevant_to_discipline` helper (designed for namesake disambiguation) scans the *entire abstract text* for broad keyword stems ("energy", "particle", "matter"), so an unrelated paper mentioning any of those words anywhere still passes. Added a stricter `_work_topic_matches()` that only checks a work's own *structured* topic/concept/field classification, and used that instead.
7. Hit a real caching gotcha mid-verification: `profile_cache` (Postgres, keyed `profile::id:{author_id}`) kept serving a stale prior-attempt's result across container rebuilds (rebuild only clears in-memory L1, not Postgres L2) — had to delete the specific cached row after each fix iteration to get a genuinely fresh recompute, exactly like the earlier daily_conjecture cache issue.
8. Final live verification, fresh recompute, reporting user's real profile (`A5020214245`): Similar Researchers now returns 5 genuinely on-topic physics/materials-science researchers (no unrelated fields); Roadmap peer-coauthors returns the same 3 people with 95%/91%/87% match. Daily feed smoke-tested afterward (200 OK, 3 items) to confirm the `_find_similar_researchers` refactor didn't regress it. `tsc --noEmit` clean for the frontend change; `ruff check` clean for all backend files touched.
9. Noted but did not act on: `pytest` in this dev environment has 27 unrelated pre-existing failures because `pytest-asyncio` isn't installed/declared anywhere in the repo (not in `requirements.txt`, not in CI's lint-tool install step) — a real gap, but unrelated to this fix and no existing test covers the functions touched here either way.

**Status:** Done — both root causes fixed and live-verified against the reporting user's real profile, including two precision follow-ups (expertise-vs-field priority, structured-vs-freetext topic matching) discovered only through live verification, not visible from reading the diff alone.

---

## 2026-07-29 17:28 — Repository Quality Sweep (No-Slop Check & Code Cleanliness)

**Asked:** "can you check no slops in the current repo" and "fix issues"

1. Ran global `no-slop-check` skill across repo source files to identify dead code, unused imports, and raw logging statements.
2. Replaced raw `print(...)` calls in `user_memory_service.py` and `pipeline_services.py` with standard Python logger calls (`logger.warning`).
3. Removed unused imports (`fastapi.Depends`, `fastapi.HTTPException`, `is_work_relevant_to_discipline`) flagged by `ruff check`.
4. Verified `layout.tsx` inline theme script is a justified exception for SSR hydration anti-flash.
5. Ran `python -m ruff check app` (passed clean) and `npx tsc --noEmit` in `apps/web` (passed clean with 0 errors).
6. Backend test suite (`pytest`) launched and running in background.

**Status:** Done — slop items cleaned, linting and TypeScript checks verified clean.

---

## 2026-07-29 17:30 — Add full-repo-test-suite Skill

**Asked:** "add these tests as a skill too in the local ./claude folder"

1. Created `.claude/skills/full-repo-test-suite/SKILL.md` in the repository.
2. Encapsulated tests for all 3 stacks: Next.js (`npx tsc --noEmit`), Go Gateway (`go test ./...`), and Python Backend (`ruff check`, `pytest`).
3. Documented reporting and verification guidelines.

**Status:** Done — skill file created in `.claude/skills/full-repo-test-suite/SKILL.md`.

---

## 2026-07-29 17:33 — Create error.md and local error-recovery Skill

**Asked:** "have a tough error log in error.md this should be updated by appropriate skill either in global claude or local, look for it and add"

1. Created root `error.md` to log structured incident records for complex runtime or test errors.
2. Created local skill `.claude/skills/error-recovery/SKILL.md` tailoring the global `error-recovery` workflow to log incidents into `error.md`.
3. Logged the `pytest-asyncio` missing plugin resolution entry into `error.md`.

**Status:** Done — `error.md` created with initial entry and local `error-recovery` skill configured.

---

## 2026-07-29 21:05 — Create & Register All Custom Skills, Hooks, and MCP Servers

**Asked:** "create all" and "make sure they are refernced in respective .md file so that they will be triggered with utmost probability"

1. Updated `.mcp.json` to register the `@modelcontextprotocol/server-fetch` MCP server.
2. Created 3 new local skills in `.claude/skills/`: `openapi-contract-sync`, `design-token-compile`, and `cache-purge-verify`.
3. Created 2 new automated hook scripts in `.claude/hooks/`: `openapi-drift-reminder.js` and `web-tsc-nudge.js`, and registered them in `.claude/settings.json`.
4. Documented all local skills, hooks, and MCP servers in `AGENTS.md` and `docs/agent-contract.md` to maximize triggering probability across sessions.

**Status:** Done — all skills, hooks, MCP servers, and doc references created and registered.





---

## 2026-08-09 — Fail the Go gateway's Firebase auth bypass closed in release

**Asked:** "the Go `dev_user` auth bypass ([firebase.go:47]) is the one I'd take
first — and I'll scope it" / "okay go ahead with it"

- **Goal**: Make `auth.VerifyUser` fail closed when Firebase credentials are
  absent and the gateway is running in release mode, so a misconfiguration
  cannot silently serve every protected route as `dev_user`.
- **Input**:
  - `services/backend-go/internal/auth/firebase.go:20-33` (`InitFirebase`,
    leaves `authClient` nil on error) and `:47-51` (the bypass).
  - `services/backend-go/main.go:30` — the existing `GIN_MODE == "release"`
    gate to follow; `:71,:79,:101` — the three guarded route groups.
  - `services/backend-go/internal/user/user.go:105` — reads `user_id`.
  - `.env.example:16` — `GIN_MODE=release`.
- **Output**: `firebase.go` refuses the request instead of setting `dev_user`
  when `authClient == nil` and release mode is set; dev/CI behaviour unchanged.
  Plus `internal/auth/firebase_test.go` — the package has no test file today.
- **Constraints**: Reuse `GIN_MODE`; do not introduce a second environment
  variable (inferred — no stated preference, but `main.go:30` sets the
  precedent). Do not alter the Authorization-header check or the
  `VerifyIDToken` path. No new dependencies.
- **Done Checks**:
  - `cd services/backend-go && go vet ./... && go test ./...` exits 0.
  - New test: `authClient == nil` + `GIN_MODE=release` + header
    `Authorization: Bearer anything` ⇒ response is 401 or 503 and `user_id` is
    never set to `dev_user`.
  - New test: `authClient == nil` + `GIN_MODE` unset ⇒ still sets `dev_user`
    (dev/CI path preserved).
- **Out of Scope**: The Python backend's own auth. The other untested Go
  packages (`author`, `quest`, `db`, `websocket`, `user`). The route groups in
  `main.go`. Provisioning or rotating any Firebase credential.

**Status:** Done — release now returns 503 instead of dev_user; 4 tests added to internal/auth (was 0). Mutation-tested: the release test fails (200, want 503) against the pre-fix code.
