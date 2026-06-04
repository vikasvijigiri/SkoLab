# Android App — Professional Testing Phases Checklist

> **Purpose:** This file defines all testing gates required before a production release.
> Copilot: before any release-related task, scan this file and report which phases have open items.
> A release is only approved when every `[block]` item shows `[x]`, and overall completion is ≥ 95%.

---

## How to use this file

- Mark each item `[x]` as you complete it.
- Count remaining blockers:
  ```bash
  grep -c 'BLOCKER.*\[ \]' TESTING_CHECKLIST.md
  ```
  Zero blocker output = cleared for release candidate.
- Count all open items:
  ```bash
  grep -c '\[ \]' TESTING_CHECKLIST.md
  ```
- Tags per item: `[AUTO]` = automated/scriptable, `[MANUAL]` = requires human, `[CI]` = must pass in CI pipeline, `[BLOCKER]` = release halts if incomplete.

---

## Phase 1 — Unit Testing
> Pure logic, no Android framework dependency.

> **Copilot:** Check all files under `*/viewmodel/`, `*/usecase/`, `*/repository/`, `*/util/` for a
> corresponding test file in `src/test/`. Flag any class with no test counterpart. Verify
> `TestCoroutineDispatcher` is used wherever `Dispatchers.IO` or `Dispatchers.Default` appear in
> production code. Run `:testDebugUnitTest` and confirm coverage ≥ 80%.

- [ ] `[AUTO][CI]` All business logic classes have ≥ 80% line coverage — JUnit5 + MockK via `:testDebugUnitTest`
- [ ] `[AUTO][CI]` ViewModel state transitions fully tested — every StateFlow/LiveData emission path including error and loading states (use Turbine for Flow assertions)
- [ ] `[AUTO][CI]` Repository layer tested with mocked data sources — verify DTO → domain model mapping and error propagation
- [ ] `[AUTO][CI]` Use-case / interactor classes tested in isolation — zero Android context dependency
- [ ] `[AUTO]` Utility and extension functions tested with edge cases — null inputs, empty strings, boundary integers, locale-sensitive formatting
- [ ] `[AUTO][CI]` All coroutine flows tested with `TestCoroutineDispatcher` — verify cancellation and exception propagation

**Coverage report path:** `/build/reports/coverage/test/debug/`

**Sign-off:** `[ ]` Verified by _______________ Date: _______________

---

## Phase 2 — Integration Testing
> Component boundaries, real Android framework, no UI.

> **Copilot:** Check for a `MockWebServer` setup in `src/androidTest/` or `src/test/`. Verify
> `Room.inMemoryDatabaseBuilder` is used in DAO tests — never a real file-backed DB.
> Confirm `HiltAndroidRule` or `KoinTestRule` is present in integration test base classes.
> Flag any test that accesses the real network.

- [ ] `[AUTO][CI][BLOCKER]` Room DAO queries tested against in-memory database — all queries, transactions, and migrations covered
- [ ] `[AUTO][CI]` Retrofit API client tested against `MockWebServer` — test 200 / 400 / 500 / timeout per endpoint; verify retry logic and error mapping
- [ ] `[AUTO]` WorkManager tasks tested with `TestWorkerFactory` — verify constraints, output data, retry policy, and chaining
- [ ] `[AUTO]` DataStore / SharedPreferences read-write cycle tested — write → read → verify; test migration if applicable
- [ ] `[AUTO][CI][BLOCKER]` DI graph verified (Hilt `HiltAndroidRule` / Koin `KoinTestRule`) — no missing bindings at test startup
- [ ] `[AUTO]` FCM token registration and message handling tested — simulate token refresh and foreground / background message receipt

**Sign-off:** `[ ]` Verified by _______________ Date: _______________

---

## Phase 3 — UI Testing (Espresso / Compose)
> Screen flows and real device behaviour.

