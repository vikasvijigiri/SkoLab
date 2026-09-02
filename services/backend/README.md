---
title: Skolab Backend
emoji: 🎓
colorFrom: blue
colorTo: indigo
sdk: docker
app_port: 8000
pinned: false
---

<!-- The block above is Hugging Face Space metadata. It is inert on GitHub and
     required when this directory is pushed to a Docker Space (see DEPLOY.md).
     `app_port: 8000` matches the Dockerfile CMD's uvicorn port. -->

# Skolab Backend

This is the Python (FastAPI) backend for the Skolab project.

## Architecture

This project strictly follows modern FastAPI structure and separates concerns into individual modules:

- **`app/api/`**: Contains API routers and versioning (e.g., `v1`).
- **`app/core/`**: Configuration, security, and environment variable logic.
- **`app/models/`**: Database schema models (e.g., SQLAlchemy) and data-related objects. 
- **`app/services/`**: The core business logic. Separation here ensures your endpoints remain clean and logic is highly testable.
- **`app/db/`**: Database connection and session management logic.
- **`tests/`**: Unit and integration tests for the backend.

## Quick Start

### 1. Environment Setup
Ensure you have Python installed, then create and activate a virtual environment:

```bash
# Create a virtual environment
python -m venv venv

# Activate it
# On Windows:
venv\Scripts\activate
# On macOS/Linux:
source venv/bin/activate
```

### 2. Install Dependencies
```bash
pip install -r requirements.txt
```

### 3. Run the Development Server
To make the backend accessible to an Android device or emulator, bind it to all network interfaces:

```bash
uvicorn app.main:app --host 0.0.0.0 --port 8000 --reload
```
The API documentation will then be available at `http://localhost:8000/docs`.
