# SkoLab FastAPI service

FastAPI provides the internal Python portion of SkoLab's identity and CoLab
backend, including the compile fallback. In production, it listens on
`127.0.0.1:8000` inside the `skolab-api` Render container. Clients call the Go
gateway on port 8080; they do not call this process directly.

## Local setup

```powershell
cd services/backend
python -m venv venv
.\venv\Scripts\Activate.ps1
pip install -r requirements.txt -r requirements-dev.txt
uvicorn app.main:app --host 127.0.0.1 --port 8000 --reload
```

Use the repository `.env.example` as a configuration reference. Keep local
secrets out of Git; set production values in Render's environment. Both Go and
Python need their own compatible `DATABASE_URL` format and the same Firebase
service-account credentials. Production CORS allows only `APP_BASE_URL` and
explicit `CORS_ORIGINS` values.

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

The production entrypoint runs migrations before starting either API process.
