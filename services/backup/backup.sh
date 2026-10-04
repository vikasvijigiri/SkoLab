#!/usr/bin/env bash
# Back up SkoLab's application data (the public schema; identities live in
# Firebase), encrypt it, and prove the encrypted copy restores before keeping it.
#
#   backup.sh OUT_DIR
#
# DATABASE_URL       production, read only (any SQLAlchemy +asyncpg form works)
# BACKUP_PASSPHRASE  symmetric key for the archive; keep a copy outside GitHub
# RESTORE_URL        an empty throwaway local Postgres with pgvector, for the drill
#
# Prints no row counts or data: this repository's Actions logs are public.
set -euo pipefail
umask 077
: "${DATABASE_URL:?}" "${BACKUP_PASSPHRASE:?}" "${RESTORE_URL:?}"
out=${1:?usage: backup.sh OUT_DIR}
image=${PG_CLIENT_IMAGE:-postgres:18}
versions="$(cd "$(dirname "$0")/../backend/alembic/versions" && pwd)"

case "$RESTORE_URL" in
  postgresql://*@127.0.0.1:*/*|postgresql://*@localhost:*/*) ;;
  *) echo "RESTORE_URL must be a local throwaway database" >&2; exit 2 ;;
esac
source_url=${DATABASE_URL/#postgresql+asyncpg:/postgresql:}
source_url=${source_url/#postgres+asyncpg:/postgresql:}

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
mkdir -p "$out" "$work/check"

# run URL TOOL [ARGS...]: a Postgres 18 client tool. The URL travels by
# environment, never argv, so it stays out of process listings.
run() {
  local url=$1 tool=$2
  shift 2
  docker run --rm -i --network host -e PGURL="$url" -e PGCONNECT_TIMEOUT=15 \
    -v "$work:/work" "$image" sh -c 'tool=$1; shift; exec "$tool" -d "$PGURL" "$@"' sh "$tool" "$@"
}

# counts URL: "table rows" for every public table, by name.
counts() {
  run "$1" psql -qAtX -v ON_ERROR_STOP=1 -F ' ' -c "
    SELECT c.relname,
           (xpath('/row/n/text()', query_to_xml(format('SELECT count(*) AS n FROM public.%I', c.relname), false, true, '')))[1]::text
    FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
    WHERE n.nspname = 'public' AND c.relkind IN ('r', 'p')
    ORDER BY 1"
}

passphrase() { printf '%s' "$BACKUP_PASSPHRASE"; }

stamp=$(date -u +%Y%m%dT%H%M%SZ)
name="skolab-db-$stamp"

# ── Dump ────────────────────────────────────────────────────────────────────
counts "$source_url" > "$work/before"
run "$source_url" pg_dump --schema=public --extension=vector --format=custom \
  --no-owner --no-acl -f /work/skolab.dump
counts "$source_url" > "$work/after"
server=$(run "$source_url" psql -qAtX -c "SHOW server_version")
revision=$(run "$source_url" psql -qAtX -c "SELECT version_num FROM public.alembic_version")
dump_sha=$(sha256sum "$work/skolab.dump" | cut -d' ' -f1)
tables=$(wc -l < "$work/before")
cat > "$work/manifest.json" <<EOF
{"created_utc": "$stamp", "server_version": "$server", "alembic_revision": "$revision",
 "tables": $tables, "dump_sha256": "$dump_sha", "format": "pg_dump custom, schema public"}
EOF
cp "$work/before" "$work/row_counts.txt"

# ── Encrypt (AES-256; the bundle never leaves the runner unencrypted) ──────
tar -C "$work" -cf "$work/bundle.tar" skolab.dump manifest.json row_counts.txt
gpg --batch --yes --quiet --pinentry-mode loopback --passphrase-fd 3 \
  --symmetric --cipher-algo AES256 --s2k-digest-algo SHA512 --s2k-mode 3 \
  --s2k-count 65011712 --compress-algo none \
  -o "$out/$name.tar.gpg" "$work/bundle.tar" 3< <(passphrase)
(cd "$out" && sha256sum "$name.tar.gpg" > "$name.tar.gpg.sha256")

# ── Restore drill from the encrypted copy, exactly as a recovery would ─────
gpg --batch --quiet --pinentry-mode loopback --passphrase-fd 3 \
  -d -o "$work/check.tar" "$out/$name.tar.gpg" 3< <(passphrase)
tar -C "$work/check" -xf "$work/check.tar"
[ "$(sha256sum "$work/check/skolab.dump" | cut -d' ' -f1)" = "$dump_sha" ] \
  || { echo "::error::Decrypted dump does not match the original"; exit 1; }

# Supabase places extensions in their own schema and may grant to its roles.
run "$RESTORE_URL" psql -qX -v ON_ERROR_STOP=1 \
  -c "CREATE SCHEMA IF NOT EXISTS extensions" \
  -c "DO \$\$ DECLARE r text; BEGIN
        FOREACH r IN ARRAY ARRAY['anon', 'authenticated', 'service_role'] LOOP
          IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = r) THEN
            EXECUTE format('CREATE ROLE %I NOLOGIN', r);
          END IF;
        END LOOP; END \$\$"
# Every database already has a public schema: restore everything but that.
docker run --rm -v "$work:/work" "$image" sh -c   'pg_restore -l /work/check/skolab.dump | grep -v " SCHEMA - public " > /work/check/toc'
started=$(date +%s)
run "$RESTORE_URL" pg_restore --exit-on-error --no-owner --no-acl -L /work/check/toc /work/check/skolab.dump
restore_seconds=$(( $(date +%s) - started ))
counts "$RESTORE_URL" > "$work/restored"

# Same tables always; same rows unless production changed during the dump.
cmp -s <(cut -d' ' -f1 "$work/before") <(cut -d' ' -f1 "$work/restored") \
  || { echo "::error::Restored tables differ from production"; exit 1; }
if cmp -s "$work/before" "$work/restored"; then
  rows="exact row counts in all $tables tables"
elif ! cmp -s "$work/before" "$work/after"; then
  rows="row counts not compared exactly: production was written to during the dump"
  echo "::warning::$rows"
else
  echo "::error::Restored row counts differ from production"
  exit 1
fi

restored_revision=$(run "$RESTORE_URL" psql -qAtX -c "SELECT version_num FROM public.alembic_version")
[ "$restored_revision" = "$revision" ] \
  || { echo "::error::Restored schema revision differs"; exit 1; }
head=$(python3 - "$versions" <<'PY'
import pathlib, re, sys
revs, downs = set(), set()
for f in pathlib.Path(sys.argv[1]).glob("*.py"):
    text = f.read_text(encoding="utf-8")
    revs |= set(re.findall(r"^revision(?:: str)? = ['\"](\w+)['\"]", text, re.M))
    for d in re.findall(r"^down_revision[^=]*= (.+)$", text, re.M):
        downs |= set(re.findall(r"['\"](\w+)['\"]", d))
print(" ".join(sorted(revs - downs)))
PY
)
[ "$revision" = "$head" ] || echo "::warning::Production schema revision is not this commit's migration head"

{
  echo "### Database backup $name"
  echo "- Encrypted archive: \`$name.tar.gpg\` (AES-256)"
  echo "- Restore drill from the encrypted copy: passed in ${restore_seconds}s"
  echo "- Verified: $rows; schema revision \`$revision\`"
} | tee -a "${GITHUB_STEP_SUMMARY:-/dev/null}"
