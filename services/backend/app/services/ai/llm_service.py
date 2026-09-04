import time
import httpx
from typing import List, Dict, Any, Optional
from openrouter import OpenRouter
from app.core.config import settings
from app.core.circuit_breaker import groq_breaker, CircuitBreakerOpenError


# ── Shared HTTP client ───────────────────────────────────────────────────────
# One process-wide AsyncClient with a bounded connection pool. The previous
# `async with httpx.AsyncClient()` per call paid a fresh TCP + TLS handshake to
# Groq on every request (~100-300 ms) and, under load, exhausted ephemeral
# ports. Keep-alive connections are reused across calls.
_http_client: Optional[httpx.AsyncClient] = None


def get_http_client() -> httpx.AsyncClient:
    global _http_client
    if _http_client is None or _http_client.is_closed:
        _http_client = httpx.AsyncClient(
            timeout=httpx.Timeout(settings.llm_timeout_seconds, connect=5.0),
            limits=httpx.Limits(max_connections=100, max_keepalive_connections=20),
        )
    return _http_client


async def aclose_http_client() -> None:
    """Close the shared client on app shutdown (call from the FastAPI lifespan)."""
    global _http_client
    if _http_client is not None and not _http_client.is_closed:
        await _http_client.aclose()
    _http_client = None


# Global rate limit / availability state
LLM_LIMIT_EXCEEDED = False
LLM_LIMIT_EXCEEDED_TIME = 0.0


def is_llm_working() -> bool:
    global LLM_LIMIT_EXCEEDED, LLM_LIMIT_EXCEEDED_TIME
    if LLM_LIMIT_EXCEEDED:
        # If 15 minutes have passed, attempt to reset and try again
        if time.time() - LLM_LIMIT_EXCEEDED_TIME > 900:
            LLM_LIMIT_EXCEEDED = False
            print(
                "[LLMService] Attempting to reset LLM_LIMIT_EXCEEDED after 15-minute cooldown...",
                flush=True,
            )
    has_groq = bool(settings.groq_api_key)
    has_openrouter = bool(settings.openrouter_api_key)
    return has_openrouter or (has_groq and not LLM_LIMIT_EXCEEDED)


def set_llm_limit_exceeded(exceeded: bool):
    global LLM_LIMIT_EXCEEDED, LLM_LIMIT_EXCEEDED_TIME
    LLM_LIMIT_EXCEEDED = exceeded
    if exceeded:
        LLM_LIMIT_EXCEEDED_TIME = time.time()
    print(f"[LLMService] LLM_LIMIT_EXCEEDED set to {exceeded}", flush=True)


class LLMResponse:
    def __init__(
        self,
        content: Optional[str],
        tool_calls: Optional[List[Dict[str, Any]]] = None,
        model_used: str = "",
    ) -> None:
        self.content = content
        self.tool_calls = tool_calls
        self.model_used = model_used

    def to_dict(self) -> Dict[str, Any]:
        return {
            "content": self.content,
            "tool_calls": self.tool_calls,
            "model_used": self.model_used,
        }


