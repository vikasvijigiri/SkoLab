"""The writing improver's model call: Groq's OpenAI-compatible chat API.

This module only asks the model and parses what it says. Everything that
must be true of an edit (it quotes the text exactly, keeps every protected
placeholder, keeps braces balanced) is checked by the gateway
(internal/ai), which never shows an edit it cannot verify.

Configuration: ``GROQ_API`` (the key; ``GROQ_API_KEY`` also accepted) and
``GROQ_MODEL`` (default ``llama-3.3-70b-versatile``).
"""

from __future__ import annotations

import asyncio
import json
import logging
import os
import time
from dataclasses import dataclass

import httpx

from app.schemas.ai import CATEGORIES, Edit, Goal, ImproveResponse

logger = logging.getLogger("skolab.ai")

GROQ_URL = "https://api.groq.com/openai/v1/chat/completions"
DEFAULT_MODEL = "llama-3.3-70b-versatile"
TIMEOUT = httpx.Timeout(25.0, connect=5.0)
MAX_EDITS = 12

GOALS: dict[str, str] = {
    "clarity": "Make sentences clearer and easier to follow without changing what they claim.",
    "concise": "Remove redundancy and wordiness; keep every fact, claim and number.",
    "academic": "Use a formal, precise scholarly register suitable for a journal article.",
    "grammar": "Fix grammar, spelling, punctuation and agreement errors only; change nothing else.",
    "flow": "Improve transitions and sentence rhythm so paragraphs read smoothly.",
}

SYSTEM = """You are an expert scientific editor improving a passage from a LaTeX manuscript.
Goal: {goal}

Rules, all mandatory:
- Tokens like ⟦1⟧, ⟦2⟧ stand for math, citations, references or LaTeX commands. Copy each one exactly; never edit, add, remove, split or translate them.
- Keep the meaning, every claim, number, unit and technical term. Never invent results, data or citations.
- Keep the author's spelling variant (British or American) and keep LaTeX braces balanced.
- Propose at most {max_edits} edits. Each "original" must be an exact, contiguous substring of the passage (copy it character for character), at most one sentence, and edits must not overlap.
- Skip text that is already good; return fewer edits rather than cosmetic ones.

Reply with JSON only, in this shape:
{{"edits": [{{"original": "...", "replacement": "...", "reason": "short reason, under 15 words", "category": "clarity|concision|tone|grammar|flow|word_choice"}}]}}"""


class LLMUnavailable(Exception):
    """The model could not answer (not configured, down, or busy)."""

    def __init__(
        self, message: str, *, retry_after: int | None = None, status: int = 503
    ):
        super().__init__(message)
        self.retry_after = retry_after
        self.status = status


class LLMBadOutput(Exception):
    """The model answered with something that is not the agreed JSON."""


@dataclass(frozen=True)
class Config:
    key: str
    model: str


def config() -> Config | None:
    key = (os.environ.get("GROQ_API") or os.environ.get("GROQ_API_KEY") or "").strip()
    if not key:
        return None
    model = os.environ.get("GROQ_MODEL", DEFAULT_MODEL).strip() or DEFAULT_MODEL
    return Config(key=key, model=model)


def _retry_after(response: httpx.Response) -> int | None:
    value = response.headers.get("retry-after")
    try:
        return max(1, min(60, int(float(value)))) if value else None
    except ValueError:
        return None


async def improve(
    text: str, goal: Goal, *, client: httpx.AsyncClient | None = None
) -> ImproveResponse:
    """Edits the model proposes for text, parsed but not yet verified."""
    cfg = config()
    if cfg is None:
        raise LLMUnavailable("The writing assistant is not configured")
    payload = {
        "model": cfg.model,
        "temperature": 0.2,
        "max_tokens": 2048,
        "response_format": {"type": "json_object"},
        "messages": [
            {
                "role": "system",
                "content": SYSTEM.format(goal=GOALS[goal], max_edits=MAX_EDITS),
            },
            {"role": "user", "content": f"Passage:\n<<<\n{text}\n>>>"},
        ],
    }
    own = client is None
    http = client or httpx.AsyncClient()
    started = time.monotonic()
    try:
        response: httpx.Response | None = None
        # One retry for a transient failure, well inside the gateway's own timeout.
        for attempt in range(2):
            try:
                response = await http.post(
                    GROQ_URL,
                    json=payload,
                    headers={"Authorization": f"Bearer {cfg.key}"},
                    timeout=TIMEOUT,
                )
            except httpx.TimeoutException as exc:
                raise LLMUnavailable(
                    "The writing assistant took too long to answer"
                ) from exc
            except httpx.HTTPError as exc:
                if attempt == 0:
                    await asyncio.sleep(0.5)
                    continue
                raise LLMUnavailable("The writing assistant is unreachable") from exc
            if response.status_code in (500, 502, 503, 504) and attempt == 0:
                await asyncio.sleep(0.5)
                continue
            break
        if response is None:  # pragma: no cover - the loop always assigns or raises
            raise LLMUnavailable("The writing assistant is unreachable")
        if response.status_code == 429:
            raise LLMUnavailable(
                "The writing assistant is busy",
                retry_after=_retry_after(response) or 20,
                status=429,
            )
        if response.status_code in (401, 403):
            logger.error(
                "ai: model provider refused the key (status %s)", response.status_code
            )
            raise LLMUnavailable("The writing assistant is not configured")
        if response.status_code >= 400:
            logger.warning("ai: model provider error (status %s)", response.status_code)
            raise LLMUnavailable("The writing assistant failed; try again")
        return _parse(response.json(), cfg.model)
    finally:
        logger.info(
            "ai: improve call took %d ms (goal %s)",
            round((time.monotonic() - started) * 1000),
            goal,
        )
        if own:
            await http.aclose()


def _parse(body: dict, model: str) -> ImproveResponse:
    try:
        data = json.loads(body["choices"][0]["message"]["content"])
    except (KeyError, IndexError, TypeError, ValueError) as exc:
        raise LLMBadOutput("The model did not answer in JSON") from exc
    raw = data.get("edits") if isinstance(data, dict) else None
    if not isinstance(raw, list):
        raise LLMBadOutput("The model's answer has no edits list")
    edits: list[Edit] = []
    for item in raw[:MAX_EDITS]:
        if not isinstance(item, dict):
            continue
        category = item.get("category")
        try:
            edit = Edit(
                original=str(item.get("original", "")),
                replacement=str(item.get("replacement", "")),
                reason=str(item.get("reason", ""))[:300],
                category=category if category in CATEGORIES else "clarity",
            )
        except ValueError:
            continue  # one malformed edit is dropped, not the whole answer
        if edit.original != edit.replacement:
            edits.append(edit)
    raw_usage = body.get("usage")
    usage: dict = raw_usage if isinstance(raw_usage, dict) else {}
    return ImproveResponse(
        edits=edits,
        model=str(body.get("model") or model),
        prompt_tokens=int(usage.get("prompt_tokens") or 0),
        completion_tokens=int(usage.get("completion_tokens") or 0),
    )
