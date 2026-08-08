---
name: android-build
description: Build and install the SkoLab Android app on Windows. Use whenever asked to build, compile, or install apps/android-app — always goes through scripts/build/build-and-install.ps1, never raw gradlew, because this repo's checkout path contains the unicode symbol pi (π) which crashes Gradle in standard shells. Do NOT use for iOS, web, or backend builds, or for general "run the app" requests unrelated to compiling (see the `run` skill).
---

# Android build

Raw `gradlew`/`gradlew.bat` invocations are blocked by a PreToolUse hook in
this repo (`.claude/hooks/block-raw-gradlew.js`) for exactly this reason:
the historical repo path contains **π**, which crashes Gradle in standard
shells.

Always build through the wrapper script instead:

```powershell
./scripts/build/build-and-install.ps1
```

It copies `apps/android-app` into an ASCII-only temp directory before
invoking Gradle there, then installs the resulting APK if a device/emulator
is connected (`adb devices` — check this first if install is the goal, not
just compile).

## What not to do

- Do not `cd apps/android-app && ./gradlew ...` — will be blocked by the hook
  anyway, but don't try to work around the block; the underlying crash is
  real, not just a permission restriction.
- Do not touch Gradle signing config or keystores under `apps/android-app`
  without being explicitly asked (see AGENTS.md "What not to touch").

## Verifying a build

After running the script, confirm success from its own output (build
succeeded / APK path / install result) rather than assuming — report the
actual APK path and, if installed, the `adb` install confirmation.

## Routing

- Mandatory validator: none beyond the wrapper script's own exit status and
  APK output — that is the build's ground truth.
- Preceded by: `design-token-compile`, if the build is verifying a
  design-token change reached the Android theme files.
- Terminal handoff: none. Report the build/install result.

## Success

The wrapper script (never raw `gradlew`) exited clean, the real APK path was
reported, and — if install was requested — the `adb` install confirmation was
shown, not assumed.
