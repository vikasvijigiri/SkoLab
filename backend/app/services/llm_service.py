import os
import time
import httpx
from typing import List, Dict, Any, Optional
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
    has_groq = bool(os.getenv("GROQ_API"))
    has_openrouter = bool(os.getenv("OPENROUTER_API_KEY"))
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
        self.groq_api_key = os.getenv("GROQ_API")
        self.groq_base_url = "https://api.groq.com/openai/v1/chat/completions"

        # Default Groq model order
        self.default_models = [
            "llama-3.3-70b-versatile",
            "llama-3.1-8b-instant",
            "llama3-8b-8192",
            "mixtral-8x7b-32768",
            "gemma2-9b-it",
        ]

    async def query_openrouter(
        self,
        messages: List[Dict[str, str]],
        model: str = "google/gemma-4-31b-it:free",
        temperature: float = 0.5,
        max_tokens: Optional[int] = None,
        response_format: Optional[Dict[str, Any]] = None,
        tools: Optional[List[Dict[str, Any]]] = None,
        tool_choice: Optional[str] = None,
    ) -> LLMResponse:
        api_key = os.getenv("OPENROUTER_API_KEY", "")
        if not api_key:
            raise Exception("OpenRouter API key is missing.")

        headers = {
            "Authorization": f"Bearer {api_key}",
            "Content-Type": "application/json",
            "HTTP-Referer": settings.app_base_url,
            "X-Title": "SkoLab",
        }

        payload = {"model": model, "messages": messages, "temperature": temperature}
        if max_tokens is not None:
            payload["max_tokens"] = max_tokens
        if response_format is not None:
            payload["response_format"] = response_format
        if tools is not None:
            payload["tools"] = tools
        if tool_choice is not None:
            payload["tool_choice"] = tool_choice

        try:
            async with httpx.AsyncClient(
                timeout=httpx.Timeout(settings.llm_timeout_seconds, connect=5.0)
            ) as client:
                resp = await client.post(
                    "https://openrouter.ai/api/v1/chat/completions",
                    headers=headers,
                    json=payload,
                )

            if resp.status_code == 200:
                data = resp.json()
                choices = data.get("choices", [])
                if choices:
                    msg = choices[0].get("message", {})
                    content = msg.get("content")
                    tool_calls = msg.get("tool_calls")
                    return LLMResponse(
                        content=content, tool_calls=tool_calls, model_used=model
                    )
                else:
                    return LLMResponse(content=str(data), model_used=model)
            else:
                print(
                    f"[LLMService] OpenRouter returned status {resp.status_code}: {resp.text[:200]}",
                    flush=True,
                )
                raise Exception(f"OpenRouter returned status {resp.status_code}")
        except Exception as e:
            print(f"[LLMService] OpenRouter exception: {e}", flush=True)
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
        Primary LLM query entry point. Tries Groq models sequentially.
        If all fail, triggers OpenRouter fallback using 'google/gemma-4-31b-it:free'.
        """
        global LLM_LIMIT_EXCEEDED
        if not is_llm_working():
            raise Exception("LLM services are currently unavailable or rate-limited.")

        has_groq = bool(self.groq_api_key)
        if (not has_groq or LLM_LIMIT_EXCEEDED) and os.getenv("OPENROUTER_API_KEY"):
            print(
                "[LLMService] Groq unavailable/rate-limited. Querying OpenRouter directly...",
                flush=True,
            )
            return await self.query_openrouter(
                messages=messages,
                temperature=temperature,
                max_tokens=max_tokens,
                response_format=response_format,
                tools=tools,
                tool_choice=tool_choice,
            )

        # Try Groq models first
        models_to_try = models if models else self.default_models

        for model in models_to_try:
            if "/" in model and not model.startswith("groq/"):
                continue

            payload = {"model": model, "messages": messages, "temperature": temperature}
            if max_tokens is not None:
                payload["max_tokens"] = max_tokens
            if response_format is not None:
                payload["response_format"] = response_format
            if tools is not None:
                payload["tools"] = tools
            if tool_choice is not None:
                payload["tool_choice"] = tool_choice

            try:
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
                    print(
                        f"[LLMService] Groq model {model} returned status {resp.status_code}: {resp.text[:200]}",
                        flush=True,
                    )
                    if resp.status_code in [401, 403, 429]:
                        set_llm_limit_exceeded(True)
            except Exception as e:
                print(f"[LLMService] Exception for Groq model {model}: {e}", flush=True)

        if os.getenv("OPENROUTER_API_KEY"):
            print(
                "[LLMService] All Groq models failed. Attempting OpenRouter fallback...",
                flush=True,
            )
            try:
                try:
                    return await self.query_openrouter(
                        messages=messages,
                        model="google/gemma-4-31b-it:free",
                        temperature=temperature,
                        max_tokens=max_tokens,
                        response_format=response_format,
                        tools=tools,
                        tool_choice=tool_choice,
                    )
                except Exception as or_tool_err:
                    if tools is not None:
                        print(
                            f"[LLMService] OpenRouter with tools failed: {or_tool_err}. Retrying without tools...",
                            flush=True,
                        )
                        return await self.query_openrouter(
                            messages=messages,
                            model="google/gemma-4-31b-it:free",
                            temperature=temperature,
                            max_tokens=max_tokens,
                            response_format=response_format,
                        )
                    raise or_tool_err
            except Exception as or_err:
                print(f"[LLMService] OpenRouter fallback failed: {or_err}", flush=True)
                raise or_err

        raise Exception(
            "LLM query failed across all Groq models, and no OpenRouter fallback available."
        )
