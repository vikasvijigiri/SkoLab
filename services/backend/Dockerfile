# Multi-stage build for minimal image size and security
# Stage 1: Build dependencies
FROM python:3.10-slim as builder

WORKDIR /app

RUN apt-get update && apt-get install -y --no-install-recommends \
    build-essential \
    && rm -rf /var/lib/apt/lists/*

COPY requirements.txt .
# CPU-only torch first — the default PyPI wheel bundles CUDA runtime libs
# (~2GB+ extra) that are useless in this container and would blow past the
# "minimal image" intent of this multi-stage build. sentence-transformers'
# own torch requirement is then already satisfied and pip skips it.
RUN pip install --no-cache-dir --user --index-url https://download.pytorch.org/whl/cpu torch && \
    pip install --no-cache-dir --user -r requirements.txt

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
ENV HF_HOME=/home/schoolab/.cache/huggingface

# Expose port
EXPOSE 8000

# Switch to non-root user
USER schoolab

# Pre-download the embedding model weights into the image at build time, so
# container startup never depends on HuggingFace Hub being reachable and
# there's no first-request latency spike.
RUN python -c "from sentence_transformers import SentenceTransformer; SentenceTransformer('BAAI/bge-small-en-v1.5')"

# Run the app.
# WEB_CONCURRENCY controls uvicorn worker processes — one event loop per worker,
# so CPU-bound work (embedding encode, large JSON parsing) in one request no
# longer starves every other. Each worker loads its own copy of the embedding
# model (~130 MB + torch), so scale this to the host: default 1 keeps a 512 MB
# box alive; set WEB_CONCURRENCY=2..N on a multi-vCPU / 16 GB Space.
# --timeout-keep-alive 65 outlives typical upstream (Render/HF) idle timeouts.
CMD ["sh", "-c", "exec uvicorn app.main:app --host 0.0.0.0 --port 8000 --workers ${WEB_CONCURRENCY:-1} --timeout-keep-alive 65"]
