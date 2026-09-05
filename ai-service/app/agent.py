"""活动发现 Agent：LangChain create_agent + 受控工具。

最终答案必须可校验：模型最后一条消息输出 JSON；其中 events[].event_id 会
与本次工具真实返回的 id 集合比对，编造的 id 直接丢弃；解析失败时降级为
“只有自然语言回答”。
"""

import json
import re
import time
from typing import Any

from langchain.agents import create_agent
from langchain.agents.middleware import wrap_model_call
from langchain_core.callbacks import BaseCallbackHandler
from langchain_core.language_models.chat_models import BaseChatModel
from langchain_core.messages import AIMessage, HumanMessage
from langchain_core.tools import BaseTool
from pydantic import BaseModel, Field

from .backend_client import BackendClient
from .chains import _content_to_text, extract_json
from .prompts import (
    DISCOVERY_SYSTEM_PROMPT,
    discovery_context,
    ui_language_context,
    user_preferences_context,
)
from .schemas import DiscoveryChatRequest, DiscoveryEventRef
from .streaming import EnvelopeStreamExtractor
from .tools import ToolLedger, ToolLimitExceeded, build_tools


class AgentExecutionError(Exception):
    """Agent 整体失败（超时 / 工具失败 / 次数超限）：返回明确降级提示。"""


class AgentBudgetExceeded(Exception):
    """整轮墙上时钟预算耗尽（覆盖所有 LLM 调用；工具级软停止由 ToolLedger 负责）。"""


class _DeadlineGuard(BaseCallbackHandler):
    """每次 LLM 调用开始前检查整体死线。

    LangGraph 无法在图执行中途打断，只能在下一个节点入口抛错止损：
    否则 Spring 读超时已把 503 返回给用户后，Python 进程还会继续
    烧完剩余步数的 token。raise_error 默认 True，异常会向上传播。
    """

    def __init__(self, deadline: float) -> None:
        self.deadline = deadline

    def on_chat_model_start(self, serialized: Any, messages: Any, **kwargs: Any) -> None:
        if time.monotonic() >= self.deadline:
            raise AgentBudgetExceeded("agent total time budget exceeded")


class DiscoveryAnswer(BaseModel):
    answer: str = ""
    events: list[DiscoveryEventRef] = Field(default_factory=list)
    follow_up_questions: list[str] = Field(default_factory=list)


# 只在结构化解析失败时用：从坏掉的信封里捞出 answer 字段的字符串值。
_ANSWER_FIELD = re.compile(r'"answer"\s*:\s*"((?:[^"\\]|\\.)*)"', re.DOTALL)
# 判断一段文本是不是「本来想当 JSON 信封」的产物。
_LOOKS_LIKE_ENVELOPE = re.compile(r'^\s*(?:```[a-zA-Z]*\s*)?\{.*"(?:answer|events|follow_up_questions)"', re.DOTALL)


def _salvage_answer(text: str) -> str | None:
    """信封解析失败时抢救 answer 文本；抢救不到返回 None。"""
    match = _ANSWER_FIELD.search(text)
    if not match:
        return None
    try:
        # 用 JSON 自己的转义规则还原 \n、\" 等，避免把转义符原样给用户。
        salvaged = json.loads(f'"{match.group(1)}"')
    except ValueError:
        return None
    return salvaged.strip() or None


def parse_discovery_answer(text: str, allowed_event_ids: set[int], max_events: int) -> DiscoveryAnswer:
    """把模型的最终文本解析成可校验的答案。

    - JSON 合法：events 过滤到 allowed_event_ids（工具真实返回过的 id）。
    - JSON 不合法但看得出是坏掉的信封：只抢救 answer 文本，不带活动卡片
      （宁缺毋滥）。绝不把原始 JSON 直接当回答展示给用户。
    - 完全不是 JSON：整段文本就是模型的自然语言回答，原样使用。
    """
    raw = (text or "").strip()
    parsed = extract_json(raw)
    if parsed is None:
        if _LOOKS_LIKE_ENVELOPE.match(raw):
            salvaged = _salvage_answer(raw)
            # 抢救失败时返回空 answer，交给调用方走统一的降级文案。
            return DiscoveryAnswer(answer=(salvaged or "")[:2000], events=[], follow_up_questions=[])
        return DiscoveryAnswer(answer=raw[:2000], events=[], follow_up_questions=[])

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


