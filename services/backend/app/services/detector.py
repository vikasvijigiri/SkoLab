"""The AI-writing classifier, for the gateway's originality report.

The model (desklib/ai-text-detector-v1.01, DeBERTa-v3-large) needs about
2 GB of memory, more than this server has, so it runs as its own service
(services/ai-detector: on Modal, scaling to zero between checks). This
module calls it: AI_DETECTOR_URL, with the AI_DETECTOR_TOKEN secret. Why
that model: services/ai-detector/README.md and services/qa/ai_detect_eval.py.

A service that has scaled to zero takes up to a minute to start, so the
gateway wakes it (warm) when a check begins, while it searches the sources.
"""

from __future__ import annotations

import os

import httpx

# A cold start (container, then the model) can take about a minute.
TIMEOUT = httpx.Timeout(240.0, connect=30.0)


class DetectorUnavailable(RuntimeError):
    """No detector is configured, or it did not answer usefully."""


def configured() -> bool:
    return bool(
        os.getenv("AI_DETECTOR_URL", "").strip()
        and os.getenv("AI_DETECTOR_TOKEN", "").strip()
    )


def _base() -> str:
    return os.getenv("AI_DETECTOR_URL", "").strip().rstrip("/")


async def score(
    passages: list[str], *, client: httpx.AsyncClient | None = None
) -> tuple[list[float], str]:
    """The probability that each passage is machine-written, and the model's name."""
    if not configured():
        raise DetectorUnavailable("the AI-writing detector is not configured")
    own = client is None
    client = client or httpx.AsyncClient(timeout=TIMEOUT)
    try:
        response = await client.post(
            f"{_base()}/detect",
            json={"passages": passages},
            headers={"X-Detector-Token": os.environ["AI_DETECTOR_TOKEN"].strip()},
        )
    except httpx.HTTPError as exc:
        raise DetectorUnavailable("the AI-writing detector did not answer") from exc
    finally:
        if own:
            await client.aclose()
    if response.status_code != 200:
        raise DetectorUnavailable(
            f"the AI-writing detector answered {response.status_code}"
        )
    try:
        body = response.json()
        probabilities = [float(p) for p in body["probabilities"]]
        model = str(body.get("model") or "unknown")
    except (ValueError, KeyError, TypeError) as exc:
        raise DetectorUnavailable(
            "the AI-writing detector gave an unreadable answer"
        ) from exc
    if len(probabilities) != len(passages) or any(
        not 0.0 <= p <= 1.0 for p in probabilities
    ):
        raise DetectorUnavailable("the AI-writing detector gave an unusable answer")
    return probabilities, model


async def warm(*, client: httpx.AsyncClient | None = None) -> bool:
    """Starts the detector if it has scaled to zero; True once it answers."""
    if not configured():
        return False
    own = client is None
    client = client or httpx.AsyncClient(timeout=TIMEOUT)
    try:
        response = await client.get(f"{_base()}/health")
        return response.status_code == 200
    except httpx.HTTPError:
        return False
    finally:
        if own:
            await client.aclose()
