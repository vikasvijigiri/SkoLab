"""Measure an AI-writing detector's false alarms and detection rate.

    # the deployed detector (services/ai-detector), exactly as the API calls it
    AI_DETECTOR_URL=... AI_DETECTOR_TOKEN=... python services/qa/ai_detect_eval.py evalset.json
    # a candidate model, locally (pip install torch transformers)
    python services/qa/ai_detect_eval.py evalset.json --hf fakespot-ai/roberta-base-ai-text-detection-v1

The evaluation set is JSON: [{"text", "label": "human"|"ai", "field"?, "model"?}].
Human texts must be ones nobody could have written with AI help (abstracts
from 2010-2019); AI texts, several models' writing of the same kind. Each
text is cut to the window sizes the gateway scores (internal/ai/writing.go)
and the share flagged is printed per threshold.

Measured 2026-10-19/20 on 198 human abstracts (ten fields, OpenAlex) and 138
AI texts (gpt-oss-120b, gpt-oss-20b, qwen3.8-27b), human flagged / AI flagged:

    desklib/ai-text-detector-v1.01 (DeBERTa-v3-large), 200 words
        0.95: 1.5% / 86%    0.98: 0.0% / 83%     <- used: Likely = 0.98
    fakespot-ai/roberta-base-ai-text-detection-v1, 300 words
        0.95: 13.6% / 90%   0.99: 9.6% / 83%
    Oxidane/tmr-ai-text-detector (RAID), 120 words
        0.90: 17.9% / 62%
    Binoculars, Qwen2.5-0.5B pair: 1.5% false alarms catch 4%

Only desklib's model keeps false alarms near zero on scientific prose, so it
is the one used, though it needs its own service to run.
"""

from __future__ import annotations

import argparse
import asyncio
import json
import statistics
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "backend"))

THRESHOLDS = (0.5, 0.8, 0.9, 0.95, 0.98, 0.99)


def cut(text: str, words: int) -> str:
    return " ".join(text.split()[:words])


def table(name: str, human: list[float], ai: list[float]) -> None:
    print(
        f"\n{name}: {len(human)} human, {len(ai)} AI; median human {statistics.median(human):.3f}, AI {statistics.median(ai):.3f}"
    )
    for t in THRESHOLDS:
        fp, tp = sum(p >= t for p in human), sum(p >= t for p in ai)
        print(
            f"  threshold {t:.2f}: human flagged {fp / len(human):6.1%} ({fp}), AI flagged {tp / len(ai):6.1%}"
        )


def deployed() -> callable:
    from app.services import detector

    if not detector.configured():
        raise SystemExit("set AI_DETECTOR_URL and AI_DETECTOR_TOKEN, or pass --hf")

    def score(texts: list[str]) -> list[float]:
        out = []
        for i in range(
            0, len(texts), 20
        ):  # as the gateway sends a document: a few dozen at most
            probabilities, _ = asyncio.run(detector.score(texts[i : i + 20]))
            out += probabilities
        return out

    return score


def local(name: str) -> callable:
    import torch
    from transformers import AutoModelForSequenceClassification, AutoTokenizer

    tok = AutoTokenizer.from_pretrained(name)
    model = AutoModelForSequenceClassification.from_pretrained(name).eval()

    def score(texts: list[str]) -> list[float]:
        out = []
        for text in texts:
            enc = tok(text, return_tensors="pt", truncation=True, max_length=512)
            with torch.no_grad():
                out.append(float(torch.softmax(model(**enc).logits[0], -1)[1]))
        return out

    return score


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("evalset", help="one or more JSON files, comma-separated")
    parser.add_argument(
        "--hf", help="score with this Hugging Face classifier locally instead"
    )
    parser.add_argument("--lengths", default="200,300", help="window sizes in words")
    args = parser.parse_args()
    items = []
    for path in args.evalset.split(","):
        with open(path, encoding="utf-8") as f:
            items += json.load(f)
    score = local(args.hf) if args.hf else deployed()
    for length in (int(n) for n in args.lengths.split(",")):
        human = [
            cut(i["text"], length)
            for i in items
            if i["label"] == "human" and len(i["text"].split()) >= length
        ]
        ai = [
            cut(i["text"], length)
            for i in items
            if i["label"] == "ai" and len(i["text"].split()) >= length
        ]
        if human and ai:
            table(
                f"{args.hf or 'deployed detector'}, {length}-word windows",
                score(human),
                score(ai),
            )
    return 0


if __name__ == "__main__":
    sys.exit(main())