def _invoke_agent(agent, history, request, *, recursion_limit, deadline):
    """执行一次 Agent；空回复（如 reasoning 模型思考耗尽输出预算）时重试一次。

    空响应重试对用户不可见：仍走同样的工具与预算约束，第二次仍然空就
    交给上层按降级处理，绝不编造内容。每次尝试前与每次 LLM 调用开始
    （_DeadlineGuard）都检查整体死线。
    """
    guard = _DeadlineGuard(deadline=deadline)
    for attempt in range(2):
        if time.monotonic() >= deadline:
            raise AgentBudgetExceeded("agent total time budget exceeded")
        result = agent.invoke(
            {"messages": [*history, HumanMessage(content=request.message)]},
            config={"recursion_limit": recursion_limit, "callbacks": [guard]},
        )
        messages = result.get("messages", [])
        final = messages[-1] if messages else None
        final_text = _content_to_text(getattr(final, "content", None)) if final else ""
        if final_text.strip() or getattr(final, "tool_calls", None):
            return result
    return result


@wrap_model_call
def _auto_tool_choice(request, handler):  # noqa: ANN001, ANN202
    """每次模型调用显式带上 tool_choice="auto"。

    create_agent 默认传 tool_choice=None，请求 payload 里就没有这个字段，
    行为取决于网关自己的默认值；而 LLM_BASE_URL 允许指向任意 OpenAI 兼容
    网关（结构化输出那条链就因兼容性选了 function_calling）。显式写 auto
    是唯一跨网关稳定的取值：模型自己决定「直接回答还是先调工具」，寒暄类
    问题不必被强制空跑一次工具调用。
    """
    return handler(request.override(tool_choice="auto"))


def _prepare_agent(model, settings, request, client):
    """构造 agent、账本、历史与死线（供同步/流式两条路径共用）。"""
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
    # 用户消息太短（只有地名、数字、emoji）时模型判断不出语言，会被中文提示词
    # 带偏；界面语言就是这种情况下唯一可靠的依据。未知 locale 返回空串，跳过。
    language_block = ui_language_context(request.locale)
    if language_block:
        system = system + "\n\n" + language_block
    # 偏好由 Spring 随请求带过来（它本来就持有这张表），省掉「模型决定调工具 +
    # 一次 HTTP 往返」。get_my_preferences 工具保留，模型需要时仍可显式重查。
    preferences_block = user_preferences_context(request.preferences)
    if preferences_block:
        system = system + "\n\n" + preferences_block
    agent = create_agent(model, tools=tools, system_prompt=system, middleware=[_auto_tool_choice])
    deadline = time.monotonic() + settings.agent_total_budget_seconds

    # Spring 可能传 null 内容：归一化成空串，避免 HumanMessage(content=None)。
    # assistant 行必须还原成 AIMessage：全部当 user 输入会让模型把上一轮
    # 自己的回答当成新指令，多轮上下文断裂。
    history = [
        AIMessage(content=m.content or "")
        if m.role == "assistant"
        else HumanMessage(content=m.content or "")
        for m in request.history
    ]
    return {
        "ledger": ledger,
        "tools": tools,
        "agent": agent,
        "history": history,
        "deadline": deadline,
    }


def _error_to_execution_error(exc: BaseException) -> AgentExecutionError:
    if isinstance(exc, ToolLimitExceeded):
        return AgentExecutionError(str(exc))
    if isinstance(exc, AgentBudgetExceeded):
        return AgentExecutionError("agent total time budget exceeded")
    # 网络 / 模型 / 工具异常
    return AgentExecutionError(f"{type(exc).__name__}")


def _usage_of_messages(messages: list[Any]) -> dict[str, int]:
    usage: dict[str, int] = {"input_tokens": 0, "output_tokens": 0}
    for message in messages:
        u = getattr(message, "usage_metadata", None)
        if isinstance(u, dict):
            usage["input_tokens"] += int(u.get("input_tokens") or 0)
            usage["output_tokens"] += int(u.get("output_tokens") or 0)
    return usage


def run_discovery_agent(
    model: BaseChatModel,
    settings: Any,
    request: DiscoveryChatRequest,
    client: BackendClient,
) -> tuple[DiscoveryAnswer, dict[str, int], int]:
    """跑一次发现 Agent。

    返回 (答案, token用量, 工具调用次数)。整体失败抛 AgentExecutionError。
    """
    prepared = _prepare_agent(model, settings, request, client)
    ledger = prepared["ledger"]
    agent = prepared["agent"]
    history = prepared["history"]
    deadline = prepared["deadline"]
    try:
        result = _invoke_agent(
            agent, history, request,
            recursion_limit=settings.agent_max_tool_calls * 3 + 10,
            deadline=deadline,
        )
    except Exception as exc:
        raise _error_to_execution_error(exc) from exc

    messages = result.get("messages", [])
    if not messages:
        raise AgentExecutionError("agent returned no messages")
    final = messages[-1]
    if not isinstance(final, AIMessage):
        # 预算 / 递归耗尽时可能停在工具消息上：工具原始 JSON 不能当面向用户
        # 的回答（否则原始查询结果会原样出现在聊天里），按整体失败降级。
        raise AgentExecutionError(f"agent ended on {type(final).__name__}")
    text = _content_to_text(final.content)
    answer = parse_discovery_answer(text, ledger.allowed_event_ids, settings.max_events_returned)
    # answer 为空时不在这里补中文兜底：语言应跟随用户，统一由 Spring 侧
    # 的降级文案（前端可本地化）处理，避免两层各写一句、语言还对不上。
    return answer, _usage_of_messages(messages), ledger.calls



