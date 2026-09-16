# CoLab research-writing workspace overhaul

## Research basis

The target workflow is not a generic dashboard. Current Overleaf guidance
centres the editor on a code/source pane and a typeset preview, with explicit
recompile controls, compiler output, compiler settings, comments, review and
history. Zotero's current workflow treats citation search and bibliography
updates as first-class writing actions. A survey of 213 scientific authors and
27 documents found recurring requirements around collaborative authoring and
review. The design therefore keeps the manuscript central and exposes research
context progressively rather than opening a grid of unrelated cards.

## Product outcome

CoLab should let a researcher move through this loop without losing context:

`capture idea → outline → write source → compile → inspect evidence → cite →
review → share`

## Scope

### Workspace shell

- Dark Instrument visual language with a quiet, dense, 1440px work canvas.
- Three independently collapsible regions: file/context rail, writing surface,
  and research/review rail.
- Persistent project identity, save state, collaborator presence and command
  palette access.
- Responsive fallback: source first on small screens, panels become sheets.

### Writing surface

- Source and compiled preview visible together by default on desktop.
- Compile action with compiling/success/warning/error states and elapsed time.
- Compiler output is inspectable without leaving the manuscript.
- Markdown and inline/display LaTeX remain safe and fast while a full PDF
  compiler is not configured in the deployment environment.
- Focus mode, keyboard shortcuts, document outline and word/read-time metrics.

### Research rail

- Quick reference: abstract limit, section structure and citation count.
- Equations scratchpad, chat, tasks and meetings remain available as focused
  tabs instead of competing with the manuscript.
- Extensible evidence/citation tab contract, ready for OpenAlex/Zotero-backed
  search without coupling editor rendering to network latency.

### Reliability and security

- Preserve existing Firestore ownership/role rules and Go WebSocket scoping.
- Keep remote data out of the editor render path; show skeleton, empty and
  recoverable error states.
- Avoid unsafe HTML and LaTeX execution; rendered math stays through KaTeX's
  safe string output with `throwOnError: false`.
- No schema or production secret changes in this UI slice.

## Verification gates

- Focused workspace tests and full web Vitest suite.
- TypeScript, ESLint, Next production build and contrast checks.
- Playwright at 320, 768, 1024 and 1440px; no page errors; screenshot evidence.
- Go and Python suites when their toolchains are available locally; otherwise
  repository CI is the merge gate.
