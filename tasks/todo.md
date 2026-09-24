# Light Evidence Workspace tasks

## Task 1: Establish the shared light evidence foundation

**Acceptance criteria:**
- [ ] Light default tokens match `DESIGN.md`.
- [ ] Product chrome uses a calm navy ribbon rather than the legacy blue theme.

**Verification:**
- [ ] `npm run lint` from `apps/web`.

**Dependencies:** None

**Files likely touched:** `apps/web/src/app/globals.css`, shared layout files.

## Task 2: Implement CoLab manuscript evidence workspace

**Acceptance criteria:**
- [ ] Three-column desktop workspace exposes manuscript outline, editable
  document, research lens, and a compact status bar.
- [ ] Compile, template, share, preview, and focus controls remain reachable.
- [ ] Desktop and mobile layouts have no clipped critical controls.

**Verification:**
- [ ] Focused component test passes.
- [ ] Local browser screenshot reviewed at desktop and mobile widths.

**Dependencies:** Task 1

**Files likely touched:** `apps/web/src/components/workspace/DocumentsTab.tsx`,
`apps/web/src/components/workspace/ManuscriptWorkspace.module.css`, test file.

## Task 3: Release-quality verification

**Acceptance criteria:**
- [ ] Lint, unit tests, build, and contrast checks pass.
- [ ] Browser console is free of implementation errors during local visual review.

**Verification:**
- [ ] Run the commands named in `tasks/plan.md`.

**Dependencies:** Tasks 1-2