> **Copilot:** Check `src/androidTest/` for Espresso or Compose UI test files covering each screen
> in the NavGraph. Verify `AccessibilityChecks.enable()` is called in the test setup class.
> Check for Paparazzi or Showkase snapshot tests. Flag any screen that has no corresponding
> UI test file.

- [ ] `[AUTO][CI][BLOCKER]` Critical user journeys automated end-to-end — sign up, onboarding, core feature flow, logout; must pass on phone and tablet
- [ ] `[AUTO][CI]` All NavGraph destinations reachable in tests — deep link URIs open correct screen with correct arguments
- [ ] `[AUTO]` Form validation feedback verified — empty submit, invalid email, password mismatch; error text appears and CTA is disabled
- [ ] `[AUTO]` RecyclerView / LazyColumn pagination tested — scroll to bottom triggers next page; empty state and error state render correctly
- [ ] `[AUTO][CI][BLOCKER]` Accessibility checks via `AccessibilityChecks.enable()` — no missing content descriptions, no touch targets < 48dp
- [ ] `[AUTO][CI]` Dark mode / light mode snapshot tests — Paparazzi or Showkase diff alerts on unintended visual regressions
- [ ] `[MANUAL]` RTL layout verified (if targeting Arabic / Hebrew locales) — no clipped text, no incorrectly mirrored icons

**Sign-off:** `[ ]` Verified by _______________ Date: _______________

---

## Phase 4 — Device & Compatibility Testing
> Real hardware matrix.

> **Copilot:** Check the Firebase Test Lab configuration (`firebase.json` or CI workflow) for a
> device matrix covering at minimum: API 24, API 35, a small phone (360dp), a large phone (411dp),
> and a tablet (600dp+). Flag if the matrix has fewer than 5 device/API combinations.

- [ ] `[AUTO][BLOCKER]` Tested on minimum SDK target (API 24 / Android 7) — full UI suite; no missing API calls or backcompat crashes
- [ ] `[MANUAL][BLOCKER]` Tested on latest Android release (API 35) — verify notification permissions, photo picker, predictive back gesture behavior
- [ ] `[AUTO]` Screen size matrix covered: 360dp / 411dp / 600dp / 840dp — via Firebase Test Lab device matrix
- [ ] `[MANUAL]` Manufacturer skin variants tested: Samsung One UI, Xiaomi MIUI, OnePlus OxygenOS — focus on permission dialogs and background killing
- [ ] `[MANUAL][BLOCKER]` App behavior verified after OS kills process — background 30 min on low-RAM device; state restored, no data loss, no crash
- [ ] `[MANUAL]` Foldable / split-screen continuity tested — fold / unfold mid-flow; layout adapts cleanly, no crash
- [ ] `[AUTO][CI]` Firebase Test Lab robo test run on release candidate — 5+ real devices; crawl report reviewed for crashes and ANRs

**Sign-off:** `[ ]` Verified by _______________ Date: _______________

---

## Phase 5 — Performance Testing
> Speed, memory, battery, and binary size.

> **Copilot:** Check `build.gradle` for `minifyEnabled true` and `shrinkResources true` on the
> release build type. Verify LeakCanary is in `debugImplementation` only (never release).
> Check for a Macrobenchmark module and Baseline Profile generation task. Flag if APK/AAB size
> exceeds 20 MB base.

- [ ] `[AUTO][BLOCKER]` App cold start time < 2 s on mid-range device — measured with `adb shell am start -W`; profiled with Android Profiler
- [ ] `[AUTO][BLOCKER]` Zero memory leaks detected by LeakCanary across all critical user flows in debug build
- [ ] `[MANUAL]` Frame rate ≥ 60 fps on all scrollable screens — Systrace / Perfetto; no janky frames > 16 ms on RecyclerView / LazyColumn scroll
- [ ] `[AUTO][CI]` APK / AAB size within target (< 20 MB base) — R8 full-mode and resource shrinking enabled; large assets audited
- [ ] `[MANUAL]` No unnecessary wakelocks held in background — `adb shell dumpsys batterystats` reviewed
- [ ] `[MANUAL]` All API response images use WebP + compression — verified via Charles Proxy or OkHttp logging interceptor
- [ ] `[AUTO]` Baseline Profiles generated and bundled in release build — Macrobenchmark + profileinstaller; targets 20–40% cold start improvement

