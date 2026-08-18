# HANDOFF

> Current-state snapshot, not history. Overwritten in place at the end of
> every session. For history, see `LOG.md`; for why a decision was made, see
> `decisions/`.

**Last updated:** 2026-08-18

## Where the repository is

`main` now carries everything that was sitting on
`fix/similar-authors-shape-hardening` — 11 commits, 208 files. Tag `v1.0.0`
marks that merge; it is the repository's first tag and it matches the version
`package.json` already declared.

What landed, oldest first:
- `2f14a0b` shape-check authorship data in `derive_similar_authors_from_works`
- `12bf1a9` supply the required `LeaderboardEntry.id` in quest fixtures
- `1a564e9` the UAIOS agent capability layer and its checks
- `c65653a` personalize the research surfaces around the requesting user
- `9e1d116` Go gateway tests, wired into CI, plus layer docs
- `c50455a` drop the project-native skills/hooks, fix two layer validators
- `c7aa5f9` timeouts and URL-scheme guards in ops scripts, ruff backlog cleared
- `1a728dc` next 16.3.0, closing 6 high-severity advisories
- `3be5e98` the repository recon map
- `f057dc5` the gateway fails auth closed in release when Firebase is absent
- `155fa83` that brief recorded in the task log

`fix/empty-connections-fallback` is zero commits ahead of `main`. It is fully
merged and can be deleted whenever its owner wants; nothing depends on it.

## Verification behind the merge

| Check | Result |
|---|---|
| `python tools/run_checks.py` | `PASS: 30 check(s) green (lint, test, typecheck)` |
| `go vet ./... && go test ./...` (`services/backend-go`) | every package `ok` |
| `pytest services/backend/tests/` | `85 passed, 6 errors` |

The six errors are one pre-existing defect, not a regression from this branch:
`tests/test_threat_modeling.py:34` calls `asyncio.get_event_loop()` in teardown,
which Python 3.14 refuses. CI pins 3.10 and is green. Logged in `ISSUES.md` and
still open — it becomes a real CI failure as soon as the pin moves past 3.12.

## What needs a decision

- **`stash@{0}` — layer-bootstrap regressions.** The global SessionStart
  bootstrap re-installed stock `ruff.toml`, `tools/test_ci_shape.py` and
  `tools/test_package.py` over the fixes in `c7aa5f9`/`c50455a`. Applying that
  stash re-breaks 3 checks. It is stashed rather than dropped so the diff stays
  inspectable; the durable repair is upstream, in the bootstrap's merge
  behaviour, not here. Drop the stash once that is settled.
- **`mcp.json` is untracked and inert.** Claude Code reads `.mcp.json`. As
  named, this file configures nothing. Rename it, commit it, or delete it —
  but it was not folded into the merge.
- **`.env` still lacks** `DATABASE_URL`, `GROQ_API`, `PORT`, `LAN_IP`,
  `PYTHON_BACKEND_URL`, `GIN_MODE`, all present in `.env.example`. These are
  the owner's own credentials; no agent should fill them in.
- **`docs/plans/` and `docs/archive/` do not exist.** The docs-structure rule
  says not to create speculative empty folders, so they were left absent. That
  drift warning will keep firing until either a real page lands in them or the
  rule is amended.
