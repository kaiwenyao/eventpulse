"""线上（wire）Pydantic 模型：与 Spring Boot 之间统一 camelCase。

LLM 的原始输出永远不直接进响应：先解析、校验、裁剪成这些结构。
"""

from typing import Any

from pydantic import AliasGenerator, BaseModel, ConfigDict, Field
from pydantic.alias_generators import to_camel


class CamelModel(BaseModel):
    """camelCase 序列化 + 允许按字段名填充（Python 侧写 snake_case）。"""

    model_config = ConfigDict(populate_by_name=True, alias_generator=AliasGenerator(alias=to_camel), extra="ignore")


# ---- token 用量 ----

class Usage(CamelModel):
    input_tokens: int | None = None
    output_tokens: int | None = None


# ---- 主办方文案助手 ----

class ImproveEventRequest(CamelModel):
    """Spring 未填的字段会序列化成 null：全部可空，取值时归一化为空串。"""

    request_id: str = Field(max_length=64)
    title: str | None = Field(default=None, max_length=200)
    summary: str | None = Field(default=None, max_length=300)
    description: str | None = Field(default=None, max_length=5000)
    category: str | None = Field(default=None, max_length=50)
    city: str | None = Field(default=None, max_length=50)
    venue_name: str | None = Field(default=None, max_length=200)
    audience: str | None = Field(default=None, max_length=300)
    tone: str | None = Field(default=None, max_length=200)
    starts_at_iso: str | None = Field(default=None, max_length=40)
    price_cents: int | None = None


class Suggestion(CamelModel):
    title: str = Field(max_length=200)
    summary: str = Field(default="", max_length=300)
    description: str = Field(default="", max_length=5000)
    attendance_notes: str = Field(default="", max_length=1000)
    warnings: list[str] = Field(default_factory=list)


class ImproveEventResponse(CamelModel):
    request_id: str
    suggestion: Suggestion
    warnings: list[str] = Field(default_factory=list)
    provider: str
    model: str
    usage: Usage


# ---- 活动发现助手 ----

class HistoryMessage(CamelModel):
    role: str = Field(max_length=20)
    content: str | None = Field(default=None, max_length=2000)


class DiscoveryUser(CamelModel):
    user_id: int
    role: str = Field(default="USER", max_length=30)


class DiscoveryChatRequest(CamelModel):
    request_id: str = Field(max_length=64)
    message: str = Field(min_length=1, max_length=2000)
    history: list[HistoryMessage] = Field(default_factory=list, max_length=8)
    now_iso: str = Field(max_length=40)
    time_zone: str | None = Field(default=None, max_length=60)
    user: DiscoveryUser | None = None
    # 游客请求时 Spring 发 null；登录时是签名的用户上下文 token。
    user_context_token: str | None = Field(default=None, max_length=4096)


class DiscoveryEventRef(CamelModel):
    event_id: int = Field(ge=0)
    reason: str = Field(default="", max_length=200)


class DiscoveryChatResponse(CamelModel):
    request_id: str
    answer: str = Field(max_length=2000)
    events: list[DiscoveryEventRef] = Field(default_factory=list)
    follow_up_questions: list[str] = Field(default_factory=list)
    provider: str
    model: str
    usage: Usage


# ---- 工具返回给 Agent 的活动视图（来自 Spring Boot 的精简 JSON） ----

class ToolEvent(CamelModel):
    id: int
    title: str
    summary: str | None = None
    description: str | None = None
    category: str | None = None
    city: str | None = None
    venue_name: str | None = None
    address: str | None = None
    latitude: float | None = None
    longitude: float | None = None
    starts_at: str | None = None
    ends_at: str | None = None
    price_cents: int | None = None
    remaining: int | None = None
    status: str | None = None


def to_jsonable(value: Any) -> Any:
    """模型输出 / 工具结果统一经 Pydantic 校验后再序列化。"""
    return value
