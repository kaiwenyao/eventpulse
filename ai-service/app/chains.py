"""文案生成链：普通 LLM 调用 + 结构化输出校验（不用 Agent）。

LLM 的输出按不可信数据处理：解析失败时做一次有限重试，仍失败就抛
LlmOutputError，绝不把未校验的文本交给调用方。
"""

import json
from typing import Any

from langchain_core.language_models.chat_models import BaseChatModel
from langchain_core.messages import HumanMessage, SystemMessage
from pydantic import BaseModel, Field

from .prompts import IMPROVE_SYSTEM_PROMPT
from .schemas import Suggestion


class LlmOutputError(Exception):
    """模型连续两次没有给出合法的结构化输出。"""


class CopySuggestionOut(BaseModel):
    """模型应返回的内部结构（校验用）；长度限制在 to_suggestion 里统一裁剪，
    避免模型输出超长字段时校验直接失败而浪费重试。"""

    title: str = Field(default="")
    summary: str = Field(default="")
    description: str = Field(default="")
    attendance_notes: str = Field(default="")
    warnings: list[str] = Field(default_factory=list)

    def to_suggestion(self) -> Suggestion:
        return Suggestion(
            title=self.title.strip()[:200] or "（请让 AI 重新生成标题）",
            summary=self.summary.strip()[:300],
            description=self.description.strip()[:5000],
            attendance_notes=self.attendance_notes.strip()[:1000],
            warnings=[w.strip()[:300] for w in self.warnings if isinstance(w, str) and w.strip()][:6],
        )


# strict=False：容忍模型在字符串里直接写换行等控制字符（常见输出瑕疵），
# 否则整段合法结构会因为一个裸换行被判为不可解析。
_DECODER = json.JSONDecoder(strict=False)


def _decode_object_at(text: str, start: int) -> tuple[dict[str, Any], int] | None:
    """从 start 处尝试解出一个 JSON 对象，返回 (对象, 结束位置)。"""
    try:
        parsed, end = _DECODER.raw_decode(text, start)
    except ValueError:
        return None
    return (parsed, end) if isinstance(parsed, dict) else None


def _scan_objects(text: str, limit: int = 2) -> list[tuple[dict[str, Any], int]]:
    r"""按位置扫描出最多 limit 个顶层 JSON 对象。

    用 raw_decode 做括号配对，而不是贪婪正则 `\{.*\}`：正则会把对象之后
    的任何 `}`（模型偶尔多吐的收尾符号、解释性后缀）也吞进匹配里，让一段
    本来合法的 JSON 整体解析失败。
    """
    found: list[tuple[dict[str, Any], int]] = []
    index = text.find("{")
    while index != -1 and len(found) < limit:
        decoded = _decode_object_at(text, index)
        if decoded is not None:
            found.append(decoded)
            index = text.find("{", decoded[1])
        else:
            index = text.find("{", index + 1)
    return found


def extract_json(text: str) -> dict[str, Any] | None:
    """从模型回复里提取唯一的 JSON 对象；容忍 markdown 代码块与前后缀文本。

    出现两个可解析的对象时属于歧义文本，宁可拒绝也不猜（由调用方重试或降级）。
    """
    if not isinstance(text, str):
        return None
    objects = _scan_objects(text)
    if len(objects) != 1:
        return None
    return objects[0][0]


def _content_to_text(message_content: Any) -> str:
    """AIMessage.content 可能是 str，也可能是多模态分片列表。"""
    if isinstance(message_content, str):
        return message_content
    if isinstance(message_content, list):
        return "".join(
            part.get("text", "") if isinstance(part, dict) else str(part) for part in message_content
        )
    return str(message_content)


def _usage_of(message: Any) -> dict[str, int]:
    usage = getattr(message, "usage_metadata", None)
    if not isinstance(usage, dict):
        return {}
    return {
        "input_tokens": int(usage.get("input_tokens") or 0),
        "output_tokens": int(usage.get("output_tokens") or 0),
    }


def _validate(parsed: dict[str, Any]) -> CopySuggestionOut:
    # 非字符串的标量一律丢弃（LLM 输出不可信）。
    cleaned = {
        key: (value if isinstance(value, str) or key == "warnings" else "")
        for key, value in parsed.items()
    }
    if isinstance(cleaned.get("warnings"), str):
        cleaned["warnings"] = [cleaned["warnings"]]
    if not isinstance(cleaned.get("warnings"), list):
        cleaned["warnings"] = []
    return CopySuggestionOut.model_validate(cleaned)


