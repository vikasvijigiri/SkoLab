---
name: visual-qa-review
description: Drive the SkoLab web app (apps/web) through its real routes with the Playwright MCP server and check for visual/UX regressions — screenshots across viewport sizes, console errors, and broken layout. Use after any UI/frontend change (components, pages, design tokens, Tailwind config) before considering it done, or when asked for a "visual QA", "UI review", or "does this look right" check. Do NOT use for backend-only changes, or in place of `full-repo-test-suite`'s TypeScript/lint gates.
---

# Visual QA review

Uses the `playwright` MCP server (already configured in `.mcp.json`) to
actually load pages in a real browser rather than relying on `tsc --noEmit`
or reading the diff — see AGENTS.md: "Type checking... verifies code
correctness, not feature correctness."

## Before starting

1. Confirm the dev server is up: `curl -s -o /dev/null -w "%{http_code}" http://localhost:3000`.
   If it's not running, start it (`npm run dev:web`) and wait for it to bind
   before navigating — don't screenshot a connection-refused page.
2. Figure out which routes are actually affected by the change (don't blindly
   re-check all of them for a one-component edit). Current top-level routes
   (page paths, not slash-commands): login, signup, onboarding, home,
   horizon, discovery, nexus, profile, workspace, workspace/[id],
   author/[id], paper/[id]. Routes under `(app)` require an authenticated
   session —
   check whether the current browser context already has one (via a prior
   login) before assuming a route is reachable.

## Steps

1. `browser_navigate` to the target route.
2. `browser_console_messages` — capture immediately after load. Any error or
   warning here is a real finding, not noise; report it even if the page
   *looks* fine.
3. `browser_resize` through at least three breakpoints and re-screenshot at
   each — this repo's Tailwind config is mobile-first, so a desktop-only
   check misses the most common regressions:
   - mobile: 390x844
   - tablet: 834x1194
   - desktop: 1440x900
4. `browser_take_screenshot` (full page) at each breakpoint, and
   `browser_snapshot` (accessibility tree) at least once per route — the
   snapshot catches things a screenshot can miss (missing alt text, wrong
   heading order, unlabeled controls).
5. For interactive changes (new button, modal, form), drive the actual
   interaction (`browser_click` / `browser_type` / `browser_fill_form`) and
   re-screenshot the resulting state — don't only check the initial render.
6. If the change touches user-facing text that could contain LaTeX/math or
   OpenAlex HTML titles, specifically verify it rendered through
   `MathText`/`MarkdownText` and not as raw `{text}` — a literal `$...$` or
   an HTML tag visible in the screenshot means it bypassed those components
   (see AGENTS.md).

## What counts as a finding

- Any console error/warning.
- Layout breakage at any of the three breakpoints (overflow, overlap,
  unreadable contrast, a component that doesn't reflow).
- Accessibility snapshot issues: missing labels, wrong landmark/heading
  structure, focus not reaching an interactive element.
- Design-token drift: colors/spacing that don't match
  `shared/skolab-design-system` (hand-rolled hex values, arbitrary Tailwind
  values where a token should have been used).
- Raw unrendered math/HTML (see step 6).

## Reporting

Report per-route: which breakpoints were checked, what the console showed,
and a plain description of anything that looked wrong (with the screenshot
as evidence, not just "looks fine"). Don't claim a UI change is verified
without having actually navigated to it in this session — a passing
`tsc --noEmit` is necessary but not sufficient (see AGENTS.md's frontend
go/no-go check).

## Routing

- Mandatory validator: none beyond the Playwright session itself —
  screenshots, console output, and the accessibility snapshot are the
  evidence.
- Preceded by: the frontend change plus a passing `tsc --noEmit`
  (`full-repo-test-suite` or `design-token-compile`, whichever changed).
- Terminal handoff: `code-review`.

## Success

Every affected route was navigated to (not just typechecked), console
output was captured, all three breakpoints were screenshotted, and any
finding is backed by a real screenshot or console line, not "looks fine".
