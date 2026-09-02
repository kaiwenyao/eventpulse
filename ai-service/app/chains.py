"""文案生成链：普通 LLM 调用 + 结构化输出校验（不用 Agent）。

LLM 的输出按不可信数据处理：解析失败时做一次有限重试，仍失败就抛
LlmOutputError，绝不把未校验的文本交给调用方。
"""

import json
import re
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


_JSON_OBJECT = re.compile(r"\{.*\}", re.DOTALL)


def extract_json(text: str) -> dict[str, Any] | None:
    """从模型回复里提取第一个 JSON 对象；容忍 markdown 代码块与前后缀文本。"""
    if not isinstance(text, str):
        return None
    match = _JSON_OBJECT.search(text)
    if not match:
        return None
    try:
        parsed = json.loads(match.group(0))
    except ValueError:
        return None
    return parsed if isinstance(parsed, dict) else None


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


def improve_event_copy(model: BaseChatModel, payload: Any) -> tuple[Suggestion, list[str], dict[str, int]]:
    """根据主办方表单数据生成文案建议。

    返回 (建议, 警告, token用量)。警告包括模型给出的 warnings 与本地的
    资料缺失提示；模型两次都输出不合法结构时抛 LlmOutputError。
    """
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
    messages = [SystemMessage(content=IMPROVE_SYSTEM_PROMPT), HumanMessage(content=human)]

    usage: dict[str, int] = {"input_tokens": 0, "output_tokens": 0}
    last_error: str | None = None
    for attempt in range(2):
        response = model.invoke(messages)
        for key, value in _usage_of(response).items():
            usage[key] = usage.get(key, 0) + value
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
