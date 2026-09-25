# Deploying the SkoLab backend

`render.yaml` is the source of truth for the two production services:

| Service | Runtime | Health check |
|---|---|---|
| `skolab-gateway` | Go | `/gateway-health` |
| `skolab-backend-py` | FastAPI | `/livez` |

## Initial setup

1. In Render, create a Blueprint from this repository and `render.yaml`.
2. Set the `sync: false` values for each service. Both services use the
   Supabase transaction-pooler database URL; Python uses the
   `postgresql+asyncpg://` scheme and Go uses `postgres://`.
3. Set `PYTHON_BACKEND_URL` on `skolab-gateway` to
   `https://skolab-backend-py.onrender.com` after the Python service is live.
4. Verify `https://skolab-gateway.onrender.com/gateway-health` and
   `https://skolab-backend-py.onrender.com/livez` return successful responses.

## Required production secrets

- `DATABASE_URL`
- `DATABASE_ENCRYPTION_KEY` (Python)
- `GROQ_API` (Python)
- `SRE_SECURITY_TOKEN` (Python)
- `INTERNAL_API_TOKEN` (the same value in both services)
- `SENTRY_DSN` (Python, if error reporting is enabled)

Do not place real values in the repository, `render.yaml`, or chat.
