# Production-Grade Android App — Master Checklist

> **Purpose:** 10 mandatory domains every professional Android app must satisfy before claiming
> production-grade status. This file is the authoritative quality gate.
>
> **Copilot:** On any task touching code quality, architecture, release, or compliance — scan
> this file, identify open items in the relevant domain, and surface them before proceeding.
> A production release requires zero open `[BLOCKER]` items across all 10 domains.

---

## How to use this file

```bash
# Count open blockers (must be 0 before release)
grep -c 'BLOCKER.*\[ \]' PRODUCTION_CHECKLIST.md

# Count all open items
grep -c '\[ \]' PRODUCTION_CHECKLIST.md

# Count open items per domain
grep -A 100 '## Domain 3' PRODUCTION_CHECKLIST.md | grep -c '\[ \]'
```

Tags per item:
- `[BLOCKER]` — release halts if incomplete
- `[AUTO]` — can be automated or verified by tooling
- `[CI]` — must be enforced in CI pipeline
- `[MANUAL]` — requires human judgment or action

---

## Domain 1 — Architecture & Code Structure

> **Copilot:** Scan the module graph for any ViewModel importing Retrofit, Dao, or
> DataSource directly. Flag any class over 300 lines. Check that every cross-module
> dependency goes through an interface, not a concrete class. Verify NavGraph contains
> all declared routes. Run `./gradlew detekt` and confirm zero violations.

- [ ] `[BLOCKER]` Clean architecture enforced: UI → ViewModel → UseCase → Repository → DataSource — no ViewModel importing Retrofit directly, no Activity holding business logic
- [ ] `[BLOCKER]` Feature-based modularisation — each feature is an independent Gradle module (`:feature:home`, `:feature:profile`, etc.) enabling parallel builds and independent testing
- [ ] `[BLOCKER]` Single source of truth for all state — no state duplicated across ViewModel and Repository; derived state is computed, not stored
- [ ] `[AUTO][CI]` No God classes — every class has a single responsibility, under 300 lines; enforced via detekt `MaxLinesPerClass` rule
- [ ] `[BLOCKER]` Dependency inversion via interfaces — no concrete class dependencies across module boundaries; ViewModel depends on `IUserRepository`, not `UserRepositoryImpl`
- [ ] `[MANUAL]` Navigation graph defined in XML / Compose NavHost — all routes declared, deep links registered, no imperative fragment transaction chains
- [ ] `[AUTO][CI]` No hardcoded strings, colours, or dimensions in Kotlin files — all in resource files; enforced via detekt `StringLiteralDuplication` rule

**Sign-off:** `[ ]` Verified by _______________ Date: _______________

---

## Domain 2 — CI/CD Pipeline

> **Copilot:** Check `.github/workflows/` (or equivalent) for a PR workflow running lint,
> unit tests, and build. Verify no API keys, keystore passwords, or `google-services.json`
> credentials are committed to the repo (scan with `git log -S 'API_KEY'`). Confirm
> `versionCode` is auto-incremented by CI, not manually edited in `build.gradle`.

- [x] `[BLOCKER][CI]` CI runs on every pull request: lint + unit tests + build — PR cannot merge if any step fails; target CI time under 10 minutes
- [ ] `[BLOCKER][CI]` Automated signed AAB build for every merge to main — artefact uploaded to CI storage with version tag
- [ ] `[AUTO][CI]` Semantic versioning enforced: `vMAJOR.MINOR.PATCH` — `versionCode` and `versionName` auto-incremented by CI, never manually edited
- [ ] `[BLOCKER][MANUAL]` Separate build variants: `debug` / `staging` / `release` with distinct application IDs — all three installable side-by-side
- [ ] `[BLOCKER][CI]` Zero secrets in repo — all API keys, keystore passwords, and service account files managed via CI secret store
- [ ] `[AUTO][CI]` Automated Play Store deployment to internal track on merge to release branch — via Fastlane or Gradle Play Publisher; human approval gate before production promotion
- [x] `[AUTO]` Gradle build cache and configuration cache enabled — cold CI build under 5 minutes, incremental build under 2 minutes
- [ ] `[BLOCKER][MANUAL]` Branch protection on `main` and `release/*` — requires PR + passing CI + 1 approving reviewer; force push disabled

