# Design System Changelog — SkoLab

All notable changes to the SkoLab design system, theme tokens, and typography layouts are documented in this file.

---

## [1.1.0] - 2026-06-04

### Added
- **Centralized Design Tokens**: Defined `design_tokens.json` in the root workspace directory as the single source of truth for color stop definitions, spacing ramps, corner shapes, and motion speeds.
- **Auto-Sync Token Script**: Created `scripts/sync_tokens.py` to parse JSON tokens and validate/verify Kotlin Compose theme variables, enforcing 1:1 design-to-code alignment.
- **Haptic Click Feedback**: Programmed haptic feedback click effects directly in custom button primitives (`SkoLabPrimaryButton`, `SkoLabOutlinedButton`, `GoogleSignInButton`), delivering tactile confirmations, errors (`HapticFeedbackConstants.REJECT`), and selections.
- **Pull-To-Refresh Integrations**: Implemented `PullToRefreshBox` layouts in the primary research feed (`FeedScreen.kt`) and personal intelligence vaults (`LibraryScreen.kt`), enhancing lists with native drag-to-refresh interactions.
- **Inline Text Formatting**: Embedded monospace digit configurations (`fontFeatureSettings = "tnum"`) in numeric highlights to prevent layout column jitter.

### Improved
- **Dark Mode Elevation Overlay**: Shifted from pure black to cohesive desaturated dark surfaces (`#0F172A` to `#1E293B`) to reduce visual vibration.
- **Manual Theming Override**: Introduced Settings toggles allowing explicit user override of light, dark, and system default modes.
- **Line Length Constraints**: Restricted `MarkdownText` components to `560.dp` maximum width to keep body lines reading within the optimal 45–75 character measure.

### Fixed
- **Permissions Rationale Flow**: Embedded custom rationale explanation dialogs preceding contact access (`READ_CONTACTS`) and push notification (`POST_NOTIFICATIONS`) system requests.
- **Real-Time Input Checks**: Introduced regex-based inline feedback loops for login inputs with instant success indicators.
- **Scroll State Cache**: Retained list offsets across screen pops in the Discovery and Saved views.

---

## [2.1.0] - 2026-06-04

### Added
- **`LegacySkoLabButton` (Deprecated)**: A backwards-compatible shim wrapping `SkoLabPrimaryButton`. Annotated with `@Deprecated(level = DeprecationLevel.WARNING, replaceWith = …)` to surface compiler warnings at all remaining call sites. See `SkoLabButtons.kt §LegacySkoLabButton`.
- **Dynamic Color (Monet) Support**: `SkoLabTheme` now accepts a `dynamicColor: Boolean` parameter. On Android 12+ (API 31+), when enabled, Material 3 `dynamicLightColorScheme` / `dynamicDarkColorScheme` replace the static palette, deriving colours from the user's wallpaper. User preference is persisted via DataStore `dynamic_color_enabled` key. Toggle available in Profile → App Settings.
- **Delight Composable Utilities** (`SharedComponents.kt`):
  - `ConfettiCelebration` — 40-particle canvas confetti overlay with 2.2s fade-out, triggered on meaningful saves.
  - `MilestoneCelebrationDialog` — celebratory dialog at 5 / 10 / 25 saved papers with emoji, title, and contextual subtitle.
  - `StageProgressBar` — cycling stage label + linear progress bar for long-running operations.
  - `StaggeredAnimatedVisibility` — index-based entrance delay (50ms per item, max 400ms cap) for cascading list reveals.
- **Session Recorder Hook** (`SkoLabAnalytics.initSessionRecorder()`): Logs a `session_started` event and a Crashlytics breadcrumb each cold start. Acts as a documented integration point for future session-replay SDKs (e.g., FullStory, LogRocket).
- **Session Counter & NPS Flag** (`UserPreferences`): `sessionCount` increments on every app cold start. `npsShown` persists whether the NPS prompt has been displayed. Enables the NPS trigger at session ≥ 5 (first occurrence only).
- **UX Documentation**:
  - `docs/ux/ab_testing.md` — A/B test registry, variant template, active/planned tests, and anti-patterns.
  - `docs/ux/feedback_review.md` — NPS scoring rubric, review cadence table, and feedback-to-issue pipeline.

### Deprecated
- `LegacySkoLabButton` — Introduced and immediately deprecated. Will be removed in the next major release once all call sites are migrated to `SkoLabPrimaryButton` / `SkoLabOutlinedButton`.

---

## Design System Deprecation Cycle Policy
To ensure breaking design system changes do not disrupt product development, SkoLab implements a formal deprecation cycle:
1. **Deprecation Phase**: An old component or theme token is marked with `@Deprecated` (or equivalent metadata) containing a `replaceWith` parameter, along with a migration guide note in this changelog.
2. **Coexistence Phase**: The deprecated component coexists alongside its replacement for exactly 2 sprints (approx. 4 weeks).
3. **Removal Phase**: Once all references in the codebase are migrated, the deprecated code is permanently deleted.

