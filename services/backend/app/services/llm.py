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
GROQ_MODELS_URL = "https://api.groq.com/openai/v1/models"
DEFAULT_MODEL = "llama-3.3-70b-versatile"
# When the configured model is retired (the provider answers "model not
# found"), the first of these the key can use takes over, best first.
PREFERRED_MODELS = (
    "llama-3.3-70b-versatile",
    "openai/gpt-oss-120b",
    "meta-llama/llama-4-maverick-17b-128e-instruct",
    "meta-llama/llama-4-scout-17b-16e-instruct",
    "qwen/qwen3-32b",
    "openai/gpt-oss-20b",
    "llama-3.1-8b-instant",
)
# The configured model -> the one serving in its place, once resolved.
_replacements: dict[str, str] = {}
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
    model = _replacements.get(cfg.model, cfg.model)
    try:
        response = await _send(http, cfg, {**payload, "model": model})
        if _model_missing(response):
            replacement = await _replacement(http, cfg, exclude=model)
            if replacement is None:
                logger.error(
                    "ai: model %s is not served and no listed model is available", model
                )
                raise LLMUnavailable("The writing assistant is not configured")
            logger.warning(
                "ai: model %s is not served; using %s instead", model, replacement
            )
            _replacements[cfg.model] = model = replacement
            response = await _send(http, cfg, {**payload, "model": model})
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
        return _parse(response.json(), model)
    finally:
        logger.info(
            "ai: improve call took %d ms (goal %s, model %s)",
            round((time.monotonic() - started) * 1000),
            goal,
            model,
        )
        if own:
            await http.aclose()


async def _send(http: httpx.AsyncClient, cfg: Config, payload: dict) -> httpx.Response:
    """POSTs payload, retrying once a connection failure or a 5xx."""
    response: httpx.Response | None = None
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
    return response


def _model_missing(response: httpx.Response) -> bool:
    """The provider no longer serves the model asked for (404, or a 400 that
    says the model was decommissioned)."""
    if response.status_code not in (400, 404):
        return False
    try:
        error = response.json().get("error") or {}
    except ValueError:
        return response.status_code == 404
    code = str(error.get("code", "")) if isinstance(error, dict) else ""
    return response.status_code == 404 or "model" in code


async def _replacement(
    http: httpx.AsyncClient, cfg: Config, *, exclude: str
) -> str | None:
    """The best listed model the key can use now, other than exclude."""
    try:
        response = await http.get(
            GROQ_MODELS_URL,
            headers={"Authorization": f"Bearer {cfg.key}"},
            timeout=TIMEOUT,
        )
        data = response.json().get("data", []) if response.status_code == 200 else []
    except (httpx.HTTPError, ValueError):
        return None
    available = {
        m.get("id") for m in data if isinstance(m, dict) and m.get("active", True)
    }
    for candidate in (cfg.model, *PREFERRED_MODELS):
        if candidate != exclude and candidate in available:
            return candidate
    return None


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