**Sign-off:** `[ ]` Verified by _______________ Date: _______________

---

## Domain 3 — Observability & Monitoring

> **Copilot:** Check that the CI release workflow includes a `./gradlew uploadCrashlyticsMappingFile`
> step. Scan Analytics event calls for raw string literals — they must reference a sealed class
> or enum, not inline strings. Verify `FirebaseRemoteConfig` or equivalent is initialised before
> any feature flag is read. Check that Play Console Android Vitals alerts are configured.

- [ ] `[BLOCKER][CI]` Firebase Crashlytics integrated — real-time crash reporting with ProGuard mapping file uploaded automatically in CI release workflow
- [ ] `[BLOCKER][MANUAL]` Analytics events defined in a typed sealed class or enum — no raw string event names; `AnalyticsEvent.PaperViewed(paperId)` not `analytics.log("paper_viewed")`
- [x] `[BLOCKER][MANUAL]` Funnel analytics instrumented for all critical user journeys — sign up, onboarding completion, core action, retention trigger; validated in DebugView before release
- [ ] `[BLOCKER][MANUAL]` Remote config / feature flags integrated — every new feature behind a flag; kill switch available without a hotfix deploy
- [ ] `[AUTO][MANUAL]` Custom performance traces for critical interactions — cold start, feed load, search response; P95 alert threshold configured
- [ ] `[BLOCKER][MANUAL]` Android Vitals dashboard monitored with alerts — ANR rate < 0.1%, crash rate < 1%; on-call notified on threshold breach
- [x] `[BLOCKER][MANUAL]` Server-side structured logs with correlation IDs in a queryable dashboard — saved queries for common failure patterns

**Sign-off:** `[ ]` Verified by _______________ Date: _______________

---

## Domain 4 — Code Quality & Standards

> **Copilot:** Run `./gradlew detekt` and `./gradlew lint`. Both must exit with zero violations.
> Check `.editorconfig` and `ktlint` config exist. Scan for TODO/FIXME comments without a
> ticket reference. Verify `libs.versions.toml` exists and all dependency versions are
> declared there. Run OWASP Dependency-Check and confirm no CVSS ≥ 7 vulnerabilities.

- [ ] `[BLOCKER][CI]` Detekt static analysis configured with custom ruleset — zero violations; complexity, naming, style, and performance rules enforced; build fails on any violation
- [ ] `[AUTO][CI]` Android Lint baseline set — new lint warnings above baseline fail the build; baseline file committed to repo
- [ ] `[AUTO][CI]` Kotlin style enforced via `.editorconfig` + ktlint — formatting automated via pre-commit hook or CI check; never argued in code review
- [ ] `[BLOCKER][MANUAL]` Code review checklist in `.github/pull_request_template.md` — covers architecture, test coverage, naming, security; reviewer confirms all items before approving
- [ ] `[AUTO][CI]` No stale TODO/FIXME/HACK comments — all TODOs must reference a ticket (`// TODO(SKOL-123): description`); detekt `TodoCommentRule` enforced
- [ ] `[MANUAL]` Dependency versions centralised in `libs.versions.toml` — no version strings scattered across module `build.gradle` files
- [ ] `[BLOCKER][CI]` Dependency vulnerability scan on every CI run — OWASP Dependency-Check or GitHub Dependabot; build blocked on CVSS ≥ 7

**Sign-off:** `[ ]` Verified by _______________ Date: _______________

---

## Domain 5 — API Design & Contract

> **Copilot:** Check all Retrofit interface files — verify every endpoint URL contains `/v1/`
> or higher. Check that every API response sealed class has an `Error` state that maps a
> structured `{code, message}` body. Verify `OkHttpClient` builder sets explicit
> `connectTimeout` and `readTimeout`. Check for a Pact or contract test module.

