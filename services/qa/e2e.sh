#!/usr/bin/env bash
# Run the end-to-end QA scenarios (services/qa/hurl) against an API.
#
#   bash services/qa/e2e.sh <base-url> <report-dir>
#
# Creates temporary verified test users, runs every Hurl file in order, and
# always deletes the users again (through the API's own account deletion),
# pass or fail. Needs FIREBASE_SERVICE_ACCOUNT and SKOLAB_FIREBASE_API_KEY.
set -uo pipefail
base="${1:?base URL}"
reports="${2:-qa-reports}"
here="$(cd "$(dirname "$0")" && pwd)"
work="$(mktemp -d)"
mkdir -p "$reports"

cleanup() {
    python "$here/users.py" cleanup --base-url "$base" --state "$work/state.json" || true
    rm -rf "$work"
}
trap cleanup EXIT

python "$here/users.py" create --base-url "$base" --out "$work/qa.env" --state "$work/state.json" || exit 1

# Files run in name order on one connection: 06 deletes an account.
# --delay paces requests (about 13/s) under the API's real per-IP and
# per-user rate limits, which production keeps.
hurl --test --jobs 1 --color --delay 75 \
    --variables-file "$work/qa.env" \
    --report-junit "$reports/hurl-junit.xml" \
    --report-html "$reports/hurl-html" \
    "$here"/hurl/*.hurl
