# Multi-stage build for minimal image size and security.
#
# Embeddings run via the Hugging Face Inference API (see
# app/services/ai/embedding_service.py), so there is no PyTorch and no
# sentence-transformers model in this image — it is ~350 MB and fits a
# 512 MB free-tier container. For a self-hosted deploy that wants the local
# model backend, add `sentence-transformers` to requirements.txt and a
# `pip install --index-url https://download.pytorch.org/whl/cpu torch` line
# plus a build-time model pre-download.
#
# Python 3.12, matching CI (.github/workflows/checks.yml, ci.yml, verify.yml)
# and requirements.txt's numpy==2.5.3/networkx pins, which need >=3.11/3.12.
# This image was still on 3.10 when 26497f8 (2026-09-11) bumped those two
# packages "now that CI runs Python 3.12" — CI moved, this file didn't, and
# every Render build since has failed at `pip install` with no matching
# numpy wheel for 3.10, silently serving whatever image last built
# successfully (pre-2026-09-11) instead of anything more recent.

# Stage 1: Build dependencies
FROM python:3.12-slim AS builder

WORKDIR /app

RUN apt-get update && apt-get install -y --no-install-recommends \
    build-essential \
    && rm -rf /var/lib/apt/lists/*

COPY requirements.txt .
RUN pip install --no-cache-dir --user -r requirements.txt

# Stage 2: Final minimal image
FROM python:3.12-slim

WORKDIR /app

# TeX is deliberately installed in the runtime image: CoLab compilation is
# an authenticated, bounded subprocess and must work after deployment, not
# only on a developer machine. Keep the package set minimal; pdflatex and the
# base LaTeX classes cover the templates currently shipped by the web client.
RUN apt-get update && apt-get install -y --no-install-recommends \
    texlive-latex-base \
    && rm -rf /var/lib/apt/lists/* && \
    groupadd -g 10001 schoolab && \
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
