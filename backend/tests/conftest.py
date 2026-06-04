import os
import sys
from pathlib import Path
from dotenv import load_dotenv

# Load .env first to respect local developer environment db configurations
backend_root = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
load_dotenv(os.path.join(backend_root, ".env"))

if "DATABASE_URL" not in os.environ:
    os.environ["DATABASE_URL"] = (
        "postgresql+asyncpg://postgres:postgres@localhost:5432/qyrus"
    )
os.environ["TESTING"] = "True"
os.environ["GROQ_API"] = "mock_groq_key"
os.environ["GOOGLE_APPLICATION_CREDENTIALS"] = "service-account.json"

# Inject backend root to sys.path
backend_root = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
if backend_root not in sys.path:
    sys.path.insert(0, backend_root)