def _has_tool_work(message: Any) -> bool:
    """消息是否携带工具调用（整块 tool_calls 或流式 tool_call_chunks）。"""
    return bool(getattr(message, "tool_calls", None) or getattr(message, "tool_call_chunks", None))


def stream_discovery_agent(
    model: BaseChatModel,
    settings: Any,
    request: DiscoveryChatRequest,
    client: BackendClient,
):
    """流式跑一次发现 Agent。

    生成器事件：
    - ("delta", text)：最终答案信封里 answer 字段值的字符（已解转义），
      可以逐字推给浏览器；
    - ("result", DiscoveryAnswer, usage dict, tool_calls int)：权威收尾，
      覆盖任何已放行的增量（events / follow_up_questions 只在这里出现）。

    整体失败抛 AgentExecutionError（同同步路径的语义）：包括进入生成器
    之前的死线检查、图中的每次 LLM / 工具调用、以及两次空回复。
    """
    try:
        yield from _stream_agent_events(model, settings, request, client)
    except Exception as exc:
        raise _error_to_execution_error(exc) from exc


def _stream_agent_events(
    model: BaseChatModel,
    settings: Any,
    request: DiscoveryChatRequest,
    client: BackendClient,
):
    """流式 Agent 的实际事件生成器（异常由外层包装器统一映射）。

    空回复重试一次：与 _invoke_agent 一致——只有在【没有任何字符被放行】时
    重试才是安全的，否则用户已经看到部分内容，重试会把同一个回答从头再答一遍。
    """
    prepared = _prepare_agent(model, settings, request, client)
    ledger = prepared["ledger"]
    agent = prepared["agent"]
    history = prepared["history"]
    deadline = prepared["deadline"]
    recursion_limit = settings.agent_max_tool_calls * 3 + 10

    for attempt in range(2):
        if time.monotonic() >= deadline:
            raise AgentBudgetExceeded("agent total time budget exceeded")
        guard = _DeadlineGuard(deadline=deadline)
        extractor = EnvelopeStreamExtractor(
            lead_budget_chars=int(settings.stream_answer_budget_chars)
        )
        forwarded_any = False
        final_state: dict[str, Any] | None = None
        for mode, chunk in agent.stream(
            {"messages": [*history, HumanMessage(content=request.message)]},
            stream_mode=["messages", "values"],
            config={"recursion_limit": recursion_limit, "callbacks": [guard]},
        ):
            if mode == "messages":
                message, metadata = chunk
                # 只放行 model 节点上不带工具调用的文本增量；工具轮次与
                # 思考内容要么是空 content、要么带 tool_call_chunks，天然跳过。
                if (
                    metadata.get("langgraph_node") == "model"
                    and not _has_tool_work(message)
                ):
                    text = _content_to_text(message.content)
                    if text and not extractor.abandoned:
                        emitted = extractor.feed(text)
                        if emitted:
                            forwarded_any = True
                            yield ("delta", emitted)
            else:
                final_state = chunk

        messages = (final_state or {}).get("messages", [])
        final = messages[-1] if messages else None
        if not isinstance(final, AIMessage):
            # 预算 / 递归耗尽时可能停在工具消息上：工具原始 JSON 不能当面向用户
            # 的回答，按整体失败降级（语义与同步路径一致）。
            raise AgentExecutionError(f"agent ended on {type(final).__name__}")
        final_text = _content_to_text(final.content)
        if (
            not forwarded_any
            and not final_text.strip()
            and not _has_tool_work(final)
        ):
            # 空回复（reasoning 模型思考耗尽输出预算）：对用户不可见地重试一次。
            continue

        answer = parse_discovery_answer(
            final_text, ledger.allowed_event_ids, settings.max_events_returned
        )
        yield ("result", answer, _usage_of_messages(messages), ledger.calls)
        return

    # 两次都空：交给上层按降级处理，绝不编造内容。
    raise AgentExecutionError("agent returned empty responses")


def event_ids_from_response(events: list[DiscoveryEventRef]) -> list[int]:
    return [e.event_id for e in events]
