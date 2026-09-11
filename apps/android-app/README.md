# SkoLab Android client

The Android client is a first-class SkoLab application in the repository. It
uses Kotlin, Jetpack Compose, Gradle and the `:app` module.

## Boundaries

```text
ui/            Compose screens, components, layouts and theme
viewmodel/     UI state and event orchestration
repository/    Feature-facing data access
network/       HTTP/WebSocket clients and environment routing
data/          Local preferences and encrypted device state
model/         API and domain models
auth/          Firebase/Credential Manager authentication
analytics/     Consent-aware product telemetry
```

New work should be placed by product capability first. A feature may have a
screen, ViewModel, repository and models close together; shared primitives and
tokens remain in the shared UI/theme layers.

## Build variants

- `devDebug` targets local development services.
- `prodRelease` is the release-like production artifact.
- `prod` and `dev` configuration must not share secrets in source control.

Run from this directory:

```powershell
./gradlew assembleDevDebug
./gradlew test
```

The Android app consumes the same product API and design-token decisions as the
web client, but Gradle remains the authority for Android dependencies and
packaging.
