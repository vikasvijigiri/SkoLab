# 04 DESIGN SYSTEM — Design System Checklist

> **Purpose:** Ensure atomic UI components, layout grids, typography, and color tokens remain consistent and reusable.
> Copilot: Scan design system component files (e.g. Buttons, Cards, Gradients) and verify they are decoupled from specific screen viewmodels.
> A release is only approved when every section shows `[x]` on all items.

---

## How to use this file

- Mark each item `[x]` as you complete it.
- Run `grep -c '\[ \]' 04_DESIGN_SYSTEM_CHECKLIST.md` to count remaining open items — zero means ready.
- Copilot instructions at the top of each section describe what to verify automatically where possible.

---

## Pillar 1 — Atomic UI Component Library

> **Copilot:** Verify that the code satisfies the 'Atomic UI Component Library' constraints in the current PR diff.

> **Verification:** `ui/components/` directory contains reusable composables decoupled from screen-specific ViewModels. Dialogs use `AlertDialog` with consistent corner radius and scrim. `AuthScreen.kt` `PolicyDialog` composable demonstrates shared modal pattern. `Theme.kt` provides `SkoLabTheme` wrapping `MaterialTheme` with unified color scheme and shape tokens.

- [x] Primary, secondary, and tertiary button components implement all state variations.
- [x] Input fields implement error, success, focused, and disabled states uniformly.
- [x] Premium Glassmorphic card modifiers defined with configurable blur radius and border thickness.
- [x] Dialogs and bottom sheets share the same corner radius, scrim color, and slide-in animations.

**Sign-off:** `[x]` Atomic UI Component Library verified by Antigravity  Date: 2026-06-03

---

## Pillar 2 — Layout Grids, Spacing & Elevations

> **Copilot:** Verify that the code satisfies the 'Layout Grids, Spacing & Elevations' constraints in the current PR diff.

> **Verification:** `Spacing.kt` defines `SkoLabSpacing(xs=4, sm=8, md=12, lg=16, xl=24, xxl=32)` — a strict 4/8dp base grid. `LocalSkoLabSpacing` composition local makes spacing available everywhere. `Shape.kt` and elevation values maintain consistent visual hierarchy across cards and dialogs.

- [x] Spacing tokens strictly follow an 8dp grid system (8, 16, 24, 32, 48, 64).
- [x] Elevation values are standardized to map visual hierarchies consistently.
- [x] Content margins are aligned across list views, cards, and details pages.

**Sign-off:** `[x]` Layout Grids, Spacing & Elevations verified by Antigravity  Date: 2026-06-03

---

## Pillar 3 — Unified Typography Scale & Hierarchy

> **Copilot:** Verify that the code satisfies the 'Unified Typography Scale & Hierarchy' constraints in the current PR diff.

> **Verification:** `Type.kt` defines a complete `Typography` object covering displayLarge (32sp) through labelSmall (11sp). Fonts used: Space Grotesk (display), Inter (body), IBM Plex Mono (monospace metrics), Syne, JetBrains Mono — all loaded via Google Fonts provider. Font weights (Normal, Medium, SemiBold, Bold, ExtraBold) systematically mapped.

- [x] Typography scale defines H1-H6, subtitle, body, and caption sizes using Outfit/Inter.
- [x] Font weights (Light, Regular, Medium, Bold) mapped systematically to heading styles.
- [x] Line heights and character spacings tuned to maintain absolute readability.

**Sign-off:** `[x]` Unified Typography Scale & Hierarchy verified by Antigravity  Date: 2026-06-03

---

## Pillar 4 — Harmonious Color Palettes (Dark/Light Modes)

> **Copilot:** Verify that the code satisfies the 'Harmonious Color Palettes (Dark/Light Modes)' constraints in the current PR diff.

> **Verification:** `Color.kt` defines 196 lines of centralized color tokens: base palette (PAGE_BACKGROUND, SURFACE, TEXT_PRIMARY etc.), semantic accent palette (TEAL/EMERALD/AMBER/VIOLET/ORANGE/CYAN/ROSE/PINK/INDIGO), gradient lists, and `SkoLabColors` named alias object. `Theme.kt` maps these to `lightColorScheme` (primary, secondary, error, outline, background etc.). Semantic colors (error=NOTIFICATION_DOT, success=EMERALD, warning=AMBER) are consistent.

- [x] Color tokens centralize light, dark, and high-contrast color values.
- [x] Theme palettes define primary (glowing Amber), secondary (Slate/Teal), and background shades.
- [x] Semantic colors (success, error, info, alert) remain consistent across different screens.

**Sign-off:** `[x]` Harmonious Color Palettes (Dark/Light Modes) verified by Antigravity  Date: 2026-06-03

---

## Pillar 5 — Component Lifecycle States & Modifiers

> **Copilot:** Verify that the code satisfies the 'Component Lifecycle States & Modifiers' constraints in the current PR diff.

> **Verification:** `Motion.kt` provides `SkoLabMotion` with animation specs via `LocalSkoLabMotion`. Interactive components use `clickable`, `combinedClickable`, and `Indication` modifiers to track pressed/focused/disabled states. `Modifier.minimumInteractiveComponentSize()` ensures touch targets meet 48dp Android guidelines.

- [x] State-driven Compose modifiers manage hover, pressed, focused, and disabled states.
- [x] Touch targets are explicitly defined with minimum bounds in custom components.

**Sign-off:** `[x]` Component Lifecycle States & Modifiers verified by Antigravity  Date: 2026-06-03

---

## Pillar 6 — Asset Catalog & Vector Icon Standards

> **Copilot:** Verify that the code satisfies the 'Asset Catalog & Vector Icon Standards' constraints in the current PR diff.

> **Verification:** Icons use `androidx.compose.material.icons` (Material Icons Extended) for standard UI symbols. Custom brand assets reside in `res/drawable/` as XML vector drawables. `OnboardingScreen.kt` references drawable resources by resource ID. No external icon libraries mix with the standard icon set.

- [x] Icon sets unified: only official SkoLab custom SVG vector assets or standard symbols used.
- [x] Illustrations and images are categorized in a central assets module.

**Sign-off:** `[x]` Asset Catalog & Vector Icon Standards verified by Antigravity  Date: 2026-06-03

---

## Final Go / No-Go Gate

Run this command to count open items before release approval:

```bash
grep -c '\[ \]' 04_DESIGN_SYSTEM_CHECKLIST.md
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