**Sign-off:** `[ ]` Verified by _______________ Date: _______________

---

## Phase 6 — Security Testing
> Data, auth, network, and binary hardening.

> **Copilot:** Check `AndroidManifest.xml` for `android:debuggable="true"` — this must NOT appear
> in any release variant manifest. Scan all files for `Log.d`, `Log.v`, `println` calls not
> gated by `BuildConfig.DEBUG`. Check `network_security_config.xml` for `<pin-set>` on all
> production domains. Flag any `SharedPreferences` storing keys with names containing
> "token", "key", "secret", or "password".

- [ ] `[AUTO][CI][BLOCKER]` No sensitive data (tokens, email, password) in logcat in release build — all debug logs gated by `BuildConfig.DEBUG`
- [ ] `[MANUAL][BLOCKER]` Auth tokens stored in `EncryptedSharedPreferences` or Android Keystore — never in plain SharedPreferences or unencrypted SQLite
- [ ] `[MANUAL][BLOCKER]` Certificate pinning enabled for all production API hosts — `CertificatePinner` or `network_security_config.xml`; pinning rejection tested
- [ ] `[AUTO][CI][BLOCKER]` Release build is not debuggable — `android:debuggable=false` verified with `aapt dump badging`
- [ ] `[MANUAL][BLOCKER]` All exported components audited — no `exported="true"` without explicit justification; reviewed with `apkanalyzer`
- [ ] `[MANUAL]` Deep link URI parameters sanitised — fuzz with unexpected values, path traversal, encoded characters; no open redirect
- [ ] `[MANUAL]` ProGuard / R8 obfuscation verified — release APK decompiled with jadx; class and method names are obfuscated
- [ ] `[MANUAL][BLOCKER]` OWASP Mobile Top 10 checklist signed off — https://owasp.org/www-project-mobile-top-10

**Sign-off:** `[ ]` Verified by _______________ Date: _______________

---

## Phase 7 — UAT & Beta Testing
> Real users, real conditions.

> **Copilot:** Check Play Console for an active closed testing track with ≥ 50 testers. Verify a
> feature flag / remote config system is in place for staged rollout. Check Analytics for
> an onboarding funnel event sequence. Flag if crash-free rate in closed beta is below 99.5%.

- [ ] `[MANUAL]` Internal alpha completed — all team members ran structured test script (sign up → core feature → notification → logout) on personal devices
- [ ] `[MANUAL][BLOCKER]` Closed beta run for ≥ 2 weeks with 50–200 users from target segment — crash-free rate ≥ 99.5%
- [ ] `[AUTO][BLOCKER]` Play Console pre-launch report reviewed — zero crashes on Google's automated device suite; accessibility warnings triaged
- [ ] `[MANUAL]` Feature flag / staged rollout plan defined — 10% → 25% → 50% → 100%; kill switch tested in staging
- [ ] `[MANUAL]` Onboarding funnel completion rate measured in beta — target > 70% of new installs complete onboarding
- [ ] `[MANUAL]` In-app feedback channel active and monitored daily during beta

**Sign-off:** `[ ]` Verified by _______________ Date: _______________

---

## Phase 8 — Release & Production Monitoring
> Post-launch gates and ongoing health.

> **Copilot:** Verify the CI release workflow includes a step that uploads the ProGuard mapping
> file to Firebase Crashlytics. Check that Android Vitals alerts are configured in Play Console.
> Confirm a rollback runbook document exists at `/docs/rollback-runbook.md`.

