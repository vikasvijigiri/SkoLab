---
name: refactoring
description: Sweep a codebase for accumulated slop, then repair what is approved. Finds dead code, unused files, placeholders and TODOs, stale counts, dangling references, duplicated guidance, hedging, and claims nothing backs - reading standing artefacts including files the change never touched. Triggers include "clean up this repo", "tidy this up", "refactoring check", "is this clean enough to ship", "audit this before we merge", "remove the dead code", "anything unused", or "tech debt". Do NOT use to review a single diff before it lands - that is code-review. Use this proactively before shipping, even if the user does not ask for a sweep.
effort: high
model: sonnet
disable-model-invocation: false
allowed-tools: Read Grep Glob Bash
---

# Refactoring

Sweep for slop, then repair what the user approves. Runs after work is verified
and before it is reviewed — not instead of either. Running after review would
ship the repairs unreviewed; running before verification sweeps a tree that is
about to change.

**This is not a diff review, and `code-review` is.** Both can read the same
files, so the division is stated on both sides:

| | Reads | Answers |
|---|---|---|
| this skill | standing artefacts, **including files the change never touched** | has slop accumulated here |
| `code-review` | the diff, and under `small` its direct callers | is this change correct and safe to ship |

"Including files the change never touched" is the load-bearing half: slop
accumulates across sessions and every turn that produced it was fine at the
time, so a sweep restricted to the diff cannot see what it exists to find.

**`capability-layer-maintenance` is the other neighbour**, divided by question
rather than directory: sweeping `.claude/` is this skill's job, deciding what
its contracts should be is that skill's. A dead reference is **reported here and
repaired there**. `tools/test_process_router.py` fails if either neighbour stops
naming the other.

Cap visible output at ~500 tokens. Findings with `file:line`, not a tour.

Classify every finding `P0` release-blocking, `P1` high-risk, or `P2` cleanup,
with the evidence and the smallest safe repair. Clean means the automated checks
passed and the judgement pass found nothing — not that an unrendered or untested
surface is correct.

## Scope is one dimension, set per run

Scope is however much of the tree this run is answerable for, and it is stated
in the report so the reader knows what was covered. Typical triggers:

- **Before shipping, merging or handing off** — the default; covers the unit
  plus anything it touches.
- **After adding or removing a component** — where overlap, duplication and dead
  references appear, and no single edit reveals them.
- **On a cadence or change-volume threshold you set** — slop is invisible
  turn-by-turn and shows up at volume.

If the request doesn't specify, ask, or default to the smallest scope that would
catch what prompted the request — and say which you picked.

At a scope wide enough that Phase 1 would take many serial passes, `ultracode`
(or "use a workflow to sweep `<scope>`") parallelizes it across Claude Code's
native dynamic-workflow subagents — worth suggesting to the user rather than
sweeping serially, when the scope is that wide.

## Two phases, and the gate between them is hard

<HARD-GATE>
Phase 1 REPORTS in full. Phase 2 repairs **the local group only**, and only
after the user has seen every finding.

Fixing mid-sweep makes the report describe a tree that no longer exists, so the
reader cannot check a single claim against what is there.

**Structural findings are never applied here.** Merging or splitting a
component, renaming a public interface, deleting a document, re-cutting how work
is routed — those change what exists or what fires, and an unreviewed change is
what this skill exists to prevent. Report them and hand them to planning as
their own piece of work.
</HARD-GATE>

## Phase 1 — sweep

**Run the project's automated checks first** and quote the last relevant line.
**Take the scoped tier** (`python tools/run_checks.py --scoped`): this stage runs
mid-chain and the full tier belongs to the last verification before delivery.
Phase 2's re-run is scoped too. Those checks own credentials, conflict markers,
placeholders, empty files, dead imports — anything objectively decidable.
Re-checking them by eye is a second, weaker answer to a settled question.

If a category below has no automated check in this project, use the detection
tooling map to name one, then log the gap as a finding (`P2`, `P1` if it has
bitten this project before) — the absence is information, and a named tool
turns the gap into an actionable fix rather than a permanent manual chore:

