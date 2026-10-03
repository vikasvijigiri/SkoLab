#!/usr/bin/env bash
# Start and supervise skolab-api's two processes (this script is PID 1).
#
#  1. Migrate the schema. A failed migration fails the deploy instead of
#     serving an image that does not match the database.
#  2. Start Python on 127.0.0.1:8000 (never public) and wait until it is
#     live, so the gateway never takes traffic it cannot forward.
#  3. Start the gateway on :8080, the only public port.
#
# If either process exits, stop the other and exit with its status: Render
# then restarts the whole container. SIGTERM (deploy, restart) is passed to
# both so each drains its in-flight requests.
set -uo pipefail
cd /app

python_pid=""
gateway_pid=""
stop() {
    [ -n "$gateway_pid" ] && kill -TERM "$gateway_pid" 2>/dev/null
    [ -n "$python_pid" ] && kill -TERM "$python_pid" 2>/dev/null
    return 0
}
# PID 1 ignores signals it does not handle, so handle them from the start.
trap 'stop; wait; exit 143' TERM INT

python scripts/run_migrations.py || exit 1

OTEL_SERVICE_NAME=skolab-backend-py uvicorn app.main:app \
    --host 127.0.0.1 --port 8000 \
    --workers "${WEB_CONCURRENCY:-1}" --timeout-keep-alive 65 &
python_pid=$!

python_live() {
    python -c 'import urllib.request; urllib.request.urlopen("http://127.0.0.1:8000/livez", timeout=1)' 2>/dev/null
}
started=""
for _ in $(seq 1 120); do
    if python_live; then
        started=yes
        break
    fi
    if ! kill -0 "$python_pid" 2>/dev/null; then
        echo "skolab-entrypoint: python exited during startup" >&2
        exit 1
    fi
    sleep 1
done
if [ -z "$started" ]; then
    echo "skolab-entrypoint: python not live after 120s" >&2
    stop
    exit 1
fi

OTEL_SERVICE_NAME=skolab-gateway skolab-gateway &
gateway_pid=$!

# Returns when either process exits; the TERM trap handles shutdown.
wait -n "$python_pid" "$gateway_pid"
status=$?
echo "skolab-entrypoint: a process exited (status $status); stopping the container" >&2
stop
wait
exit "$status"
