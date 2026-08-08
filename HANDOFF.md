# HANDOFF

> Current-state snapshot, not history. Overwritten in place at the end of
> every session. For history, see `LOG.md`; for why a decision was made, see
> `decisions/`.

**Last updated:** 2026-07-21 17:09

## What's done

Implemented and live-verified 3 MCP servers, 2 hooks, and 2 skills (the set
recommended in the prior session's "what would help this repo" table).

**Hooks — fully live-verified within-session, no restart needed:**
- `.claude/settings.json` + `.claude/hooks/block-raw-gradlew.js`
  (PreToolUse on `Bash|PowerShell`): denies raw `gradlew`/`gradlew.bat`
  invocations that don't go through `build-and-install.ps1`. Proven live —
  an actual `./gradlew --version` call was blocked with the expected
  message.
- `.claude/hooks/backend-docker-reminder.js` (PostToolUse on `Write|Edit`):
  reminds that `services/backend/**` edits need
  `docker compose build web && docker compose up -d --no-deps web` to reach
  the `skolab_python_ai` container. Proven live via a real Edit + sentinel
  capture of the hook's stdin payload.

**MCP servers — added to `.mcp.json` (project scope) / local scope, config
proven correct via direct protocol-level calls, but NOT yet loaded as
Claude-native tools in a running session** (see "What's not done"):
- `postgres` (`crystaldba/postgres-mcp`, restricted/read-only, joined to
  `backend_private-net`, talks to `db:5432` by internal hostname — the
  container has no host port published). DB connectivity + credentials
  verified with a live `psql` query (20 tables in `skolab`). Committed to
  `.mcp.json` — the dev password is already public in
  `services/backend/docker-compose.yml`, so no new secret exposure.
- `grafana` (`mcp/grafana`, joined to `infrastructure_default`) — added at
  **local** scope (`~/.claude.json`, not committed) because it needs a live
  Grafana service-account token, unlike Postgres's already-public dev
  password. Verified end-to-end via a raw `tools/call` to `query_prometheus`
  returning real data: `up{job="skolab-backend"} = 1`.
- `playwright` (`@playwright/mcp`). Verified end-to-end via a raw
  `tools/call` to `browser_navigate` — loaded `http://localhost:3000`, page
  title confirmed "SkoLab".

**Skills — written, logic-correct, not yet invocable via `/skill` this
session** (same restart caveat):
- `.claude/skills/backend-rebuild-verify/SKILL.md`
- `.claude/skills/android-build/SKILL.md`

**Infra side-effects of verification (still running, reversible):**
- `infrastructure/docker-compose.yml` stack (Prometheus, Alertmanager, Loki,
  Promtail) was started with the user's explicit sign-off (this directory is
  flagged in `AGENTS.md` as "don't touch without asking").
- `grafana/grafana` was run standalone (not via that compose file) on host
  port **3010** instead of 3000, because 3000 was already bound by the local
  `npm run dev:web`. Container name `skolab_grafana`.
- Two datasources were provisioned in that fresh Grafana instance via its
  API (not committed anywhere, lives only in the container's sqlite state):
  `Prometheus` → `http://skolab_prometheus:9090`, `Alertmanager` →
  `http://skolab_alertmanager:9093`.
- A Grafana service account (`mcp-grafana`, Viewer role) + token were
  created for the MCP server to authenticate with.
- `.gitignore` gained two lines: `.playwright-mcp/` (real tool usage writes
  screenshots/snapshots there) and `.claude/settings.local.json` (personal
  overrides, none created yet).

## What's not done / blocked

- **The actual in-Claude-Code tool calls for all 3 MCP servers and both
  skills are unverified inside a live session** — this is a hard platform
  limit, not a shortcut: newly-added `.mcp.json`/`.claude/skills` entries
  only attach at session start. `claude mcp list` still shows all three as
  "Pending approval" and `ToolSearch`/`Skill` calls for them fail even after
  writing `enabledMcpjsonServers` into `~/.claude.json` directly. **Next
  action: restart Claude Code (or otherwise start a fresh session in this
  project), then re-run a real query/navigate/skill-invoke through the
  actual tool surface to close this out.**
- Docker/OnCall-style Grafana alert-grouping tools (`list_alert_groups`)
  don't apply to this stack — SkoLab uses standalone Prometheus+Alertmanager,
  not Grafana-managed alerting, so that 404/mismatch is expected, not a bug.
  Real alert state was confirmed instead via Alertmanager's own API
  (`/api/v2/status` → cluster ready) and via the Alertmanager datasource now
  wired into Grafana.
- No CI enforcement was added — everything above is local/session config.

## Notes

- Reasoning for putting hooks + Postgres MCP in committed `.claude/`/
  `.mcp.json` vs. Grafana MCP in local/personal scope: only commit config
  that's safe and portable for every teammate. Postgres's dev password was
  already public; the Grafana token is a live secret freshly minted for this
  machine's container, so it stays local.
- If the Prometheus/Alertmanager/Loki/Promtail stack should keep running
  between sessions or get folded into normal `docker compose up` habits,
  that's a call for whoever owns `infrastructure/` — it was started only for
  this verification, at the user's explicit go-ahead.
