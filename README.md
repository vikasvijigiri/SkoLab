# SkoLab backend

SkoLab's current API supports Firebase identity, CoLab workspaces, invitations,
membership, real-time collaboration, and LaTeX compilation. The public Go
gateway handles authentication, authorization, rate limits, and WebSockets. It
calls an internal FastAPI service for the Python operations it still needs.

| Path | Purpose |
| --- | --- |
| `services/backend-go` | Public Go gateway and WebSocket server |
| `services/backend` | Internal FastAPI service and database migrations |
| `deploy/skolab-api` | Production image and two-process entrypoint |
| `services/release` | Deployment and production smoke checks |
| `services/observability` | OpenTelemetry and Grafana alert definitions |
| `.github/workflows` | CI, staging QA, security scans, and release |
| `render.yaml` | Render deployment blueprint |

Production uses one Render service, `skolab-api`. The gateway listens publicly
on port 8080; Python listens only on `127.0.0.1:8000` inside the same container.
The public base URL is `https://skolab-api.onrender.com`. `/gateway-health`
checks that the gateway process is alive; `/readyz` also checks the database and
Python service. Neither endpoint proves every API operation works.

The API contract is in `services/backend-go/api/openapi.yaml`. Routes under
`/api/v1` are the client-facing API; use a Firebase ID token for protected
requests. The Python routes are internal to this deployment.

## Local development

Install Python 3.12 and the Go version declared in `services/backend-go/go.mod`.
Use `.env.example` as a reference for local configuration; put deployment
secrets in Render's environment and CI secrets in GitHub Actions. Do not commit
credentials or Firebase service-account JSON.

```powershell
make dev-backend
make dev-go
```

## Verification and release

```powershell
make lint-python
make test-python
make test-go
```

CI runs tests, a production-image staging smoke test, API QA, secret scanning,
and SLO rule checks before its release job can deploy a `master` commit.
Secret scanning, Semgrep findings, and fixable HIGH/CRITICAL Trivy findings
block releases. See `services/security/README.md` for the scan scope and
exception policy. Grafana receives application telemetry over OTLP; its synthetic checks
measure external availability separately from internal request metrics.

The current free Render plan runs one instance. It cannot provide instance-level
high availability; moving to redundant instances also requires shared Redis
state and a deployment plan for failover.

`deploy/render-ha.yaml` prepares two API replicas, two private compile workers,
and shared Redis. It is a separate paid profile, awaiting rollout approval.
See `deploy/RELIABILITY.md` for its capacity checks, rollout, recovery, and limits.