class LLMService:
    def __init__(self) -> None:
        self.groq_api_key = settings.groq_api_key
        self.groq_base_url = "https://api.groq.com/openai/v1/chat/completions"

        # Prioritized fallback model order. If one fails, the loop continues
        # to the next. Sourced from settings.llm_groq_models +
        # settings.llm_openrouter_models (LLM_GROQ_MODELS /
        # LLM_OPENROUTER_MODELS env vars) — never a hardcoded literal here.
        # Incident, 2026-09-04 (two rounds):
        # (1) This used to be 5 Groq models followed by OpenRouter entries,
        #     all as string literals; Groq decommissioned every one of the
        #     5, and with llm_max_fallback_models capping the loop at 4
        #     attempts, every attempt was Groq and every one was dead —
        #     100% of LLM calls failed before ever reaching an OpenRouter
        #     fallback.
        # (2) The immediate fix for (1) replaced the dead names with
        #     openai/gpt-oss-120b and qwen/qwen3.8-27b — both containing
        #     "/", which query()'s is_or check used to read as "this must
        #     be an OpenRouter model" (Groq's older catalog was all
        #     bare names). Both got routed to query_openrouter() instead
        #     of Groq's endpoint; with no OpenRouter key configured, every
        #     model was silently skipped, still 100% failure. Explicit
        #     provider lists (self._openrouter_models, checked by set
        #     membership in query() below) replace the name-shape guess —
        #     see config.py's llm_groq_models docstring for the full
        #     writeup and how the current chain was verified.
        #
        # "openrouter/owl-alpha" was removed from the chain (pre-existing
        # fix, kept): it has no active endpoints on OpenRouter, and every
        # query used to waste a full round-trip on it before falling back —
        # worse, when combined with response_format={"type": "json_object"},
        # OpenRouter returned an empty `choices` list instead of an error,
        # which query_openrouter used to treat as a *successful* response
        # (see the fix there), so analyze_paper's JSON parsing failed on
        # every single call instead of ever reaching a model that works.
        groq_models = [
            m.strip() for m in settings.llm_groq_models.split(",") if m.strip()
        ]
        openrouter_models = [
            m.strip() for m in settings.llm_openrouter_models.split(",") if m.strip()
        ]
        self.default_models = groq_models + openrouter_models
        # Membership, not a name-shape guess — see the incident note above.
        self._openrouter_models = frozenset(openrouter_models)

        # Initialize official OpenRouter Client SDK
        if settings.openrouter_api_key:
            self.openrouter_client = OpenRouter(
                api_key=settings.openrouter_api_key,
                http_referer=settings.app_base_url,
                x_open_router_title="SkoLab",
            )
        else:
            self.openrouter_client = None

    async def query_openrouter(
        self,
        messages: List[Dict[str, str]],
        model: str = "google/gemma-2-9b-it:free",
        temperature: float = 0.5,
        max_tokens: Optional[int] = None,
        response_format: Optional[Dict[str, Any]] = None,
        tools: Optional[List[Dict[str, Any]]] = None,
        tool_choice: Optional[str] = None,
    ) -> LLMResponse:
        if not self.openrouter_client:
            raise Exception("OpenRouter API key is missing or client not initialized.")

        kwargs = {
            "messages": messages,
            "model": model,
            "temperature": temperature,
        }
        if max_tokens is not None:
            kwargs["max_tokens"] = max_tokens
        if response_format is not None:
            kwargs["response_format"] = response_format
        if tools is not None:
            kwargs["tools"] = tools
        if tool_choice is not None:
            kwargs["tool_choice"] = tool_choice

        try:
            resp = await self.openrouter_client.chat.send_async(**kwargs)
            choices = getattr(resp, "choices", [])
            if choices:
                msg = choices[0].message
                content = getattr(msg, "content", None)
                tool_calls = getattr(msg, "tool_calls", None)

                # Format tool calls to standard dictionary format if present
                tool_calls_dict = None
                if tool_calls is not None:
                    tool_calls_dict = []
                    for tc in tool_calls:
                        if hasattr(tc, "model_dump"):
                            tc_dict = tc.model_dump()
                        elif hasattr(tc, "dict"):
                            tc_dict = tc.dict()
                        else:
                            tc_dict = {
                                "id": getattr(tc, "id", None),
                                "type": getattr(tc, "type", "function"),
                                "function": {
                                    "name": getattr(
                                        getattr(tc, "function", None), "name", None
                                    ),
                                    "arguments": getattr(
                                        getattr(tc, "function", None), "arguments", None
                                    ),
                                },
                            }
                        tool_calls_dict.append(tc_dict)

                if not content and not tool_calls_dict:
                    # Some models silently return no usable output instead of
                    # an HTTP error (observed with dead/unsupported models,
                    # especially combined with response_format=json_object).
                    # Raising here — instead of returning a "successful"
                    # response with garbage content — lets query()'s fallback
                    # loop actually try the next model.
                    raise Exception(
                        f"Model {model} returned an empty completion (no content, no tool calls)."
                    )
                return LLMResponse(
                    content=content, tool_calls=tool_calls_dict, model_used=model
                )
            else:
                raise Exception(f"Model {model} returned no choices in the response.")
        except Exception as e:
            print(
                f"[LLMService] OpenRouter SDK exception for model {model}: {e}",
                flush=True,
            )
            raise e

    async def query(
        self,
        messages: List[Dict[str, str]],
        models: Optional[List[str]] = None,
        temperature: float = 0.5,
        max_tokens: Optional[int] = None,
        response_format: Optional[Dict[str, Any]] = None,
        tools: Optional[List[Dict[str, Any]]] = None,
        tool_choice: Optional[str] = None,
    ) -> LLMResponse:
        """
        Primary LLM query entry point. Tries models in the sequence iteratively.
        If a model fails or is rate-limited, attempts the next model in the list.
        """
        global LLM_LIMIT_EXCEEDED
        if not is_llm_working():
            raise Exception("LLM services are currently unavailable or rate-limited.")

        # Determine the sequence of models to try.
        # Try requested models first, then fall back to the rest of default_models.
        models_to_try = []
        if models:
            for m in models:
                if m and m not in models_to_try:
                    models_to_try.append(m)
            for m in self.default_models:
                if m not in models_to_try:
                    models_to_try.append(m)
        else:
            models_to_try = list(self.default_models)

        # Bound the fallback fan-out: at most N attempts, under one total
        # wall-clock budget. Without this, a bad provider window could keep a
        # single user request alive for minutes (16 models x llm_timeout).
        models_to_try = models_to_try[: max(1, settings.llm_max_fallback_models)]
        deadline = time.monotonic() + settings.llm_total_deadline_seconds

        errors_encountered = []
        for model in models_to_try:
            if time.monotonic() >= deadline:
                errors_encountered.append(
                    f"(stopped: {settings.llm_total_deadline_seconds:.0f}s total "
                    f"deadline reached before trying {model})"
                )
                break

            # Explicit membership, not a name-shape guess (a Groq model can
            # itself contain "/" — see the incident note on
            # self._openrouter_models in __init__).
            is_or = model in self._openrouter_models

            # Check key and availability before querying
            if is_or and not settings.openrouter_api_key:
                continue
            if not is_or and (not self.groq_api_key or LLM_LIMIT_EXCEEDED):
                continue
            # Skip Groq models while the Groq circuit is OPEN — don't burn a
            # slot (and a timeout) on a provider we already know is down; the
            # OpenRouter models later in the list are still worth trying.
            if not is_or and not await groq_breaker.allow():
                errors_encountered.append(f"{model}: skipped (groq circuit OPEN)")
                continue

            print(f"[LLMService] Attempting query with model: {model} ...", flush=True)
            try:
                if is_or:
                    return await self.query_openrouter(
                        messages=messages,
                        model=model,
                        temperature=temperature,
                        max_tokens=max_tokens,
                        response_format=response_format,
                        tools=tools,
                        tool_choice=tool_choice,
                    )
                else:
                    payload = {
                        "model": model,
                        "messages": messages,
                        "temperature": temperature,
                    }
                    if max_tokens is not None:
                        payload["max_tokens"] = max_tokens
                    if response_format is not None:
                        payload["response_format"] = response_format
                    if tools is not None:
                        payload["tools"] = tools
                    if tool_choice is not None:
                        payload["tool_choice"] = tool_choice

                    client = get_http_client()
                    resp = await client.post(
                        self.groq_base_url,
                        headers={
                            "Authorization": f"Bearer {self.groq_api_key}",
                            "Content-Type": "application/json",
                        },
                        json=payload,
                        timeout=httpx.Timeout(
                            settings.llm_timeout_seconds, connect=5.0
                        ),
                    )

                    if resp.status_code == 200:
                        data = resp.json()
                        msg = data["choices"][0]["message"]
                        content = msg.get("content")
                        tool_calls = msg.get("tool_calls")
                        if not content and not tool_calls:
                            # Same defensive check as query_openrouter — a 200
                            # with no usable content should fall through to
                            # the next model, not be treated as success.
                            raise Exception(
                                f"Model {model} returned 200 with an empty completion."
                            )
                        await groq_breaker.record_success()
                        return LLMResponse(
                            content=content, tool_calls=tool_calls, model_used=model
                        )
                    else:
                        err_msg = f"Groq returned {resp.status_code}: {resp.text[:200]}"
                        print(f"[LLMService] {err_msg}", flush=True)
                        if resp.status_code in [401, 403, 429]:
                            set_llm_limit_exceeded(True)
                        raise Exception(err_msg)

            except CircuitBreakerOpenError as e:
                errors_encountered.append(f"{model}: {e}")
            except Exception as e:
                print(f"[LLMService] Exception for model {model}: {e}", flush=True)
                errors_encountered.append(f"{model}: {e}")
                if not is_or:
                    await groq_breaker.record_failure(e)

        # If we got here, all attempted models failed
        raise Exception(
            f"LLM query failed across all attempted models. Errors: {'; '.join(errors_encountered)}"
        )
