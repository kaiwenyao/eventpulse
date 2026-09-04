"""LLM 客户端工厂：目前支持 OpenAI 及 OpenAI 兼容接口（可配 base_url）。"""

from typing import Any

from langchain_core.language_models.chat_models import BaseChatModel


class LlmNotConfigured(Exception):
    """未配置 API Key 或 provider 不受支持：明确报「AI 不可用」，不模拟回答。"""


def build_chat_model(settings: Any) -> BaseChatModel:
    if not settings.llm_api_key.strip():
        raise LlmNotConfigured("LLM_API_KEY is not configured")
    if settings.llm_provider != "openai":
        raise LlmNotConfigured(f"unsupported LLM_PROVIDER: {settings.llm_provider}")

    from langchain_openai import ChatOpenAI

    kwargs: dict[str, Any] = {
        "model": settings.llm_model,
        "api_key": settings.llm_api_key,
        "timeout": settings.llm_timeout_seconds,
        # 单次调用必须被 timeout 硬性约束，重试交给上层按降级处理：
        # max_retries=1 时一次调用最坏 60s，整轮死线 agent_total_budget_seconds
        # 就压不住 Spring 的 90s 读超时了。
        "max_retries": 0,
        "temperature": 0.7,
        "max_tokens": settings.llm_max_output_tokens,
    }
    if settings.llm_base_url.strip():
        kwargs["base_url"] = settings.llm_base_url.strip()
    return ChatOpenAI(**kwargs)
