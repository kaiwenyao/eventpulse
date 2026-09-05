"""应用配置：全部来自环境变量（12-factor），进程内不保存会话状态。

LLM_API_KEY 只出现在本服务的 Secret 中；Spring Boot 与数据库凭证都不会
进入这个进程。
"""

from functools import lru_cache

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="", extra="ignore")

    # ---- 外部 LLM ----
    llm_provider: str = "openai"
    llm_model: str = "gpt-4o-mini"
    llm_api_key: str = ""
    llm_base_url: str = ""
    llm_timeout_seconds: float = 30.0
    # reasoning 模型的思考 token 计入输出预算：1024 会在思考阶段耗尽，
    # 返回空 content 且没有 tool_calls（finish_reason=length）。
    llm_max_output_tokens: int = 4096

    # ---- Spring Boot 内部接口 ----
    backend_internal_url: str = "http://localhost:8080"
    backend_service_token: str = "dev-ai-internal-token"

    # ---- Spring Boot 调用本服务的服务间凭证 ----
    service_token: str = "dev-ai-service-token"

    # ---- Agent / 输入限制 ----
    agent_max_tool_calls: int = 6
    # 工具级预算：只约束工具调用（软停止），不约束 LLM 调用。
    agent_time_budget_seconds: float = 25.0
    # 整轮（含所有 LLM 调用）的墙上时钟死线。单次 LLM 调用最长 30s
    # （llm_timeout_seconds，且 max_retries=0）：最坏 45 + 30 + 6 ≈ 81s，
    # 仍低于 Spring 侧 AI_READ_TIMEOUT 的 90s，让 Spring 能拿到真实结果或
    # 明确降级，而不是在 Python 还在跑时就先超时。
    agent_total_budget_seconds: float = 45.0
    # 最终答案文本按「信封内 answer 字段」逐字流给浏览器。为防模型在信封之外
    # 先写一段散文、或用大块 reasoning token 拖时间，流式提取只在进入 answer
    # 字段的字符串值之后才允许放行；下面是给「尚未进入该字段」的中间内容（前缀
    # 键名、events JSON 等）的保留缓冲，超过即整体放弃流式、退回整块一次性返回。
    stream_answer_budget_chars: int = 1200
    max_history_messages: int = 8
    max_input_chars: int = 2000
    max_tool_results: int = 20
    tool_timeout_seconds: float = 6.0

    # ---- 返回限制 ----
    max_events_returned: int = 10
    max_answer_chars: int = 2000
    max_reason_chars: int = 200
    max_follow_ups: int = 3


@lru_cache
def get_settings() -> Settings:
    return Settings()


def llm_configured(settings: Settings) -> bool:
    """只有 provider 受支持且配置了 API Key 时才算可用；缺失时接口明确
    返回「AI 不可用」，绝不模拟回答。"""
    return bool(settings.llm_api_key.strip()) and settings.llm_provider == "openai"
