# Database backup and recovery

Supabase's free plan keeps no downloadable backups, so SkoLab keeps its own.

| | |
|---|---|
| What | Application data: every table in the `public` schema, plus the `vector` extension. Sign-in identities live in Firebase, not here. |
| When | Nightly at 02:00 IST (`.github/workflows/backup.yml`), and on demand from the Actions tab. |
| Where | The run's artifact `skolab-db-<run id>`, kept 35 days. |
| Protection | AES-256 (GnuPG symmetric, SHA-512 key derivation) with `BACKUP_PASSPHRASE`. The repository is public, so its artifacts can be downloaded by anyone signed in to GitHub: the archive is useless without the passphrase, and logs show no data or row counts. |
| Proof | Every backup is decrypted and restored into a fresh Postgres 18 in the same run. The run fails unless tables, row counts, and schema revision match production. |

## Targets

- **RPO (data you can lose): 24 hours.** One backup a night. A shorter RPO
  needs point-in-time recovery, which is a paid Supabase feature.
- **RTO (time to recover): 1 hour.** The step summary records how long the
  restore itself took (seconds at today's size). Most of the hour is creating
  the new project and repointing the service.

## Secrets to keep outside GitHub

Keep these in the password manager. Without them a backup is unrecoverable or
its encrypted fields are unreadable:

- `BACKUP_PASSPHRASE`: decrypts the archives.
- `DATABASE_ENCRYPTION_KEY` and `EMAIL_BLIND_INDEX_KEY`: decrypt and look up user fields
  inside the restored data. Keep every key version that encrypted data still
  in a retained backup.

## Recover

Never restore over the production database. Restore into a new Supabase
project, check it, then point the service at it.

1. Pick the run (Actions, **Database backup**) and download its artifact:
   `gh run download <run id> -n skolab-db-<run id>`
2. Check and decrypt (GnuPG prompts for the passphrase):

   ```sh
   sha256sum -c skolab-db-*.tar.gpg.sha256
   gpg -d skolab-db-*.tar.gpg | tar -x    # skolab.dump, manifest.json, row_counts.txt
   ```

3. Create a new Supabase project in Singapore. In its SQL editor run
   `CREATE EXTENSION IF NOT EXISTS vector WITH SCHEMA extensions;`
4. Restore with a Postgres 18 client, using the new project's **session
   pooler** URL (port 5432):

   ```sh
   pg_restore -d "$NEW_DATABASE_URL" --no-owner --no-acl --exit-on-error skolab.dump
   ```

5. Compare `row_counts.txt` with the new project's tables.
6. Set `DATABASE_URL` in the Render `skolab` environment group and the GitHub
   secret of the same name to the new project, then redeploy. `/readyz` must
   report the database healthy, and the release's production QA must pass.

## Limits

- Schedules in a public repository pause after 60 days without commits. GitHub
  emails a warning first; re-enable the workflow from the Actions tab.
- A failed nightly run emails whoever last changed the schedule. Treat that
  email as an incident: there is no fresh backup until a run succeeds.
- Artifacts live in GitHub only. For a second location, download one
  periodically to encrypted storage you control.