| Category | Signal it misses without automation | Representative tool |
|---|---|---|
| Unused exports / files (JS, TS) | Dead code sits and compiles clean | `knip`, `ts-prune` |
| Unused dependencies (JS, TS) | `package.json` grows, nothing flags it | `depcheck`, `knip` |
| Unused code / imports (Python) | Same failure, different runtime | `vulture`, `deptry`, `ruff` |
| Unused code (Go) | Exported-but-uncalled survives `go build` | `staticcheck`, `deadcode` |
| Unused dependencies (Rust) | `Cargo.toml` grows silently | `cargo-udeps` |
| Circular / layering violations | Import graph tangles without a single bad diff | `madge` (JS/TS), language-native `depgraph` equivalents |
| Copy-pasted blocks | Duplication spreads across files, not just within one | `jscpd` (polyglot) |
| Cyclomatic complexity | A function grows unreadable in small increments | `radon` (Python), `lizard` (polyglot) |
| Dead links in docs | A moved or deleted file leaves a silent 404 | `lychee`, `markdown-link-check` |
| Lockfile drift from manifest | `package.json`/`Cargo.toml`/etc. changes, lockfile doesn't, install silently resolves something else | `npm ci`, `pnpm install --frozen-lockfile`, `yarn install --immutable` — each fails hard on desync instead of quietly rewriting the lockfile |
| Build artifacts / generated output tracked in git | `dist/`, `.next/`, `__pycache__/`, `*.pyc` committed once and never cleaned, diverging from every fresh build | `git ls-files` diffed against `.gitignore` coverage — anything generated that's tracked is a finding |
| Empty or orphaned directories | A folder outlives every file that was ever in it, usually after a move | `find . -type d -empty`, or a repo tool's own tree-check equivalent |
| Backup / duplicate file variants | `Component.old.tsx`, `notes copy.md`, `handler_v2.py` sitting alongside the file actually imported | glob for `*.bak`, `*.orig`, `* copy*`, `*_old*`, `*-v[0-9]*` and confirm nothing references the newer-looking name |
| Large or binary files committed by accident | A dataset, video, or build output bloats every clone forever, even after later deletion (history keeps it) | `git-sizer`, or `git ls-files \| xargs du -h \| sort -rh` for a quick top-N |

None of these replace the judgement pass below — they turn "nothing objectively
decidable was checked" into "this specific class of slop has no automated
guard," which is itself worth reporting even before it's wired into CI.

Then read for what automation cannot decide, cheapest failure first:

1. **P0 safety:** credentials, destructive commands, conflict markers, broken
   configuration (including a tool whose declared permissions don't cover the
   steps it instructs), unsafe defaults, completion claimed without proof.
2. **P1 correctness:** unhandled failures, unreachable paths, missing edge
   cases, stale references, scope violations, duplicated sources of truth.
3. **P1 product quality:** incomplete loading/empty/error/permission states,
   inaccessible interactions, responsive overflow, design-token violations.
4. **P2 maintainability:** naming, comments, consistency, dead weight.

### Judgement checks — what automation cannot decide

| # | Check | Test | Signal of a hit |
|---|---|---|---|
| 1 | Overlapping responsibilities | Write three realistic inputs, name which unit owns each | The wrong one still produces confident output — automation only catches identical names, not two things described differently that compete |
| 2 | Duplicate knowledge, paraphrased | Count the paragraphs that would need editing if the rule changed | More than one — pick an owner, leave pointers from the rest |
| 3 | God component | Ask whether a reviewer could approve or revert half without touching the rest | Signal is **not** length — it's whether the two halves are independently reviewable |
| 4 | Orchestration leakage | Ask who decides what happens next | A component deciding instead of producing its result and letting the caller decide |
| 5 | Design-surface slop | Check against the project's design system (don't invent rules): tokens, all meaningful states, visible focus, contrast, alt text, colour-independent meaning, reduced motion | No design contract to check against is itself a `P1` missing-decision finding |
| 6 | Evidence slop | For each important claim, ask what artefact proves it | "Looks fine" / "done" is not evidence — missing evidence is a finding, not an invitation to guess |
| 7 | Named handoffs | Check the stated successor against how the project actually routes work | Successor *contradicts* real routing, or is stated unconditionally where the real logic is conditional — a step with **no** named successor is a different, easy-to-forget problem, not this one |
| 8 | Counted provenance | Ask whether the number describes current state or a rule that survives state changing | "Seven call sites" rots on the next change; "retry 3 times" doesn't — automation can't tell these apart |

### Classic code-smell catalog — cross-reference for source-code sweeps

