# Skolab Motion Rules

## Principles
Motion must feel purposeful and quick — never decorative.
Users are researchers with high cognitive load. Slow animations feel like lag.

## Duration scale
- Fast (150ms): button press feedback, chip selection, toggle state
- Standard (250ms): card expand/collapse, bottom sheet peek, icon swaps
- Slow (400ms): screen transitions, modal appear, hero number count-up
- Extra slow (600ms): onboarding illustrations, first-launch animations only

## Screen transitions
- Push navigation (going deeper): new screen slides in from right
- Pop navigation (going back): current screen slides out to right
- Modal / bottom sheet: slides up from bottom, 400ms, FastOutSlowIn easing
- Tab switching in bottom nav: crossfade only — no slide

## Progress bar animation
- Streak bar (#A8FF3E) fill: always animates on first render, 600ms, LinearOutSlowIn
- Never animate progress bars on every frame update — only on screen entry

## Forbidden motion
- No bounce/spring physics on navigation transitions
- No parallax scrolling effects
- No auto-playing animations in idle state
- No skeleton loaders that pulse faster than 1.5 seconds per cycle
