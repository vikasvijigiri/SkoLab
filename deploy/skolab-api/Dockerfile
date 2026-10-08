# skolab-api: the Go gateway and the Python service in one container.
#
# One free Render service instead of two: Render's free tier grants 750
# instance-hours per workspace per month, and two always-on services need
# ~1,440. The gateway is the only public listener; Python binds 127.0.0.1
# and is reachable only from inside the container. entrypoint.sh supervises
# both: if either exits, the container exits and Render restarts it.
#
# Build context is the repository root (see /.dockerignore).

# ── Gateway binary ────────────────────────────────────────────────────────
# Keep in step with services/backend-go/go.mod. CGO off: the binary is
# static, so this Alpine-built binary runs on the Debian runtime below.
FROM golang:1.26-alpine@sha256:8ac98ca534ac3f51e1f420a1dd2c15e74c75cfa0f23f3ad27eb5d7236c349a0c AS gateway
WORKDIR /src
ENV GOTOOLCHAIN=local CGO_ENABLED=0
COPY services/backend-go/go.mod services/backend-go/go.sum ./
RUN go mod download && go mod verify
COPY services/backend-go/ ./
RUN go build -trimpath -o /out/skolab-gateway .

# ── Python dependencies ───────────────────────────────────────────────────
FROM python:3.12-slim@sha256:05cda9777409a9c3ffddd94a4c476b79f0769a0b4857f0c7ed9226b6800b0d6f AS pydeps
RUN apt-get update && apt-get install -y --no-install-recommends build-essential \
    && rm -rf /var/lib/apt/lists/*
COPY services/backend/requirements.txt .
RUN pip install --no-cache-dir --user -r requirements.txt

# ── Runtime ───────────────────────────────────────────────────────────────
FROM python:3.12-slim@sha256:05cda9777409a9c3ffddd94a4c476b79f0769a0b4857f0c7ed9226b6800b0d6f
WORKDIR /app

# TeX for CoLab compiles (a bounded, sandboxed subprocess; see
# services/backend/app/api/v1/endpoints/colab.py) and util-linux for prlimit.
# Apply available security fixes to packages inherited from the base image.
RUN apt-get update && apt-get upgrade -y --no-install-recommends \
    && apt-get install -y --no-install-recommends \
    texlive-latex-base \
    util-linux \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd -g 10001 schoolab \
    && useradd -u 10001 -g schoolab -s /bin/bash -m schoolab

COPY --from=pydeps --chown=schoolab:schoolab /root/.local /home/schoolab/.local
COPY --chown=schoolab:schoolab services/backend/ ./
COPY --from=gateway /out/skolab-gateway /usr/local/bin/skolab-gateway
COPY deploy/skolab-api/entrypoint.sh /usr/local/bin/skolab-entrypoint

ENV PATH=/home/schoolab/.local/bin:$PATH \
    PYTHONUNBUFFERED=1 \
    PYTHON_BACKEND_URL=http://127.0.0.1:8000

USER schoolab
EXPOSE 8080
# Container-level liveness for any runtime that honours it (Render uses its
# own check on /gateway-health). The start period covers migrations + Python.
HEALTHCHECK --interval=30s --timeout=5s --start-period=180s --retries=3 CMD python -c "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8080/gateway-health', timeout=4)"
# Run through bash so the script needs no executable bit (Windows checkouts
# drop it).
CMD ["bash", "/usr/local/bin/skolab-entrypoint"]
