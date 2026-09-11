# HANDOFF

> Current-state snapshot, not history. Overwritten in place at the end of
> every session. For history, see `LOG.md`; for why a decision was made, see
> `decisions/`.

**Last updated:** 2026-09-11

## Where the repository is

`main` is at `940df54` (`docs(deploy): remove stale local infrastructure
references`). Working tree has an **unmerged, unbranched** set of changes on
top of that — see `git status` — from a session that closed two gaps a
market-research pass surfaced: no version-controlled Firestore access
control, and no path from a Discovery match to an actual CoLab project. Full
detail in `LOG.md`'s 2026-09-11 entry; decision `decisions/0018`.

- **New:** `firestore.rules`, `firebase.json`, `.firebaserc` (repo root),
  `tests/firestore/rules.test.ts` + `tests/firestore/vitest.config.mts`,
  `apps/web/src/lib/firebase/workspace.test.ts` (workspace.ts had zero tests
  before this).
- **Changed:** `apps/web/src/lib/firebase/workspace.ts` (role-array
  derivation), `apps/web/src/lib/types.ts` (`editorUids`/`commenterUids` on
  `CollabProject`), `apps/web/src/components/discovery/ResearcherCard.tsx`
  (+ "Start a project" action), `apps/web/src/app/(app)/workspace/page.tsx`
  (reads `withResearcher`/`withResearcherName`, invites or logs "no account
  yet"), `apps/web/src/components/workspace/ShareModal.tsx` (call-site fix
  for `inviteMember`'s new signature), plus matching test updates and one
  shared-test-infra fix (`apps/web/src/test/firestore.ts` was missing a
  `setDoc` mock).
- README's stale Firebase/REST-backend framing corrected in place.

## What needs a decision / attention

- **Two things this session could not do, both flagged clearly rather than
  worked around:**
  1. **`firestore.rules` is not deployed.** Needs `npx firebase login`
     (owner's own Google account — no CLI credentials were available) then
     `npx firebase deploy --only firestore:rules`, or paste the file into
     Firebase Console → Firestore Database → Rules. Production is still
     running on whatever rules existed before this change until then.
  2. **`npm run test:rules` has not passed anywhere.** It needs Java (the
     Firestore emulator requires a JVM) and this environment had none —
     confirmed the failure is specifically `Could not spawn "java -version"`,
     not a rules-syntax or test-logic error, but the suite is unverified.
- **Everything else was verified live**, not just in unit tests: two real
  test accounts were created against the actual `skolab-vvi` Firebase
  project (`ada.verify.skolab@mailinator.com`,
  `marie.verify.skolab@mailinator.com`) and walked through sign-up,
  onboarding, Discovery, "Start a project" end-to-end, chat, tasks, and a
  member-role change that was confirmed to persist across a page reload.
  Both accounts and the resulting "Rod Ellis collaboration" project are
  still in production `skolab-vvi` — left for the owner to inspect or
  delete, not cleaned up automatically.
- **The Go gateway could not run in this environment** — no Go toolchain on
  this machine (same class of limitation `decisions/0009` already recorded
  for a prior session). Every gateway-dependent surface (activity feed, peer
  suggestions, author search-by-name, profile sync) was confirmed to degrade
  to its documented empty/error state rather than crash — not itself
  verified working, since nothing here could start it.
- **Found, not fixed:** a pre-existing hydration mismatch in `ThemeToggle`
  on the landing page (server renders a different icon than the client) —
  noticed during live verification, unrelated to this session's work.
- **LLM-grounding follow-up, narrower than first suspected:** a static
  read-only audit of Gap Finder / Horizon / Nexus (the three LLM features
  the 2026-07-21 audit hadn't covered) found all three genuinely grounded in
  real OpenAlex/client-supplied data — no bare-name fabrication path. The
  actual gap is narrower: **none of the three validates LLM output
  structurally** before returning it to the user (Gap Finder and Nexus trust
  raw text outright; Horizon only confirms `json.loads` succeeds, and its
  `extra="allow"` / default-filled schema would silently blank-out malformed
  fields rather than error). Not fixed this session — Python/LLM-service
  work, out of scope for what was otherwise a web-only change, and
  unverifiable here without a `GROQ_API` key.
- Branch is uncommitted and unpushed. Nothing merged to `main`.
