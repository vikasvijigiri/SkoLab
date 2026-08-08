---
name: error-recovery
description: Bounded diagnose-fix-reverify loop for failing checks (build, test, deploy, or API errors) — logs durable incident records into error.md. Use whenever asked to "debug tough error", "log error in error.md", or when encountering repeating complex failures. Do NOT use for a fresh, first-time failure with no incident history yet (`systematic-debugging` owns root-causing that); use this once a failure recurs or the diagnosis itself needs a durable record.
---

# Local Error Recovery & Incident Logging Skill

Diagnoses complex errors, executes bounded fix attempts, and maintains a durable, structured record of incidents in `error.md`.

## Workflow

1. **Inspect & Diagnose**: Analyze stack traces, error output, and affected source files. Identify the root cause without making superficial symptom patches.
2. **Apply & Verify**: Apply targeted code edits. Re-verify by running the exact failing test or command.
3. **Log Incident to `error.md`**: Append a structured entry to `error.md` in the root of the repository.

## Entry Format for `error.md`

Append to the end of `error.md`, separated by `---`:

```markdown
## YYYY-MM-DD HH:MM — <short symptom title>

- **Phase/Context:** <component, build step, or test suite>
- **Symptom:** <exact error message / stack trace summary>
- **Diagnosis:** <verified root cause>
- **Attempts:**
  - 1. <hypothesis / fix tried> → <outcome>
- **Fix:** <solution applied>
- **Status:** `Resolved` | `Escalated` | `Abandoned`

---
```

## Rules
- Never delete or overwrite past incident entries in `error.md`.
- Keep diagnoses evidence-based from actual log outputs.

## Routing

- Mandatory validator: the exact failing test or command from step 2 — a fix
  with no rerun of the original failure is not verified.
- Preceded by: `systematic-debugging`, if this is the first time the failure
  is being investigated rather than a recurrence.
- Terminal handoff: none. The `error.md` entry is the durable record; hand
  off to `knowledge-manager` only if the incident also warrants a
  `decisions/` entry.

## Success

The re-verification step passed on the exact failing check, and `error.md`
has a new entry with a real `Diagnosis` and `Fix` (not a placeholder), with
`Status` set truthfully.
