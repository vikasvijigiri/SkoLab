# Checkpoints

Known-good states of production worth returning to. Each one is an annotated
git tag on `master`, so `git show <tag>` prints its note and the commit.

| Tag | Commit | What was live and verified |
| --- | --- | --- |
| `checkpoint-2026-10-09-login-live` | `9f79aa0` | Web sign-in app live at https://skolab-web.onrender.com with Firebase (email/password and Google). Backend at https://skolab-api.onrender.com with auto-rollback, supply-chain pins and the NUL-byte fix. A real browser sign-in was confirmed end to end in Grafana Cloud traces (05:10:42 UTC: `POST /api/v1/users/profile/sync` 200 after its CORS preflight). |
| `pre-domain-removal-2026-09-27` | `af76a32` | Full-featured backend before it was cut down to auth, authz and CoLab only. |

## Falling back to a checkpoint

Nothing here runs locally; every step is a GitHub or Render action.

1. **API (`skolab-api`)**: Actions > Release > Run workflow, choose the
   checkpoint tag in "Use workflow from". The release checks that CI passed on
   that commit, deploys it to Render, waits until it is live and smoke-tests
   production, rolling back on its own if the smoke test fails.
2. **Web app (`skolab-web`)**: Render dashboard > skolab-web > Events, find
   the deploy of the checkpoint commit and choose Rollback. (It is a static
   site, so this is instant and needs no rebuild.)
3. **Confirm**: Actions > Telemetry lookup > Run workflow for the minutes
   after the rollback, and check that requests answer 200.

Rolling back code does not roll back the database. If a migration ran after
the checkpoint, check that the older code still works with the newer schema
before falling back, or restore the database to a point in time first
(Supabase PITR, see `deploy/RELIABILITY.md`).

To return to the newest code afterwards, run the Release workflow from
`master` again.

## Adding a checkpoint

Tag the merge commit on `master` once its release, smoke test and production
e2e have passed, then add a row above:

    git tag -a checkpoint-YYYY-MM-DD-<what> <commit> -m "<what was live and verified>"
    git push origin checkpoint-YYYY-MM-DD-<what>
