"""活动发现 Agent：LangChain create_agent + 受控工具。

最终答案必须可校验：模型最后一条消息输出 JSON；其中 events[].event_id 会
与本次工具真实返回的 id 集合比对，编造的 id 直接丢弃；解析失败时降级为
“只有自然语言回答”。
"""

import re
import time
from typing import Any

from langchain.agents import create_agent
from langchain_core.language_models.chat_models import BaseChatModel
from langchain_core.messages import HumanMessage
from langchain_core.tools import BaseTool
from pydantic import BaseModel, Field

from .backend_client import BackendClient
from .chains import _content_to_text, extract_json
from .prompts import DISCOVERY_SYSTEM_PROMPT, discovery_context
from .schemas import DiscoveryChatRequest, DiscoveryEventRef
from .tools import ToolLedger, ToolLimitExceeded, build_tools


class AgentExecutionError(Exception):
    """Agent 整体失败（超时 / 工具失败 / 次数超限）：返回明确降级提示。"""


class DiscoveryAnswer(BaseModel):
    answer: str = ""
    events: list[DiscoveryEventRef] = Field(default_factory=list)
    follow_up_questions: list[str] = Field(default_factory=list)


_JSON_OBJECT = re.compile(r"\{.*\}", re.DOTALL)


def parse_discovery_answer(text: str, allowed_event_ids: set[int], max_events: int) -> DiscoveryAnswer:
    """把模型的最终文本解析成可校验的答案。

    - JSON 合法：events 过滤到 allowed_event_ids（工具真实返回过的 id）。
    - JSON 不合法：整段文本作为 answer，不带活动卡片（宁缺毋滥）。
    """
    parsed = extract_json(text or "")
    if parsed is None:
        answer = (text or "").strip()[:2000]
        return DiscoveryAnswer(answer=answer, events=[], follow_up_questions=[])

    answer = parsed.get("answer") if isinstance(parsed.get("answer"), str) else ""
    follow_ups = [
        q[:200] for q in parsed.get("follow_up_questions", []) if isinstance(q, str) and q.strip()
    ][:3]
    events: list[DiscoveryEventRef] = []
    raw_events = parsed.get("events")
    if isinstance(raw_events, list):
        for item in raw_events:
            if not isinstance(item, dict) or len(events) >= max_events:
                continue
            event_id = item.get("event_id", item.get("eventId"))
            if not isinstance(event_id, int) or isinstance(event_id, bool):
                continue
            if event_id not in allowed_event_ids:
                # 编造或未在本轮工具结果里出现过的 id 一律丢弃。
                continue
            reason = item.get("reason") if isinstance(item.get("reason"), str) else ""
            events.append(DiscoveryEventRef(event_id=event_id, reason=reason[:200]))
    return DiscoveryAnswer(answer=answer.strip()[:2000], events=events, follow_up_questions=follow_ups)


def _invoke_agent(agent, history, request, *, recursion_limit):
    """执行一次 Agent；空回复（如 reasoning 模型思考耗尽输出预算）时重试一次。

    空响应重试对用户不可见：仍走同样的工具与预算约束，第二次仍然空就
    交给上层按降级处理，绝不编造内容。
    """
    for attempt in range(2):
        result = agent.invoke(
            {"messages": [*history, HumanMessage(content=request.message)]},
            config={"recursion_limit": recursion_limit},
        )
        messages = result.get("messages", [])
        final = messages[-1] if messages else None
        final_text = _content_to_text(getattr(final, "content", None)) if final else ""
        if final_text.strip() or getattr(final, "tool_calls", None):
            return result
    return result


def run_discovery_agent(
    model: BaseChatModel,
    settings: Any,
    request: DiscoveryChatRequest,
    client: BackendClient,
) -> tuple[DiscoveryAnswer, dict[str, int], int]:
    """跑一次发现 Agent。

    返回 (答案, token用量, 工具调用次数)。整体失败抛 AgentExecutionError。
    """
    ledger = ToolLedger(
        max_calls=settings.agent_max_tool_calls,
        time_budget_seconds=settings.agent_time_budget_seconds,
    )
    tools: list[BaseTool] = build_tools(client, ledger)
    system = (
        DISCOVERY_SYSTEM_PROMPT
        + "\n\n"
        + discovery_context(request.now_iso, request.time_zone)
    )
    agent = create_agent(model, tools=tools, system_prompt=system)

    # Spring 可能传 null 内容：归一化成空串，避免 HumanMessage(content=None)。
    history = [HumanMessage(content=m.content or "", name=m.role) for m in request.history]
    start = time.monotonic()
    try:
        result = _invoke_agent(
            agent, history, request,
            recursion_limit=settings.agent_max_tool_calls * 3 + 10,
        )
    except ToolLimitExceeded as exc:
        raise AgentExecutionError(str(exc)) from exc
    except Exception as exc:  # 网络 / 模型 / 工具异常
        raise AgentExecutionError(f"{type(exc).__name__}") from exc

    messages = result.get("messages", [])
    if not messages:
        raise AgentExecutionError("agent returned no messages")
    final = messages[-1]
    text = _content_to_text(final.content)
    answer = parse_discovery_answer(text, ledger.allowed_event_ids, settings.max_events_returned)
    if not answer.answer:
        answer.answer = "这次没能整理出结果，请换个说法再试一次。"
    usage: dict[str, int] = {"input_tokens": 0, "output_tokens": 0}
    for message in messages:
        u = getattr(message, "usage_metadata", None)
        if isinstance(u, dict):
            usage["input_tokens"] += int(u.get("input_tokens") or 0)
            usage["output_tokens"] += int(u.get("output_tokens") or 0)
    elapsed = int((time.monotonic() - start) * 1000)
    return answer, usage, ledger.calls


def event_ids_from_response(events: list[DiscoveryEventRef]) -> list[int]:
    return [e.event_id for e in events]
