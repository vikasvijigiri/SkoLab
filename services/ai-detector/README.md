# SkoLab AI-writing detector

The AI-written estimate in SkoLab's originality report:
[desklib/ai-text-detector-v1.01](https://huggingface.co/desklib/ai-text-detector-v1.01)
(MIT, DeBERTa-v3-large), pinned to one revision, served by `app.py`.

- `POST /detect` `{"passages": [...]}` → `{"probabilities": [...], "model": "..."}`:
  the probability that each passage (200-300 words) is machine-written.
  Requires the `X-Detector-Token` secret.
- `GET /health` → whether the model is loaded.

## Why this model

`services/qa/ai_detect_eval.py` scores open detectors on 198 human-written
abstracts from 2010-2019 (before AI writing tools; ten fields) and 138
AI-written texts from three model families. On 200-word passages at the
threshold SkoLab uses (0.98), this model flagged none of the human abstracts
and caught 83% of the AI text. The small models that would fit SkoLab's API
server (512 MB) flagged 14-22% of the human abstracts, so they are not used.

## Where it runs

It needs about 2 GB of memory, so it runs on [Modal](https://modal.com) from
the free monthly credits, scaling to zero between checks (`modal_app.py`):

    pip install modal && modal setup
    modal secret create skolab-detector DETECTOR_TOKEN=<secret>
    modal deploy services/ai-detector/modal_app.py

Then set on the API (Render): `AI_DETECTOR_URL` (the URL `modal deploy`
prints), `AI_DETECTOR_TOKEN` (the same secret) and `AI_WRITING_ESTIMATE=on`.

The `Dockerfile` runs the same app anywhere with 2 GB (a VM, a Hugging Face
Space on PRO): `docker build -t skolab-ai-detector services/ai-detector`.
