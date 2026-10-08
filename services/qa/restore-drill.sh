#!/usr/bin/env bash
# Restore only CI's throwaway Postgres, never a real project's database.
set -euo pipefail
umask 077
source_url="${QA_DATABASE_URL:-postgresql://postgres:skolab@127.0.0.1:5432/skolab}"
case "$source_url" in
  postgresql://postgres:skolab@127.0.0.1:5432/skolab) ;;
  *) echo "Restore drill accepts only CI's loopback Postgres" >&2; exit 2 ;;
esac

work=$(mktemp -d)
target="skolab-restore-drill-$$-${RANDOM}"
created=""
cleanup() {
  [ -z "$created" ] || docker rm -f "$target" >/dev/null 2>&1 || true
  rm -f "$work/backup.dump" "$work/source-counts" "$work/restored-counts"
  rmdir "$work" || true
}
trap cleanup EXIT

docker run --rm --network host postgres:16 pg_dump "$source_url" \
  --format=custom --no-owner --no-acl > "$work/backup.dump"
chmod 0600 "$work/backup.dump"
docker run -d --name "$target" -e POSTGRES_PASSWORD=restore-drill-only \
  -e POSTGRES_DB=skolab postgres:16 >/dev/null
created=yes
for _ in $(seq 1 30); do
  docker exec "$target" pg_isready -U postgres -d skolab >/dev/null 2>&1 && break
  sleep 1
done
# Roles are cluster-wide, so a dump never carries them: the runtime role's
# row-security policies (alembic e1f2a3b4c5d6) need it to exist first.
docker exec "$target" psql -U postgres -d skolab -qX -v ON_ERROR_STOP=1 \
  -c "CREATE ROLE skolab_app NOLOGIN NOINHERIT"
docker exec -i "$target" pg_restore -U postgres -d skolab \
  --exit-on-error --no-owner --no-acl < "$work/backup.dump"

# Compare restored content, not just whether pg_restore returned success.
for table in users workspaces workspace_members; do
  sql="SELECT count(*), coalesce(md5(string_agg(row_to_json(t)::text, E'\\n' ORDER BY row_to_json(t)::text)), '') FROM $table t"
  docker run --rm --network host postgres:16 psql "$source_url" -Atc "$sql" >> "$work/source-counts"
  docker exec "$target" psql -U postgres -d skolab -Atc "$sql" >> "$work/restored-counts"
done
cmp "$work/source-counts" "$work/restored-counts"
echo "Restore drill passed: isolated restore and application-content comparison"
