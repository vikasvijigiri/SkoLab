"""Regression coverage for the 2026-09-04 dead-model incident.

Groq decommissioned/removed every one of the 5 Groq models this app's LLM
fallback chain used to hardcode as string literals across 6 call sites in
5 files (llm_service.py, pipeline/base.py, data/scraping_service.py,
ai/agent_service.py, endpoints/system.py, endpoints/feed.py) — confirmed
live against the Groq API with this deployment's own key, not assumed.
With LLM_MAX_FALLBACK_MODELS capping the attempt loop at 4, every attempt
was Groq and every one was dead: 100% of LLM calls failed, silently,
before ever reaching an OpenRouter fallback. is_llm_working() (what
/ai_status reports) only checks that GROQ_API is configured, not that the
configured model is actually callable, so the health check kept reporting
llm_active: true throughout.

These tests don't hit the real Groq API (that would make CI flaky and
burn real quota on every run) — they verify the structural fix instead:
every model choice now comes from Settings (env-configurable), never a
literal in a call site, and the specific decommissioned identifiers can
never silently creep back into a default.
"""

from types import SimpleNamespace

from app.core.config import Settings

# The exact 5 Groq model identifiers confirmed dead on 2026-09-04, plus
# the one hardcoded elsewhere (agent_service.py's fast-model override).
# `model_not_found`: llama-3.3-70b-versatile, llama-3.1-8b-instant.
# `model_decommissioned`: llama3-8b-8192, mixtral-8x7b-32768, gemma2-9b-it.
KNOWN_DEAD_MODELS = {
    "llama-3.3-70b-versatile",
    "llama-3.1-8b-instant",
    "llama3-8b-8192",
    "mixtral-8x7b-32768",
    "gemma2-9b-it",
}


def test_no_known_dead_model_in_the_default_configuration(monkeypatch):
    """The specific identifiers confirmed dead on 2026-09-04 must never be
    the *default* llm_primary_model / llm_fast_model / llm_fallback_models
    — that default is what a fresh deploy actually uses when no LLM_*
    env var is set on Render."""
    for var in ("LLM_PRIMARY_MODEL", "LLM_FAST_MODEL", "LLM_FALLBACK_MODELS"):
        monkeypatch.delenv(var, raising=False)
    settings = Settings()

    assert settings.llm_primary_model not in KNOWN_DEAD_MODELS
    assert settings.llm_fast_model not in KNOWN_DEAD_MODELS
    fallback_list = [m.strip() for m in settings.llm_fallback_models.split(",")]
    dead_in_fallback = KNOWN_DEAD_MODELS & set(fallback_list)
    assert not dead_in_fallback, f"known-dead model(s) still in the default chain: {dead_in_fallback}"


def test_llm_fallback_models_is_env_overridable(monkeypatch):
    """The chain is real config, not a hardcoded list — this is the whole
    point of the fix: the next Groq deprecation is a Render env var
    change, not a multi-file code deploy."""
    monkeypatch.setenv("LLM_FALLBACK_MODELS", "model-a, model-b ,model-c")

    from app.services.ai import llm_service as llm_service_mod

    monkeypatch.setattr(llm_service_mod, "settings", Settings())
    service = llm_service_mod.LLMService()
    assert service.default_models == ["model-a", "model-b", "model-c"]


def test_pipeline_base_model_comes_from_settings_not_a_literal(monkeypatch):
    fake_settings = SimpleNamespace(llm_primary_model="some/configured-model")

    from app.services.platform.pipeline import base as base_mod

    monkeypatch.setattr(base_mod, "settings", fake_settings)
    pipeline = base_mod._PipelineBase(db=None)

    assert pipeline.model == "some/configured-model"
    assert pipeline.model not in KNOWN_DEAD_MODELS


async def test_ai_status_reports_the_real_configured_model(monkeypatch):
    from app.api.v1.endpoints import system as system_mod

    monkeypatch.setattr(
        system_mod, "settings", SimpleNamespace(llm_primary_model="some/other-model")
    )
    result = await system_mod.ai_status()

    assert result["model"] == "some/other-model"
    assert result["model"] not in KNOWN_DEAD_MODELS


def test_scraping_service_model_comes_from_settings_not_a_literal(monkeypatch):
    fake_settings = SimpleNamespace(llm_primary_model="some/configured-model")

    from app.services.data import scraping_service as scraping_mod

    monkeypatch.setattr(scraping_mod, "settings", fake_settings)
    service = scraping_mod.ScrapingService()

    assert service.model == "some/configured-model"
    assert service.model not in KNOWN_DEAD_MODELS


def test_agent_service_fast_model_comes_from_settings_not_a_literal():
    """agent_service.py's summarizer used to hardcode
    models=["llama-3.1-8b-instant"] (also confirmed dead) at the call
    site itself, bypassing self.model entirely. Source-level guard since
    that call is deep inside a private method with its own DB/LLM
    dependencies not worth mocking just to reach it."""
    import inspect

    from app.services.ai import agent_service as agent_mod

    source = inspect.getsource(agent_mod)
    for dead in KNOWN_DEAD_MODELS:
        assert f'"{dead}"' not in source and f"'{dead}'" not in source
    assert "settings.llm_fast_model" in source
