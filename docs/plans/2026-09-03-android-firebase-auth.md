# Android Firebase-token attach + peers hard-auth flip (Stream C)

**Slug:** android-firebase-auth
**Worktree:** `../SkoLab-wt/android-auth` · branch `feat/android-firebase-auth` · base `bd4e04d`
**Risk:** MEDIUM — first authenticated request path in the Android app; a gateway auth flip gated on adoption.

## Context

Phase 0b tail from `decisions/0008`. The Android app has `AuthManager`
(`com.company.skolab.di.AppDependencies.authManager`, `currentUser:
FirebaseUser?`) but **attaches no `Authorization` header to any gateway call** —
every screen builds `Request.Builder().url(url)…` raw, no interceptor. The
`/recommendations/peers*` endpoints (now on the Go gateway) run under transitional
`VerifyUserOptional`; they flip to hard `VerifyUser` once a token-bearing client
build has shipped and been adopted.

## Six fields

**Goal:** Attach a fresh Firebase ID token to the Android gateway calls that hit
authenticated routes (starting with the 3 `/recommendations/peers*` call sites),
then flip the Go recommendations group from `VerifyUserOptional` to
`VerifyUser`.

**Constraints:**
- One place to add the header — an OkHttp `Interceptor` on a shared client, not
  a header line copy-pasted into 6 call sites.
- Token fetched fresh per request via `FirebaseUser.getIdToken(false)` (cached by
  the SDK, refreshed near expiry); never store it.
- Guest / signed-out users: no header, request proceeds (routes stay optional
  until the flip; after the flip, guest peers-autocomplete is expected to 401 —
  confirm that is acceptable product behaviour before Task 3).
- The gateway flip (Task 3) is a **separate PR**, merged only after the client
  build is live and telemetry (`slog` "tokenless" count on the recommendations
  group) has fallen.
- No change to `services/backend` (Python).

**Input:**
- `apps/android-app/.../auth/AuthManager.kt` (`currentUser`, Firebase wiring).
- `apps/android-app/.../network/ServerLocator.kt` (`baseUrl`).
- `apps/android-app/.../ui/screens/CreateProjectScreen.kt` (peers calls at
  ~`:264`, `:402`, `:806`, `:1064`, `:1101`), `ExternalInviteScreen.kt`
  (`:68`, `:227`), `InviteMemberScreen.kt` (`:109`).
- `services/backend-go/main.go` — the `recAPI` group (`auth.VerifyUserOptional()`).
- `services/backend-go/internal/auth/firebase.go` — `VerifyUser` / `VerifyUserOptional`.

**Output:**
- `apps/android-app/.../network/AuthInterceptor.kt` — OkHttp `Interceptor`;
  if `authManager.currentUser != null`, `runBlocking`/await
  `getIdToken(false)` and add `Authorization: Bearer <token>` for requests
  whose host matches `ServerLocator.baseUrl`.
- `apps/android-app/.../network/GatewayClient.kt` — a single shared
  `OkHttpClient` with the interceptor + the existing timeouts; the 3 screens
  use it instead of `OkHttpClient()` / ad-hoc builders for peers calls.
- **Task 3 (separate PR):** `main.go` `recAPI.Use(auth.VerifyUserOptional(), …)`
  → `auth.VerifyUser()`; update `decisions/0008` (flip done) and
  `docs/backend-auth-posture.md`.

**Done Checks:**
- `cd apps/android-app && ./gradlew :app:assembleDevDebug` succeeds (via the
  `android-build` skill / the raw-`gradlew` hook — do not call `gradlew`
  directly per `AGENTS.md`).
- `./gradlew :app:lintDevDebug` no new errors.
- Manual (emulator, signed-in user): a peers-autocomplete call carries
  `Authorization: Bearer …` (verify via gateway `slog` or a proxy).
- Task 3: `cd services/backend-go && go test ./...` green; a tokenless
  `GET /api/v1/recommendations/peers` returns 401.

**Out of Scope:**
- Migrating every other Android gateway call to the interceptor — do the peers
  screens now; a follow-up sweeps the rest (`orbit_metrics`, `leaderboard`,
  quests, profile sync — some already 401 silently and are separately broken).
- Any Python change.
- iOS (no app in this repo).

## Tasks

### Task 1: `AuthInterceptor` + `GatewayClient`
- New interceptor + shared client in `network/`. Header added only for
  `ServerLocator.baseUrl` host; skipped when `currentUser == null`.
- **Verify:** `./gradlew :app:assembleDevDebug` (via skill) compiles.

### Task 2: Route the 3 peers screens through `GatewayClient`
- Replace the ad-hoc `OkHttpClient()` / `OkHttpClient.Builder()` at the peers
  call sites in the 3 screens with `GatewayClient.instance`. Keep the per-call
  timeouts (move them onto the shared client or a `newBuilder()` per call).
- **Verify:** build + lint; emulator smoke shows the header on a peers call.

### Task 3 (separate PR, adoption-gated): flip the gateway to hard auth
- `main.go`: `recAPI.Use(auth.VerifyUser(), recRL.Limit())`.
- Update `decisions/0008` + `docs/backend-auth-posture.md` (transitional →
  enforced).
- **Verify:** `go test ./...`; tokenless peers request → 401; a signed-in app
  build still works.

## Merge coordination

Touches only `apps/android-app/**` (Tasks 1–2) and `services/backend-go/main.go`
+ 2 docs (Task 3). **Fully conflict-free with A and B.** Task 3 waits on a
shipped, adopted client build — do not merge it in the same window as Tasks 1–2.