- [x] `[BLOCKER][MANUAL]` API versioned from day one: all endpoints use `/api/v1/...` — breaking changes ship as v2; v1 maintained for minimum 6-month deprecation window
- [ ] `[BLOCKER][MANUAL]` OpenAPI / Swagger spec committed to repo and kept in sync — contract-first or spec auto-generated from code; version-tagged per release
- [ ] `[BLOCKER][MANUAL]` All API errors return a structured body: `{code, message, details}` — Android client maps error codes to user-facing strings via a sealed class; no plain text error strings
- [x] `[BLOCKER][MANUAL]` Pagination on all list endpoints — cursor-based or offset; max page size enforced server-side (e.g. 50 items); no unbounded result sets
- [ ] `[MANUAL]` Idempotency keys on all mutation endpoints — prevents duplicate submissions from client retry logic; client generates UUID per operation
- [ ] `[AUTO][CI]` Consumer-driven contract tests (Pact) — client and server verified in sync; server shape changes without client update fail CI
- [ ] `[BLOCKER][MANUAL]` Timeout, retry, and backoff defined for every API call — OkHttp: connect 10s, read 30s; exponential backoff with jitter; max 3 retries

**Sign-off:** `[ ]` Verified by _______________ Date: _______________

---

## Domain 6 — Accessibility (WCAG 2.1 AA)

> **Copilot:** Scan all Composable and XML layout files for `ImageButton` or `IconButton`
> without `contentDescription`. Check that all clickable elements have a minimum size of
> 48dp. Flag any colour usage that conveys meaning without an accompanying icon or label.
> Verify `AccessibilityChecks.enable()` is called in the UI test base class.

- [ ] `[BLOCKER][CI]` All interactive elements have content descriptions or semantic labels — no `ImageButton` or `IconButton` without `contentDescription`; enforced via Accessibility Scanner and Espresso `AccessibilityChecks`
- [x] `[BLOCKER][CI]` Touch targets ≥ 48×48 dp for all tappable elements — padding used to meet target size; verified via Layout Inspector
- [x] `[BLOCKER][MANUAL]` Colour contrast ratio ≥ 4.5:1 for normal text, ≥ 3:1 for large text — verified in Figma and in-app with Accessibility Scanner
- [x] `[BLOCKER][MANUAL]` App fully navigable via TalkBack — no focus traps, no skipped elements, logical focus order; manual walk-through on every release
- [x] `[MANUAL]` Text scales correctly up to 200% without layout breakage — no clipped text, no overlapping elements at maximum font size setting
- [x] `[BLOCKER][MANUAL]` No information conveyed by colour alone — error states use colour + icon + label; not just a red border
- [ ] `[MANUAL]` Animated content respects Reduce Motion setting — check `ValueAnimator.areAnimatorsEnabled()` and skip or shorten animations when false

**Sign-off:** `[ ]` Verified by _______________ Date: _______________

---

## Domain 7 — Data Privacy & Compliance

> **Copilot:** Check Play Console Data Safety section is completed. Verify an account deletion
> flow exists in the app settings. Scan Firebase Analytics event properties for any field
> names containing "email", "phone", "name", or "address". Check that runtime permissions
> are requested at point-of-use, not in the onboarding flow. Verify all `SharedPreferences`
> usages use `EncryptedSharedPreferences`.

