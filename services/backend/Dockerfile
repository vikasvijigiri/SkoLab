# Multi-stage build for minimal image size and security.
#
# Embeddings run via the Hugging Face Inference API (see
# app/services/ai/embedding_service.py), so there is no PyTorch and no
# sentence-transformers model in this image — it is ~350 MB and fits a
# 512 MB free-tier container. For a self-hosted deploy that wants the local
# model backend, add `sentence-transformers` to requirements.txt and a
# `pip install --index-url https://download.pytorch.org/whl/cpu torch` line
# plus a build-time model pre-download.

# Stage 1: Build dependencies
FROM python:3.10-slim AS builder

WORKDIR /app

RUN apt-get update && apt-get install -y --no-install-recommends \
    build-essential \
    && rm -rf /var/lib/apt/lists/*

COPY requirements.txt .
RUN pip install --no-cache-dir --user -r requirements.txt

# Stage 2: Final minimal image
FROM python:3.10-slim

WORKDIR /app

# Create a non-root group and user
RUN groupadd -g 10001 schoolab && \
    useradd -u 10001 -g schoolab -s /bin/bash -m schoolab

# Copy installed packages from builder
COPY --from=builder --chown=schoolab:schoolab /root/.local /home/schoolab/.local
COPY --chown=schoolab:schoolab . .

ENV PATH=/home/schoolab/.local/bin:$PATH
ENV PYTHONUNBUFFERED=1

# Expose port
EXPOSE 8000

# Switch to non-root user
USER schoolab

# Run the app.
# WEB_CONCURRENCY controls uvicorn worker processes — one event loop per worker,
# so CPU-bound work (large JSON parsing, PDF extraction) in one request no
# longer starves every other. Default 1 keeps a 512 MB box alive; raise it on
# a multi-vCPU host. --timeout-keep-alive 65 outlives typical upstream
# (Render / proxy) idle timeouts.
CMD ["sh", "-c", "exec uvicorn app.main:app --host 0.0.0.0 --port 8000 --workers ${WEB_CONCURRENCY:-1} --timeout-keep-alive 65"]
