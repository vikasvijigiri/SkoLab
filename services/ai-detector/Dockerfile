# SkoLab's AI-writing detector on a Hugging Face Space (Docker SDK).
# Deployed by services/ai-detector/deploy.py; see app.py.
FROM python:3.12-slim

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
CMD ["uvicorn", "app:app", "--host", "0.0.0.0", "--port", "7860"]
