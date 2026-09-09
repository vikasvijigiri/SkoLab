---
name: frontend-performance-rules
description: React/Next.js performance rule set — waterfalls, bundle size, server rendering, re-renders, rendering cost, JS micro-cost. Read from frontend-standards.md when a frontend task has a performance requirement or regression. Adapted from vercel-labs/agent-skills react-best-practices + addyosmani/agent-skills performance-checklist.
---

# Frontend performance rules

**Sources:** `github.com/vercel-labs/agent-skills` — `skills/react-best-practices`
(rule categories + priorities) and `github.com/addyosmani/agent-skills` —
`references/performance-checklist.md` (Core Web Vitals + the frontend checklist).
Pulled 2026-09-08. `frontend-standards.md` owns the CWV budgets; this file owns
the rules that hit them.

## Core Web Vitals targets

| Metric | Good | Needs work | Poor |
|---|---|---|---|
| LCP | ≤ 2.5s | ≤ 4.0s | > 4.0s |
| INP | ≤ 200ms | ≤ 500ms | > 500ms |
| CLS | ≤ 0.1 | ≤ 0.25 | > 0.25 |

## Priority order

Fix top-down — the first two categories are where the seconds are.

### 1. Eliminate waterfalls (CRITICAL)

- Do not `await` until the value is needed; kick off the request early.
- `Promise.all()` for independent async work — never sequential `await` of
  things that do not depend on each other. 2–10× on data-heavy routes.
- Restructure server-component composition so sibling async components resolve
  in parallel, not nose-to-tail.
- Suspense boundaries render the layout while data streams — first paint does
  not wait for the slowest query.

### 2. Bundle size (CRITICAL)

- No barrel-file imports (`import { x } from "@/components"`) on hot routes —
  they defeat tree-shaking and can pull thousands of unused modules; ~200–800ms
  on a cold start. Import from the defining file.
- Dynamic `import()` for heavy, below-the-fold, or interaction-gated components.
- Verify a dep ships ESM and marks `sideEffects: false` before trusting tree-shaking.
- Target < 200KB gzipped initial JS.

### 3. Server-side performance (HIGH)

- Treat Server Actions as public endpoints — authenticate and authorize every one.
- No shared module-level mutable state — it leaks one request's data into another.
- `React.cache()` for per-request dedupe of the same fetch across components.
- `after()` (Next) for non-blocking work — analytics, logging — off the response path.

### 4. Client-side data fetching (MEDIUM-HIGH)

- Cache by default; treat every fetch as shared, not private to the caller.
- No fetch-on-render waterfalls; hoist to the highest common ancestor or a loader.
- Uncontrolled inputs by default; a controlled input must be cheap per keystroke.

### 5. Re-render optimization (MEDIUM)

- Derive from existing state; do not copy it into new state that can desync.
- `React.memo` only on components that measurably re-render with equal props;
  `useMemo`/`useCallback` only where profiling shows benefit — not on primitives.
- Extract components to stop a parent's state change remounting an expensive subtree.

### 6. Rendering performance (MEDIUM)

- Animate `transform`/`opacity` only; never `transition: all`.
- `content-visibility: auto` + `contain-intrinsic-size` on off-screen sections.
- Virtualize lists past ~50 items (`virtua`, `react-window`).
- No layout reads in render (`getBoundingClientRect`, `offsetHeight`, `scrollTop`).
- Passive event listeners; React DOM resource hints (`preconnect`, `preload`) for known origins.
- No `unload` handlers, no `Cache-Control: no-store` on HTML — preserves bfcache.

### 7. JavaScript micro-cost (LOW-MEDIUM)

- Break tasks > 50ms — the main INP lever. `scheduler.yield()` (preferred),
  `scheduler.postTask()`, `yieldToMain` inside long loops.
- `requestIdleCallback` for deferrable work (analytics flush, prefetch, warmup).
- Build an index `Map` for O(1) lookups instead of repeated `.find()`.
- Cache property access and `.length` in hot loops.

### 8. Advanced (LOW)

- Effect events (`useEffectEvent`) for non-reactive values read inside effects.
- Stable callback refs.
- Prevent hydration-mismatch flicker for client-only values (storage, `Date`)
  without a visible flash.

## Images, fonts, network (from the performance-checklist)

- `<img>` with explicit `width`/`height`; WebP/AVIF; `srcset`/`sizes`.
- Below-fold `loading="lazy" decoding="async"`; LCP image `fetchpriority="high"`, never lazy.
- 2–3 font families, WOFF2 only, `font-display: swap`, preload the LCP-critical face,
  `size-adjust`/`ascent-override` on the fallback to kill swap CLS.
- Long `max-age` + content hashing on static assets; `preconnect` known origins;
  no unnecessary redirects.

## Common anti-patterns

| Anti-pattern | Fix |
|---|---|
| Sequential `await` of independent calls | `Promise.all()` |
| Barrel imports on a hot route | import from the defining file |
| `useMemo` on a primitive | delete it |
| Blocking the main thread with a long task | chunk with `scheduler.yield()` / Web Worker |
| Long list without virtualization | `virtua` past ~50 items |
| Layout thrashing (read/write interleave) | batch reads, then writes |
| Unoptimized images | WebP, responsive sizes, lazy below fold |
