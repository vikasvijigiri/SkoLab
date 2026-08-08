---
name: log-task
description: Append an entry to task.md for the task just requested — date-time header, 6-line brief, and status, cleanly separated for readability. Use whenever the Stop hook (task-log-reminder.js) nudges that task.md is stale, or proactively at the end of any substantive user request, even discussion-only ones that changed no files. Do NOT use for HANDOFF.md, LOG.md, MEMORY.md, or `decisions/` entries — those belong to `knowledge-manager` and have independent criteria.
---

# Log a task

`task.md` (repo root) is an **append-only** record of every task requested in
this repo — one entry per task, never edited or deleted after the fact. It is
distinct from the other current-state docs:

- `HANDOFF.md` — overall repo state right now (overwritten in place).
- `LOG.md` — session-level history.
- `task.md` — one entry per *task* (a single session can span several).

## When to log

- Whenever the Stop hook fires the "task.md hasn't been updated" nudge.
- Proactively, without waiting for the nudge, at the natural end of any
  request the user made — including pure discussion/advice turns that
  touched no files. The nudge is a safety net, not the trigger to wait for.
- Skip only for genuine filler replies with no task content ("yes", "go
  ahead", "thanks") — the Stop hook already skips nudging on these via a
  regex allowlist; use the same judgment if logging proactively.

## Entry format

Append to the end of `task.md`, separated by a `---` rule, in this shape:

```markdown
## YYYY-MM-DD HH:MM — <short task title, a few words>

**Asked:** <the user's request, trimmed to one line if long>

1. <what was actually done or decided — one line>
2. <why, if there's a non-obvious reason>
3. <key file(s) touched or created>
4. <any decision/tradeoff made along the way>
5. <anything explicitly deferred, blocked, or left for the user to confirm>
6. <verification performed, or "not verified" if none>

**Status:** Done | In progress | Blocked — <short reason>

---
```

Keep each of the 6 lines to one line each — brief, not a paragraph. If a line
doesn't apply (e.g. no non-obvious "why"), write a short one anyway rather
than skipping the number, so entries stay visually consistent and scannable.

Get the real current date-time (don't guess it) the same way LOG.md entries
do.

## Rules

- Never edit or remove a past entry. If a task's status changes later (e.g.
  "In progress" becomes "Done", or a follow-up request changes scope), append
  a new entry that references the earlier one rather than rewriting it.
- One entry per task, not per tool call or per message — if a task spans
  several back-and-forth turns, log it once when it concludes.
- This file is orthogonal to significance judgments elsewhere (e.g. whether
  something needs a `decisions/` entry) — log the task here regardless of
  whether it also warrants a decision file, HANDOFF.md update, or LOG.md
  entry; those are independent docs with independent criteria.

## Routing

- Mandatory validator: none — this is a doc-append, checked by re-reading
  the new entry against the required 6-line shape.
- Terminal handoff: none. Independent of `knowledge-manager`'s docs; run
  both if the task also warrants a HANDOFF/LOG/decision update.

## Success

A new `task.md` entry exists with a real date-time, all 6 brief lines
present (even if short), and a truthful `Status`, with no past entry edited
or removed.
