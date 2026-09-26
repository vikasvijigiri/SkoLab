# SkoLab FastAPI service

This service provides SkoLab's domain, AI, enrichment, and data API. It runs
behind the Go gateway in production and is deployed with the repository's
`render.yaml` blueprint.

## Local setup

```powershell
cd services/backend
python -m venv venv
.\venv\Scripts\Activate.ps1
pip install -r requirements.txt -r requirements-dev.txt
uvicorn app.main:app --host 0.0.0.0 --port 8000 --reload
```

Copy the repository `.env.example` to a local `.env` only when required. Never
commit service credentials or Firebase service-account files.

## Verification

```powershell
ruff check .
ruff format --check .
pytest -q
```

## Database migrations

Run migrations from this directory:

```powershell
python scripts/run_migrations.py
```

The service validates its database schema at startup. For production, provide
the required secrets through the deployment environment rather than files in
the repository.
