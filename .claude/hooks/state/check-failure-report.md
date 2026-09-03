# Check failure — attempt 1 of 3

Class `unknown`, remedy `repair`.
Signature `eba531019f84` (digits normalised, so a partial fix does
not reset the attempt budget).

No rule matched this output. Treat it as a real defect, and once the cause is known add a row to `_hooklib.FAILURE_CLASSES` so the next occurrence is classified.

## What failed

```
typecheck `"C:\Users\Vikas Vijigiri\AppData\Local\Programs\Python\Python314\python.exe" -m mypy`: Found 5 errors in 3 files (checked 64 source files)
```

## Uncommitted at the time

- .claude/hooks/state/call-fingerprints.json
- .claude/hooks/state/chain-ledger.jsonl
- .claude/hooks/state/last-entry-shape.json
- .claude/hooks/state/read-cost.json
- .claude/hooks/state/skill-cost.json
- .claude/hooks/state/telemetry.jsonl
- .claude/hooks/state/tool-cost.json
- .claude/hooks/state/turn-timer.json

## Next

This file is scratch, overwritten each failure, and is not a
knowledge doc. Root-cause the failure before changing anything.