- [ ] `[AUTO][BLOCKER]` Crash-free rate ≥ 99.5% in Firebase Crashlytics before full rollout — monitored for 24 h after each rollout increment
- [ ] `[AUTO][BLOCKER]` ANR rate < 0.1% in Play Console Android Vitals — spike triggers immediate investigation before wider rollout
- [ ] `[AUTO][BLOCKER]` API p99 latency < 1.5 s in production dashboards — CloudWatch / Datadog alarm active; on-call paged if exceeded for 5 min
- [ ] `[MANUAL]` Play rating monitoring alert configured — 1-star review spike > 3× baseline within 24 h triggers review
- [ ] `[MANUAL][BLOCKER]` Rollback plan documented and rehearsed — steps to halt rollout and push hotfix are in `/docs/rollback-runbook.md`
- [ ] `[AUTO][CI][BLOCKER]` ProGuard mapping file uploaded to Crashlytics in CI release workflow — without this, stack traces are unreadable
- [ ] `[MANUAL]` Store listing release notes written — plain language, user-benefit framing, localised for primary markets

**Sign-off:** `[ ]` Verified by _______________ Date: _______________

---

## Final Release Gate

### Blocker sweep (run before every release candidate)
```bash
# Count open blockers — must be 0
grep -E 'BLOCKER.*\[ \]' TESTING_CHECKLIST.md | wc -l

# Count all open items — target 0, hard minimum < 5% of total
grep -c '\[ \]' TESTING_CHECKLIST.md
```

### Release approval matrix

| Phase | Blockers cleared | Overall complete |
|---|---|---|
| Phase 1 — Unit testing | `[ ]` | `[ ]` |
| Phase 2 — Integration testing | `[ ]` | `[ ]` |
| Phase 3 — UI testing | `[ ]` | `[ ]` |
| Phase 4 — Device compatibility | `[ ]` | `[ ]` |
| Phase 5 — Performance | `[ ]` | `[ ]` |
| Phase 6 — Security | `[ ]` | `[ ]` |
| Phase 7 — UAT & beta | `[ ]` | `[ ]` |
| Phase 8 — Release monitoring | `[ ]` | `[ ]` |

### Mandatory artifacts before release

- [ ] Coverage report committed to `/docs/test-coverage-<version>.html`
- [ ] Load test results saved to `/docs/load-test-results-<version>.md`
- [ ] Security sign-off doc saved to `/docs/security-signoff-<version>.md`
- [ ] Rollback runbook exists at `/docs/rollback-runbook.md`
- [ ] ProGuard mapping file archived at `/docs/mapping-<version>.txt`

**Final release approval:** `[ ]` _______________ Date: _______________

---

## Quick reference — testing tools

| Layer | Tool | Command |
|---|---|---|
| Unit tests | JUnit5 + MockK + Turbine | `./gradlew testDebugUnitTest` |
| Coverage | JaCoCo | `./gradlew jacocoTestReport` |
| Integration — DB | Room in-memory | `./gradlew connectedDebugAndroidTest` |
| Integration — API | OkHttp MockWebServer | (runs in unit test phase) |
| UI — Espresso | Espresso + Compose test | `./gradlew connectedDebugAndroidTest` |
| UI — Snapshots | Paparazzi | `./gradlew recordPaparazziDebug` / `verifyPaparazziDebug` |
| Accessibility | Espresso AccessibilityChecks | Enabled in test base class |
| Device matrix | Firebase Test Lab | `gcloud firebase test android run` |
| Performance | Android Profiler, Macrobenchmark | Studio + `./gradlew :benchmark:connectedBenchmarkAndroidTest` |
| Memory leaks | LeakCanary | `debugImplementation` only; runs automatically |
| Security — static | apkanalyzer, jadx | `apkanalyzer manifest print app-release.apk` |
| Load testing | k6 | `k6 run tests/load/baseline.js` |

---

*Last updated: 2026-06-04 — update this file at the start of every release cycle.*