- [x] `[BLOCKER][MANUAL]` Privacy policy linked from Play Store listing and in-app settings — covers data collected, purpose, retention period, and deletion; legal review completed
- [ ] `[BLOCKER][MANUAL]` Play Console Data Safety section accurately completed — all collected data types declared; no mismatches with actual SDK behaviour
- [x] `[BLOCKER][MANUAL]` Account deletion flow implemented — user data purged within 30 days; in-app option + web fallback; deletion confirmed by email (Play Store policy requirement)
- [ ] `[BLOCKER][MANUAL]` Analytics SDKs configured for GDPR — no PII in event properties; user ID hashed before sending; Firebase Analytics `setUserId` uses hashed value only
- [x] `[BLOCKER][MANUAL]` Runtime permissions requested at point-of-use — not in an upfront onboarding wall; rationale dialog shown before system prompt
- [ ] `[BLOCKER][MANUAL]` All third-party SDKs audited — list of SDKs, data collected, and third-party sharing documented; reviewed annually
- [x] `[BLOCKER][MANUAL]` No personal data stored in plaintext on device — Room DB encrypted with SQLCipher or Android Keystore; sensitive files in encrypted storage

**Sign-off:** `[ ]` Verified by _______________ Date: _______________

---

## Domain 8 — Error Handling & Resilience

> **Copilot:** Scan all ViewModel files — every `Flow` or `StateFlow` from a repository call
> must have an `.catch {}` block or equivalent `onFailure` handler. Check that every screen
> composable handles a loading, success, and error state. Verify `WorkManager` is used for
> offline write queuing. Check that no screen shows a blank view when the data list is empty.

- [ ] `[BLOCKER][MANUAL]` Every API call has an explicit error state in the UI — loading / success / error sealed class; error state shows human-readable message + retry CTA; no silent failures
- [ ] `[BLOCKER][CI]` No unhandled exceptions can crash the app in release build — global uncaught exception handler logs to Crashlytics before terminating; never swallows silently
- [ ] `[BLOCKER][MANUAL]` Network errors, server errors, and auth errors distinguished in the UI — offline banner for no connectivity; re-auth flow for 401; generic retry for 500
- [ ] `[MANUAL]` Offline mode defined — cache-first for reads; write operations queued via WorkManager and synced on reconnect; behaviour documented per feature
- [x] `[AUTO][MANUAL]` Client-side input validation before API call — email format, length limits, required fields checked locally; reduces unnecessary round-trips
- [x] `[BLOCKER][MANUAL]` Empty states designed and implemented for every list and feed screen — illustration + CTA; no blank white screen; designed in Figma and verified in app
- [ ] `[BLOCKER][MANUAL]` Session expiry handled gracefully — token refresh interceptor; if refresh fails, user lands on login with message; deep link state preserved for post-auth redirect

**Sign-off:** `[ ]` Verified by _______________ Date: _______________

---

## Domain 9 — Release Management

> **Copilot:** Check that `CHANGELOG.md` exists and has an entry for the current version.
> Verify `docs/hotfix-runbook.md` exists. Check Play Console for a staged rollout
> configuration. Verify the signing keystore is NOT stored in the repo. Check that a
> `minimum_version_code` remote config key exists for force-update enforcement.

- [x] `[BLOCKER][MANUAL]` Release branch strategy defined: `main` → `release/x.y` → tags — no direct commits to release branch; hotfixes cherry-picked and tagged
- [ ] `[MANUAL]` `CHANGELOG.md` maintained — every release has a user-readable summary in Added / Changed / Fixed / Removed / Security format; written before release cut
- [ ] `[BLOCKER][MANUAL]` Staged rollout plan: 10% → 25% → 50% → 100% with 24h hold at each stage — crash-free rate and ANR rate checked before advancing each increment
- [x] `[BLOCKER][MANUAL]` Hotfix runbook documented and rehearsed — `/docs/hotfix-runbook.md` covers: alert → patch → signed build → expedited Play review; rehearsed at least once
- [ ] `[BLOCKER][MANUAL]` In-app force update mechanism implemented — `minimum_version_code` in Remote Config; mandatory update dialog shown if installed version is below minimum
- [ ] `[BLOCKER][MANUAL]` App signing keystore backed up in at least two secure offline locations — keystore and password in separate secure vaults; losing it means the app cannot be updated

**Sign-off:** `[ ]` Verified by _______________ Date: _______________

---

## Domain 10 — Documentation & Team Onboarding

