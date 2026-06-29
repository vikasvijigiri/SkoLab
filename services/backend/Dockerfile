# Multi-stage build for minimal image size and security
# Stage 1: Build dependencies
FROM python:3.10-slim as builder

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

# Run the app
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]