The checks above are this skill's own heuristics, tuned for repos and agent
artefacts generally. For a straight source-code sweep, cross-reference the
canonical taxonomy (Fowler's *Refactoring*, as organized by refactoring.guru)
so a finding can be named by its established term instead of reinvented per
sweep:

| Family | Representative smells | Typical fix |
|---|---|---|
| Bloaters | Long method, large class, long parameter list, primitive obsession, data clumps | Extract method/class, introduce parameter object |
| Object-orientation abusers | Switch statements standing in for polymorphism, refused bequest, alternative classes with mismatched interfaces | Replace conditional with polymorphism, extract superclass, unify interfaces |
| Change preventers | Divergent change (one class changes for many reasons), shotgun surgery (one change touches many classes) | Extract class, move method, consolidate the scattered logic behind one seam |
| Dispensables | Dead code, duplicate code, lazy class, data class, speculative generality, comments substituting for clear code | Delete, inline, extract-and-share, rename for clarity instead of commenting around it |
| Couplers | Feature envy, message chains, middle man, inappropriate intimacy between classes | Move method to where the data lives, hide delegate, reduce chain depth |

Same disposition rule applies as everywhere else in this skill: a smell that's
contained and mechanically checkable after the fix is **local** (Phase 2); a
smell whose fix changes a public interface or how components relate is
**structural** — report it, don't apply it here.

> If this catalog or the tooling map grows past a screenful, move it to
> `references/code-smells.md` and link it from here — `SKILL.md` loads on every
> invocation, a `references/` file loads only when a sweep actually needs it.

At wider scopes, read for dead weight nothing mechanical can judge: a superseded
document never marked as such, a script nothing calls, a config key nothing
reads, a dependency nothing imports, a directory left behind after everything
that used to live in it moved out, or a `*.bak`/`*-old`/`* copy*` variant sitting
next to the file that's actually imported. Folder-level slop hides from a
file-by-file read precisely because no single file is wrong — the tree shape
is.

Record each unit as `pass`, `finding`, or `deliberate exception`. An exception
names the rule, the reason, an owner, and an expiry; "intentional" is not a
justification.

## The report

A table: `file:line` · the smell · one sentence on what breaks. State the scope
covered, then the verdict: clean, or the count. If nothing is wrong, say so in
one line **and name what you read** — an empty review is indistinguishable from
one that never ran.

One filled instance, so the row shape is unambiguous rather than assumed:

| file:line | smell | what breaks |
|---|---|---|
| `documentation/SKILL.md:5` | malformed frontmatter — a stray list item folded into `when_to_use` by YAML's scalar-continuation rule | the live trigger text every skill listing shows ends with junk appended, not a formatting artifact |

And a clean verdict reads: *"Scope: `.claude/skills/refactoring/references/`. Both
files read in full. Clean — no TODOs, no dangling links, no stale counts."*

Split the findings, because only one group is safe to act on unasked:

| Group | Examples | Disposition |
|---|---|---|
| **Local** | a hedge, a stray `TODO`, a stale count, dead code with no external reference | Applied in Phase 2 — contained in one file, mechanically checkable after |
| **Structural** | merging or splitting a component, renaming an interface, retiring a document, changing how work is routed | **Its own unit of work.** It changes what exists or what fires |

Route each structural finding to the project's planning or decision process and
let that decide. "Retire this dead document" is settled and can become a task;
"this component overlaps its neighbour" is not — merging and splitting are both
live, and skipping to a task bakes in whichever came to mind first.

## Phase 2 — rectify

Only what was approved, and only the local group.

1. Apply the fixes.
2. **Re-run the project's automated checks at the same scope.** Repairs move
   line numbers, counts and references other checks assert against.
3. Quote the result. Still red is a debugging problem, not a reason to retry.
4. Report what changed, and which structural findings were handed off, where.

**You may be editing something in use** — a config a live process is reading, a
script invoked elsewhere. An edited file does not necessarily reload, so check
its effect the way an outside caller would rather than by re-triggering what you
just edited.

## What "clean" can and cannot mean

Green means every decidable check passed and the judgement pass found nothing
this time. It does not mean the project is provably clean — most of the
checklist needs a reader, which is why this skill exists. State the verdict as
what was checked, never as a guarantee.

## Red Flags — you are padding, not sweeping

- Re-stating what an automated check already printed.
- A finding with no `file:line`.
- "Consider possibly simplifying this section" — name the sentence to cut.
- Flagging a named handoff as leakage without checking how the project routes work.
- Editing anything before the full report has been delivered.
- Applying a structural fix because it "looked obvious."
- Sweeping a narrower scope than the request implied, to finish faster.
- Inventing a smell to avoid reporting clean.

**Each of these means: go back to the file and quote the line.**

## Common Mistakes

| Mistake | Why it bites |
|---|---|
| Reviewing a diff instead of the tree | Slop accumulates across many changes that each looked fine |
| Fixing as you go | The report then describes a tree that no longer exists |
| Treating a structural fix as local | It ships a change nobody reviewed |
| Skipping the re-run after repairs | Repairs move counts other checks assert against |
| Treating length as the god-component signal | Some units are long because the job is |
| Giving a tool fewer permissions than its own steps require | It can report the gate but not clear it |
| Fixing scope silently instead of stating it | The reader can't tell what was covered |
| Naming a finding only by ad-hoc description | The canonical term (`shotgun surgery`, `feature envy`) is faster for a reviewer to look up a fix for than a fresh paraphrase |

## Next step — you MUST take it

**The terminal state is invoking `code-review`.** The repairs made here are
themselves a change, and an unreviewed clean-up is how a sweep introduces the
defect it was run to prevent. If nothing was repaired, hand over anyway and say
the sweep was clean — the reviewer needs to know it ran.

## Routing

- Mandatory validator: the project's automated checks, at the start of Phase 1
  and again in Phase 2. Findings only mean something on a mechanically green tree.
- Preceded by verification of the work being swept.
- Terminal handoff: `code-review`, then `release-git`.
- Also entered off-chain after a component is added or removed, scoped to that
  component and its neighbours, then returns to whatever was happening.
- Structural findings become their own unit, routed to planning rather than
  applied. A recurring smell goes wherever the project records lessons.

## Success

- Every finding named a `file:line`.
- Scope covered was stated in the report.
- Automated check output was quoted, not re-derived.
- Any category with no automated check was named against the tooling map, not just flagged as absent.
- Folder-level slop (orphaned directories, backup-file variants, committed build artifacts, lockfile drift) was checked, not just file-level dead weight.
- Nothing was edited before the user approved the report.
- Structural findings were routed to planning, not applied here.
- Every relevant check was re-run and quoted after repairs.