> **Copilot:** Check that `README.md` covers architecture overview, environment setup, and
> build commands. Verify `/docs/adr/` directory exists with at least one ADR. Check that
> API documentation is auto-generated and accessible. Verify an incident runbook exists
> at `/docs/runbooks/incident.md`. Flag if any Composable in the component library
> lacks an `@Preview` annotation.

- [x] `[BLOCKER][MANUAL]` README covers: architecture overview, setup in under 5 steps, env variables, and build commands — a new developer can clone and run the app in under 15 minutes
- [ ] `[MANUAL]` Architecture Decision Records (ADRs) written for all major technical choices — `/docs/adr/001-clean-architecture.md`, `002-why-compose.md`, etc.; explains why, not just what
- [x] `[MANUAL]` API documentation auto-generated and hosted — Swagger UI or Redoc; accessible to the mobile team without asking the backend team; updated automatically on deploy
- [x] `[MANUAL]` Environment setup guide covers: exact JDK version, NDK version, emulator config, signing setup — platform-specific gotchas (macOS / Windows / Linux) documented
- [x] `[BLOCKER][MANUAL]` On-call / incident runbook exists at `/docs/runbooks/incident.md` — covers how to read dashboards, escalation path, and rollback steps; tested in a dry run before first production release
- [ ] `[MANUAL]` Component library documented — every reusable Composable has an `@Preview` annotation; optionally published via Showkase or Compose Playground

**Sign-off:** `[ ]` Verified by _______________ Date: _______________

---

## Master Release Gate

## Blocker sweep
```bash
# Must output 0 before any production release
grep -E '\[BLOCKER\].*\[ \]' PRODUCTION_CHECKLIST.md | wc -l
```

### Domain completion matrix

| Domain | Blockers cleared | Overall complete |
|---|---|---|
| 1 — Architecture & code structure | `[ ]` | `[ ]` |
| 2 — CI/CD pipeline | `[ ]` | `[ ]` |
| 3 — Observability & monitoring | `[ ]` | `[ ]` |
| 4 — Code quality & standards | `[ ]` | `[ ]` |
| 5 — API design & contract | `[ ]` | `[ ]` |
| 6 — Accessibility (WCAG 2.1 AA) | `[ ]` | `[ ]` |
| 7 — Data privacy & compliance | `[ ]` | `[ ]` |
| 8 — Error handling & resilience | `[ ]` | `[ ]` |
| 9 — Release management | `[ ]` | `[ ]` |
| 10 — Documentation & onboarding | `[ ]` | `[ ]` |

### Mandatory artefacts checklist

- [ ] `DEPLOYMENT_CHECKLIST.md` — all items complete (infrastructure, stress test, CDN, Cloudflare)
- [ ] `TESTING_CHECKLIST.md` — all 8 testing phases complete, zero blocker items open
- [ ] `PRODUCTION_CHECKLIST.md` — this file; zero blocker items open across all 10 domains
- [ ] `/docs/adr/` — at least one ADR per major technical decision
- [x] `/docs/hotfix-runbook.md` — hotfix process documented and rehearsed
- [x] `/docs/runbooks/incident.md` — incident response runbook dry-run completed
- [ ] `/docs/security-signoff-<version>.md` — OWASP Mobile Top 10 sign-off
- [ ] `/docs/load-test-results-<version>.md` — stress test report
- [ ] `CHANGELOG.md` — current release entry written
- [ ] ProGuard mapping file archived

**Final production approval:** `[ ]` _______________ Date: _______________

---

## The complete 3-file system

| File | Domain | Primary gate |
|---|---|---|
| `DEPLOYMENT_CHECKLIST.md` | Infrastructure, logging, CDN, Cloudflare | Pre-deployment |
| `TESTING_CHECKLIST.md` | All 8 testing phases | Release candidate |
| `PRODUCTION_CHECKLIST.md` | This file — 10 quality domains | Ongoing & every release |

---

*Last updated: 2026-06-04 — review and update at the start of every release cycle and whenever a new domain is added to the app (e.g. payments, health data, children's content).*