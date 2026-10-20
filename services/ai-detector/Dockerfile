# SkoLab's AI-writing detector as a container, for any host with 2 GB.
# Production runs it on Modal (modal_app.py); see README.md and app.py.
FROM python:3.12-slim@sha256:05cda9777409a9c3ffddd94a4c476b79f0769a0b4857f0c7ed9226b6800b0d6f

RUN useradd -m -u 1000 user
USER user
ENV HOME=/home/user PATH=/home/user/.local/bin:$PATH HF_HOME=/home/user/.cache/huggingface
WORKDIR /home/user/app

COPY --chown=user requirements.txt .
RUN pip install --no-cache-dir --user --extra-index-url https://download.pytorch.org/whl/cpu -r requirements.txt

COPY --chown=user app.py .
# The model (pinned revision) is fetched at build time, so a wake-up only loads it.
RUN python -c "from huggingface_hub import snapshot_download; snapshot_download('desklib/ai-text-detector-v1.01', revision='5fdea974cd4287c61674951ec78803aa274e2fb7')"

EXPOSE 7860
# Up once it answers; the model loads in the background (about 30 s), so
# allow for that before failing.
HEALTHCHECK --interval=30s --timeout=5s --start-period=90s --retries=3 CMD python -c "import urllib.request; urllib.request.urlopen('http://127.0.0.1:7860/health', timeout=4)"
CMD ["uvicorn", "app:app", "--host", "0.0.0.0", "--port", "7860"]
