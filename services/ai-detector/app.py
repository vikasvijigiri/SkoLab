"""SkoLab's AI-writing detector, served from a private Hugging Face Space.

desklib/ai-text-detector-v1.01 (MIT): DeBERTa-v3-large fine-tuned to tell
human from machine text, mean-pooled, one sigmoid unit. Chosen by
services/qa/ai_detect_eval.py: on human-written abstracts from before AI
writing tools it raised the fewest false alarms of every open detector
tested, while catching most AI-written text. Too large for SkoLab's API
server (512 MB), so it runs here (16 GB, 2 vCPU on the free tier).

Only SkoLab's API may call it: the Space is private (a Hugging Face token
is needed to reach it at all), and every request must also carry
DETECTOR_TOKEN, a secret only the API knows.
"""

from __future__ import annotations

import hmac
import os
import threading
import time

import torch
from fastapi import Depends, FastAPI, Header, HTTPException
from huggingface_hub import hf_hub_download
from pydantic import BaseModel, Field, field_validator
from safetensors.torch import load_file
from transformers import AutoConfig, AutoModel, AutoTokenizer

MODEL = "desklib/ai-text-detector-v1.01"
REVISION = "5fdea974cd4287c61674951ec78803aa274e2fb7"
MODEL_NAME = "desklib ai-text-detector-v1.01 (DeBERTa-v3-large)"
MAX_TOKENS = 768

torch.set_num_threads(max(1, os.cpu_count() or 1))
app = FastAPI(
    title="SkoLab AI-writing detector", docs_url=None, redoc_url=None, openapi_url=None
)
_lock = threading.Lock()
_model: dict = {}


def load() -> dict:
    """The model, loaded once (about 20 s)."""
    with _lock:
        if not _model:
            started = time.time()
            tokenizer = AutoTokenizer.from_pretrained(MODEL, revision=REVISION)
            config = AutoConfig.from_pretrained(MODEL, revision=REVISION)
            backbone = AutoModel.from_config(config).eval()
            head = torch.nn.Linear(config.hidden_size, 1)
            state = load_file(
                hf_hub_download(MODEL, "model.safetensors", revision=REVISION)
            )
            backbone.load_state_dict(
                {
                    k[len("model.") :]: v
                    for k, v in state.items()
                    if k.startswith("model.")
                }
            )
            head.load_state_dict(
                {
                    k[len("classifier.") :]: v
                    for k, v in state.items()
                    if k.startswith("classifier.")
                }
            )
            _model.update(tokenizer=tokenizer, backbone=backbone, head=head.eval())
            print(f"model loaded in {time.time() - started:.1f}s", flush=True)
    return _model


@torch.no_grad()
def score(text: str) -> float:
    m = load()
    enc = m["tokenizer"](
        text, return_tensors="pt", truncation=True, max_length=MAX_TOKENS
    )
    hidden = m["backbone"](enc["input_ids"], attention_mask=enc["attention_mask"])[0]
    mask = enc["attention_mask"].unsqueeze(-1).float()
    pooled = (hidden * mask).sum(1) / mask.sum(1).clamp(min=1e-9)
    return round(float(torch.sigmoid(m["head"](pooled))[0][0]), 4)


def require_api(x_detector_token: str | None = Header(default=None)) -> None:
    expected = os.environ.get("DETECTOR_TOKEN", "")
    if (
        not expected
        or not x_detector_token
        or not hmac.compare_digest(x_detector_token.encode(), expected.encode())
    ):
        raise HTTPException(status_code=403, detail="forbidden")


class DetectRequest(BaseModel):
    passages: list[str] = Field(..., min_length=1, max_length=60)

    @field_validator("passages")
    @classmethod
    def _bounded(cls, passages: list[str]) -> list[str]:
        for text in passages:
            if not text.strip() or len(text) > 6000:
                raise ValueError("each passage must be 1 to 6,000 characters")
        return passages


@app.on_event("startup")
def warm() -> None:
    # Load in the background so the Space reports itself up at once.
    threading.Thread(target=load, daemon=True).start()


@app.get("/health")
def health() -> dict:
    return {"status": "ok", "loaded": bool(_model)}


@app.post("/detect", dependencies=[Depends(require_api)])
def detect(request: DetectRequest) -> dict:
    return {
        "probabilities": [score(text) for text in request.passages],
        "model": MODEL_NAME,
    }
