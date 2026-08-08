---
name: design-token-compile
description: Compile design tokens from shared/skolab-design-system and verify synchronization across Next.js (apps/web) and Android (apps/android-app). Use whenever updating colors, typography, spacing, or design tokens in shared/skolab-design-system. Do NOT use for a one-off component style unrelated to the shared token source, or for visual regression checking (`visual-qa-review`).
---

# Design Token Compilation & Synchronization Skill

Per [AGENTS.md](file:///c:/Users/VikasVijigiri/Documents/SkoLab/AGENTS.md), design tokens originate in `shared/skolab-design-system` and are compiled into both web and mobile clients.

## Compilation Steps

1. **Compile Tokens**:
   Run the token compiler from the repo root:
   ```bash
   npm run compile:tokens
   ```

2. **Verify Web Token Imports (`apps/web`)**:
   - Ensure `apps/web/src/` components reference compiled CSS variables or design tokens, avoiding hand-rolled hex codes or static magic pixel values.
   - Run `npx tsc --noEmit` in `apps/web` to confirm no broken styling token imports.

3. **Verify Android Token Imports (`apps/android-app`)**:
   - Confirm compiled tokens are generated into Jetpack Compose theme files under `apps/android-app/app/src/main/java/.../ui/theme/`.

## Routing

- Mandatory validator: `npx tsc --noEmit` in `apps/web` — a broken token
  import shows up there.
- Terminal handoff: `visual-qa-review` if the change is visible in the web
  app, `android-build` if it needs confirming on the Android client.

## Success

`npm run compile:tokens` ran clean, `apps/web` typechecks with no broken
token imports, and the Android theme files under `ui/theme/` reflect the
same compiled values.
