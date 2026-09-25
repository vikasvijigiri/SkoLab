# SkoLab Backend

SkoLab is a backend-only platform repository. It contains a FastAPI service
for domain, AI, enrichment, and data APIs, and a Go gateway for API-edge and
realtime responsibilities.

## Repository map

| Path | Purpose |
| --- | --- |
| `services/backend` | FastAPI service |
| `services/backend-go` | Go gateway |
| `.github/workflows` | Backend CI, security scanning, and uptime monitoring |
| `render.yaml` | Render deployment blueprint |

## Prerequisites

- Python 3.12
- Go version declared in `services/backend-go/go.mod`

## Local development

```powershell
make dev-backend
make dev-go
```

Copy `.env.example` to a local `.env` only when needed. Do not commit
credentials.

## Verification

```powershell
make lint-python
make test-python
make test-go
```