def _build_messages(payload: Any) -> list[Any]:
    context = {
        "title": payload.title,
        "summary": payload.summary,
        "description": payload.description,
        "category": payload.category,
        "city": payload.city,
        "venue": payload.venue_name,
        "audience": payload.audience,
        "tone": payload.tone,
        "starts_at": payload.starts_at_iso,
        "price_cents": payload.price_cents,
    }
    human = (
        "请根据以下活动资料生成建议文案（输出 JSON）：\n"
        + json.dumps({k: v for k, v in context.items() if v}, ensure_ascii=False)
    )
    return [SystemMessage(content=IMPROVE_SYSTEM_PROMPT), HumanMessage(content=human)]


def _accumulate(usage: dict[str, int], more: dict[str, int]) -> None:
    for key, value in more.items():
        usage[key] = usage.get(key, 0) + value


def _try_structured_output(model: BaseChatModel, messages: list[Any], usage: dict[str, int]) -> Suggestion | None:
    """结构化输出主路径；不可用或没解析出来时返回 None，交给手写解析兜底。

    两个参数都是刻意选的：
    - method="function_calling"：LLM_BASE_URL 可以指向任意 OpenAI 兼容网关，
      tool calling 近乎通用，json_schema 不是。
    - include_raw=True：不然拿不到 raw 消息上的 usage_metadata，token 会归零，
      而 Spring 侧现在要按它扣预算。

    注意 include_raw=True 时解析失败【不抛异常】，而是把错误放进 parsing_error，
    所以两种失败都要判：软失败（parsed 为空）与硬失败（网关不支持，invoke 抛错）。
    """
    try:
        runnable = model.with_structured_output(
            CopySuggestionOut, method="function_calling", include_raw=True
        )
        result = runnable.invoke(messages)
    except Exception:
        # 网关不支持 tool calling 等硬失败。
        return None
    if not isinstance(result, dict):
        return None
    # 失败也要把这次的用量算进去：它一样是花掉的钱。
    _accumulate(usage, _usage_of(result.get("raw")))
    parsed = result.get("parsed")
    if parsed is None or result.get("parsing_error") is not None:
        return None
    try:
        return parsed.to_suggestion()
    except Exception:
        return None


def _improve_with_text_parsing(
    model: BaseChatModel, messages: list[Any], usage: dict[str, int]
) -> tuple[Suggestion, list[str], dict[str, int]]:
    """兜底：让模型直接吐 JSON 文本，解析失败时带着错误提示重试一次。"""
    last_error: str | None = None
    for _attempt in range(2):
        response = model.invoke(messages)
        _accumulate(usage, _usage_of(response))
        parsed = extract_json(_content_to_text(response.content))
        if parsed is not None:
            try:
                suggestion = _validate(parsed).to_suggestion()
                return suggestion, suggestion.warnings, usage
            except Exception as exc:  # pydantic 校验失败
                last_error = f"validation failed: {type(exc).__name__}"
        else:
            last_error = "no JSON object found"
        messages = messages + [
            response,
            HumanMessage(content=(
                "你的上一次回复不是要求的 JSON 结构"
                + (f"（{last_error}）" if last_error else "")
                + "。请重新回答：只输出一个 JSON 对象，字段为 title、summary、description、"
                "attendance_notes、warnings。"
            )),
        ]
    raise LlmOutputError(last_error or "invalid model output")


def improve_event_copy(model: BaseChatModel, payload: Any) -> tuple[Suggestion, list[str], dict[str, int]]:
    """根据主办方表单数据生成文案建议。

    返回 (建议, 警告, token用量)。优先走结构化输出；网关不支持或没解析出来时
    回落到手写 JSON 解析 + 一次重试，两条路径的 token 用量累加在同一个 usage 里。
    两条路径都失败时抛 LlmOutputError，绝不把未校验的文本交给调用方。
    """
    messages = _build_messages(payload)
    usage: dict[str, int] = {"input_tokens": 0, "output_tokens": 0}

    structured = _try_structured_output(model, messages, usage)
    if structured is not None:
        return structured, structured.warnings, usage

    return _improve_with_text_parsing(model, messages, usage)
