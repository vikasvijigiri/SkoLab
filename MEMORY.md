# Project Memory

<!-- Engineering-relevant facts and context worth persisting across sessions for
this repo. Distinct from Claude Code's own auto-memory index under
~/.claude/projects/<slug>/memory/ -- this file is checked into the repo. -->

- The daily-feed candidate pool (`pipeline_services.get_daily_feed`) is
  **heterogeneous by design**: arXiv results are hand-built into OpenAlex-*shaped*
  dicts (`_fetch_arxiv_candidates`) and mixed in one list with real OpenAlex
  `search_works` / `fetch_related_works` output. The arXiv shape is only a partial
  imitation — its `authorships[]` entries carry an `author.display_name` and nothing
  else: no `author.id`, no `institutions`. Any helper consuming that pool must
  shape-check rather than assume the OpenAlex schema, and must degrade per-entry
  instead of aborting, since it sits behind endpoints whose outer `except Exception`
  turns any escape into a 500. See ISSUES.md 2026-08-08.

- `QuestsService.get_leaderboard`'s `ValueError: No leaderboard data available ...` is a
  **terminal fallthrough, not a diagnosis**: both the Firestore and PostgreSQL branches
  swallow their real exception into a `print` and fall through to it, so the message
  names neither cause. Re-run the failing test with `pytest -s` to recover the actual
  error before theorising about missing data. See ISSUES.md 2026-08-08.
