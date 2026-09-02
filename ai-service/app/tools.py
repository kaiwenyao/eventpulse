"""Agent 可用的受控只读工具。

- 每次请求动态构建：userId 不由模型决定，而来自 Spring Boot 签名的用户
  上下文（放在 BackendClient 的请求头里）；游客不提供个人工具。
- 工具只有查询；没有 SQL、没有 URL 任取、没有系统命令、没有业务写操作。
- ToolLedger 统一限制调用次数与总耗时，超限后工具拒绝服务。
"""

import json
import time
from typing import Any

from langchain_core.tools import BaseTool, tool
from pydantic import BaseModel, Field

from .backend_client import BackendClient, ToolError, dumps


class ToolLimitExceeded(Exception):
    """达到工具调用次数或总时间预算。

    注意：这个异常不应抛给 Agent 框架让整轮失败——工具包装层会把它转成
    一条“请基于已有结果回答”的工具消息（软停止）。保留异常类型供
    run_discovery_agent 的整体兜底使用。
    """

    SOFT_STOP = (
        '{"error": "tool_limit", '
        '"instruction": "工具调用次数或时间预算已用完。不要再调用任何工具，'
        '请基于已经获得的查询结果直接输出最终 JSON 回答；如果信息不够，'
        '就在 answer 里如实说明并给出追问建议。"}'
    )


class ToolLedger:
    """一次 Agent 请求内的调用账本：次数、时间预算，以及“工具真实返回过的
    活动 id”。最终答案里出现的事件 id 必须来自这个集合。"""

    def __init__(self, max_calls: int, time_budget_seconds: float) -> None:
        self.max_calls = max_calls
        self.deadline = time.monotonic() + time_budget_seconds
        self.calls = 0
        self.allowed_event_ids: set[int] = set()

    def begin_call(self) -> None:
        if self.calls >= self.max_calls:
            raise ToolLimitExceeded(f"tool call limit reached ({self.max_calls})")
        if time.monotonic() > self.deadline:
            raise ToolLimitExceeded("tool time budget exceeded")
        self.calls += 1

    def record_event_ids(self, events: list[dict[str, Any]]) -> None:
        for event in events:
            event_id = event.get("id")
            if isinstance(event_id, int):
                self.allowed_event_ids.add(event_id)


class SearchArgs(BaseModel):
    q: str | None = Field(default=None, description="关键词，匹配标题或摘要")
    city: str | None = Field(default=None, description="城市名，例如 Shanghai 或 上海")
    category: str | None = Field(default=None, description="活动类别，例如 music / tech / sports / food / art")
    date_from: str | None = Field(default=None, description="开始时间下限，ISO 8601，例如 2026-09-05T00:00:00Z")
    date_to: str | None = Field(default=None, description="开始时间上限，ISO 8601")
    max_price_cents: int | None = Field(default=None, description="单票最高价（分），免费活动传 0")
    min_price_cents: int | None = Field(default=None, description="单票最低价（分）")
    has_remaining: bool | None = Field(default=None, description="是否只看还有余票的活动")
    limit: int = Field(default=10, ge=1, le=20, description="最多返回条数")


class NearbyArgs(BaseModel):
    lat: float = Field(description="纬度")
    lng: float = Field(description="经度")
    radius_km: float = Field(default=20, ge=0.1, le=100, description="搜索半径（公里），最大 100")
    limit: int = Field(default=10, ge=1, le=20)


class EventIdArgs(BaseModel):
    event_id: int = Field(ge=0, description="活动 id，必须来自工具查询结果")


class LimitArgs(BaseModel):
    limit: int = Field(default=8, ge=1, le=20)


def _guarded(ledger: ToolLedger, func):
    """达到次数 / 时间预算时软停止：返回指令让模型用已有结果收尾，
    而不是抛异常毁掉整轮对话；查询失败同样转成如实说明。"""

    def wrapped(*args: Any, **kwargs: Any) -> str:
        try:
            ledger.begin_call()
            return func(*args, **kwargs)
        except ToolLimitExceeded:
            return ToolLimitExceeded.SOFT_STOP
        except ToolError as exc:
            return json.dumps(
                {"error": "tool_failed", "detail": str(exc),
                 "instruction": "这次查询失败了。不要编造活动；在 answer 里如实说明暂时无法查询，"
                 "并输出最终 JSON。"},
                ensure_ascii=False,
            )

    return wrapped


def build_tools(client: BackendClient, ledger: ToolLedger) -> list[BaseTool]:
    """公开工具任何请求都有；个人工具只在带用户上下文时出现。"""

    @tool("search_published_events", args_schema=SearchArgs)
    def search_published_events(**kwargs: Any) -> str:
        """按关键词、城市、类别、日期范围、价格区间搜索平台已发布的活动。"""
        events = client.search_events(**kwargs)
        ledger.record_event_ids(events)
        return dumps(events)

    @tool("find_nearby_events", args_schema=NearbyArgs)
    def find_nearby_events(**kwargs: Any) -> str:
        """按坐标与半径查找附近的活动。用户没给位置时先向用户询问坐标或城市。"""
        events = client.nearby_events(**kwargs)
        ledger.record_event_ids(events)
        return dumps(events)

    @tool("get_popular_events", args_schema=LimitArgs)
    def get_popular_events(**kwargs: Any) -> str:
        """查询当前平台上的热门活动（不需要筛选条件）。"""
        events = client.popular_events(**kwargs)
        ledger.record_event_ids(events)
        return dumps(events)

    @tool("get_event_details", args_schema=EventIdArgs)
    def get_event_details(**kwargs: Any) -> str:
        """查看某个活动的时间、地点、价格、余票与说明。event_id 必须来自本次其他工具的返回结果。"""
        event = client.get_event(**kwargs)
        ledger.record_event_ids([event])
        return dumps(event)

    tools: list[BaseTool] = [
        search_published_events,
        find_nearby_events,
        get_popular_events,
        get_event_details,
    ]

    if client._context_token:
        @tool("get_my_preferences")
        def get_my_preferences() -> str:
            """读取当前登录用户主动保存的偏好（类别、城市、默认位置）。未登录时不可用。"""
            return dumps(client.my_preferences())

        @tool("get_my_recent_categories")
        def get_my_recent_categories() -> str:
            """汇总当前登录用户近期感兴趣的活动类别（只返回类别与次数）。未登录时不可用。"""
            return dumps(client.my_recent_categories())

        tools.extend([get_my_preferences, get_my_recent_categories])

    # 软停止与失败兜底挂在 StructuredTool.func 上：保留 name / args_schema / description。
    for t in tools:
        t.func = _guarded(ledger, t.func)

    return tools


__all__ = [
    "ToolLedger",
    "ToolLimitExceeded",
    "ToolError",
    "build_tools",
]
