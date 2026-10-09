# Shipping a feature

How a feature goes from idea to production without disturbing what already
works. Nothing here runs locally; every check is a GitHub workflow.

1. **One short-lived branch per feature**, opened as a pull request into
   `master` early (as a draft).
2. **The regression gate runs on every push to the pull request.** The
   existing suites guard the rest of the product and are not edited to make
   a feature pass:
   - `SkoLab backend CI`: Go and Python tests; the exact production image
     booted on a fresh Postgres with real Firebase sign-in, then the release
     smoke checks, the multi-user Hurl scenarios, the WebSocket journey,
     OpenAPI contract fuzzing and a backup/restore drill.
   - `web`: lint, type-check, unit tests with a coverage floor, the
     production build, and browser tests (journeys, keyboard, phone layout,
     WCAG 2.2 AA) under the production security headers.
   - Security scans (secrets, Semgrep, Trivy on both images).
   - The feature's own tests, added in the same pull request.
3. **A separate preview deployment.** Add the `preview` label to the pull
   request (or, from `master`, Actions > Preview > Run workflow and pick the
   branch). `.github/workflows/preview.yml` starts that branch's whole stack
   on a GitHub runner: the production API image, an empty Postgres and the
   web app built from the branch, behind one temporary
   `https://*.trycloudflare.com` link in the run summary. It never touches
   production data, Render or the production site, and it is thrown away
   after the chosen minutes (60 by default). Sign in with email and
   password; Google sign-in only works on domains listed in Firebase.
4. **Merge only on Vikas's go-ahead**, once every check is green and the
   preview has been tried. A merge to `master` deploys: `release.yml`
   deploys that exact commit, smoke-tests production and rolls back on its
   own if the smoke test fails.
5. **Tag a checkpoint** for each shipped feature (Actions > Create
   checkpoint; see `CHECKPOINTS.md`), so there is a named state to fall back
   to.

A feature that has to merge before it is finished ships behind a flag that
keeps it hidden in production; none is needed while each feature merges
complete.
