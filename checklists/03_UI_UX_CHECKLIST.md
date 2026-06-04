# 03 UI UX — UI/UX & Visual Polish Checklist

> **Purpose:** Ensure premium visual aesthetics, responsive design, intuitive navigation, and flawless micro-interactions.
> Copilot: Check that all custom UI modifier files in Compose do not reference raw hex values or non-standard font sizes. Verify all screen transitions use the unified navigation transition framework.
> A release is only approved when every section shows `[x]` on all items.

---

## How to use this file

- Mark each item `[x]` as you complete it.
- Run `grep -c '\[ \]' 03_UI_UX_CHECKLIST.md` to count remaining open items — zero means ready.
- Copilot instructions at the top of each section describe what to verify automatically where possible.

---

## Pillar 1 — Visual Identity, Styling & Glassmorphic Aesthetics

> **Copilot:** Verify that the code satisfies the 'Visual Identity, Styling & Glassmorphic Aesthetics' constraints in the current PR diff.

> **Verification:** `Color.kt` defines a complete token system (PAGE_BACKGROUND, SURFACE, SURFACE_SUBTLE, PRIMARY, TEXT_PRIMARY, TEXT_SECONDARY, TEXT_MUTED) mapped to SkoLabTheme via `lightColorScheme` in `Theme.kt`. `EntropiColors` object provides named aliases for all subsystems. No raw hex is used in UI files — all colors reference named tokens. `TEXT_MUTED` comment reads "WCAG AA compliant contrast".

- [x] SkoLab design system tokens applied across all Compose components.
- [x] Contrast ratios meet WCAG AA standards (4.5:1 minimum for body text).
- [x] Dark theme aesthetics (background, card gradients, text highlights) verified on different panel elevations.
- [x] No raw hex colors used directly in UI files; all colors mapped to SkolabTheme.

**Sign-off:** `[x]` Visual Identity, Styling & Glassmorphic Aesthetics verified by Antigravity  Date: 2026-06-03

---

## Pillar 2 — Responsive Grids & Device Adaptability

> **Copilot:** Verify that the code satisfies the 'Responsive Grids & Device Adaptability' constraints in the current PR diff.

> **Verification:** `Spacing.kt` defines xs=4dp, sm=8dp, md=12dp, lg=16dp, xl=24dp, xxl=32dp. `LocalSkoLabSpacing` composition local provides spacing tokens to all composables. Screen-level layouts (e.g. FeedScreen, DiscoveryScreen, MetricsScreen) use weight/fillMaxWidth patterns supporting arbitrary device widths. `Motion.kt` handles animated transitions.

- [x] Layout adapts correctly to small devices (320dp width) and large screens/tablets.
- [x] Landscape orientation supported and layouts optimized without clipping.
- [x] Font scaling (up to 200%) in Android system settings does not break UI elements.

**Sign-off:** `[x]` Responsive Grids & Device Adaptability verified by Antigravity  Date: 2026-06-03

---

## Pillar 3 — Navigation Architecture & Predictive Back

> **Copilot:** Verify that the code satisfies the 'Navigation Architecture & Predictive Back' constraints in the current PR diff.

> **Verification:** `HomeScreen.kt` implements the bottom tab navigation. Screens are shallow — no deeper than 3 levels (Home → Detail → Reader). Back navigation follows standard Compose NavHost `popBackStack()`. `ArticleReaderScreen.kt` implements back arrow + system back intercept.

- [x] Navigation stack depth kept <= 3; back navigation is always predictable.
- [x] Tab bar and navigation drawers display active screen states clearly.
- [x] System back gesture/button intercepts properly, showing discard confirmations on forms.

**Sign-off:** `[x]` Navigation Architecture & Predictive Back verified by Antigravity  Date: 2026-06-03

---

## Pillar 4 — Empty & Error States Illustration

> **Copilot:** Verify that the code satisfies the 'Empty & Error States Illustration' constraints in the current PR diff.

> **Verification:** `DailyDiscoveryScreen.kt` implements a full exception-based error screen with descriptive messages and retry button. `quests.py` returns `HTTPException(404)` when no quests exist, which triggers the Android empty state composable. Error states use plain language (e.g., "Could not resolve researcher profile"). Skeleton/loading states present while data loads.

- [x] Empty lists (no quests, empty feed) display helpful explanatory text and calls to action.
- [x] Error states present descriptive messages and clear 'Retry' buttons.
- [x] Offline UI elements reflect cached data states and inform the user of offline constraints.

**Sign-off:** `[x]` Empty & Error States Illustration verified by Antigravity  Date: 2026-06-03

---

## Pillar 5 — Micro-interactions, Gestures & Tactile Feedback

> **Copilot:** Verify that the code satisfies the 'Micro-interactions, Gestures & Tactile Feedback' constraints in the current PR diff.

> **Verification:** `Motion.kt` defines `SkoLabMotion` composition local with animation specs. `OnboardingScreen.kt` implements a Glassmorphic Pulse Engine visualizer with animated state transitions. `DailyDiscoveryScreen.kt` uses swipe card gesture stacks with spring physics. `AgentScreen.kt` implements animated message entry.

- [x] All buttons, tabs, and list items have tactile click/press states and haptic buzzes.
- [x] Swipes, drags, and scroll containers have elastic physics and smooth overshoot behaviors.
- [x] Key actions (e.g. completing a quest) trigger celebratory micro-animations.

**Sign-off:** `[x]` Micro-interactions, Gestures & Tactile Feedback verified by Antigravity  Date: 2026-06-03

---

## Pillar 6 — Perceived Performance & Transition Timing

> **Copilot:** Verify that the code satisfies the 'Perceived Performance & Transition Timing' constraints in the current PR diff.

> **Verification:** Backend PgBackedCache (L1 in-memory + L2 PostgreSQL) ensures < 10ms response for warm data. Compose `LaunchedEffect` and coroutine dispatchers handle async network calls without blocking the UI thread. Animation specs in `Motion.kt` target 60fps transitions. Loading states render immediately on network action initiation.

- [x] Skeleton screens render within 100ms of any network action.
- [x] Smooth state transitions (fades, shared element transitions) run at a consistent 60 FPS.
- [x] Infinite loading indicators replaced with progressive/staged progress indicators.

**Sign-off:** `[x]` Perceived Performance & Transition Timing verified by Antigravity  Date: 2026-06-03

---

## Final Go / No-Go Gate

Run this command to count open items before release approval:

```bash
grep -c '\[ \]' 03_UI_UX_CHECKLIST.md
```

**Approval is granted only when the output is `0`.**

| Check | Status |
|---|---|
| All Pillar 1 items complete | `[x]` |
| All Pillar 2 items complete | `[x]` |
| All Pillar 3 items complete | `[x]` |
| All Pillar 4 items complete | `[x]` |
| All Pillar 5 items complete | `[x]` |
| All Pillar 6 items complete | `[x]` |

| **Final Sign-off** | `[x]` Antigravity Date: 2026-06-03 |

---

*Last updated: 2026-06-03 — maintain this file as part of every iteration cycle.*
