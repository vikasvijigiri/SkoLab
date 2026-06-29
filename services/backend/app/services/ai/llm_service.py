import time
import httpx
from typing import List, Dict, Any, Optional
from openrouter import OpenRouter
from app.core.config import settings


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

        # Prioritized fallback model order. If one fails, the loop continues to the next.
        self.default_models = [
            "openrouter/owl-alpha",
            # Groq models (Primary, fast)
            "llama-3.3-70b-versatile",
            "llama-3.1-8b-instant",
            "llama3-8b-8192",
            "mixtral-8x7b-32768",
            "gemma2-9b-it",
            # OpenRouter free models (Active slugs)
            "google/gemma-4-31b-it:free",
            "meta-llama/llama-3.3-70b-instruct:free",
            "meta-llama/llama-3.2-3b-instruct:free",
            "openai/gpt-oss-120b:free",
            # OpenRouter standard paid models (Fallback options)
            "meta-llama/llama-3.3-70b-instruct",
            "google/gemma-2-9b-it",
            "qwen/qwen-2.5-72b-instruct",
            "meta-llama/llama-3-8b-instruct",
            "openai/gpt-4o-mini",
            # OpenRouter auto-router (Catch-all)
            "openrouter/auto",
        ]

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

                return LLMResponse(
                    content=content, tool_calls=tool_calls_dict, model_used=model
                )
            else:
                return LLMResponse(content=str(resp), model_used=model)
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
            models_to_try = self.default_models

        errors_encountered = []
        for model in models_to_try:
            is_or = "/" in model and not model.startswith("groq/")

            # Check key and availability before querying
            if is_or and not settings.openrouter_api_key:
                continue
            if not is_or and (not self.groq_api_key or LLM_LIMIT_EXCEEDED):
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

                    async with httpx.AsyncClient(
                        timeout=httpx.Timeout(settings.llm_timeout_seconds, connect=5.0)
                    ) as client:
                        resp = await client.post(
                            self.groq_base_url,
                            headers={
                                "Authorization": f"Bearer {self.groq_api_key}",
                                "Content-Type": "application/json",
                            },
                            json=payload,
                        )

                    if resp.status_code == 200:
                        data = resp.json()
                        msg = data["choices"][0]["message"]
                        content = msg.get("content")
                        tool_calls = msg.get("tool_calls")
                        return LLMResponse(
                            content=content, tool_calls=tool_calls, model_used=model
                        )
                    else:
                        err_msg = f"Groq returned {resp.status_code}: {resp.text[:200]}"
                        print(f"[LLMService] {err_msg}", flush=True)
                        if resp.status_code in [401, 403, 429]:
                            set_llm_limit_exceeded(True)
                        raise Exception(err_msg)

            except Exception as e:
                print(f"[LLMService] Exception for model {model}: {e}", flush=True)
                errors_encountered.append(f"{model}: {e}")

        # If we got here, all attempted models failed
        raise Exception(
            f"LLM query failed across all attempted models. Errors: {'; '.join(errors_encountered)}"
        )
