"""EventPulse Python AI 服务入口。

只暴露给 Spring Boot（内网），浏览器永远不直接访问本服务：
- POST /internal/v1/improve-event   主办方文案助手（普通 LLM 调用 + 结构化输出）
- POST /internal/v1/discovery/chat  活动发现助手（LangChain Agent + 受控工具）
- GET  /healthz                     存活 / 就绪检查（不检查外部 LLM 可用性）

进程内不保存任何会话状态：多轮上下文由 Spring Boot 存在 PostgreSQL，
每次请求随载荷带最有限的历史。
"""

import logging
import secrets
from contextlib import asynccontextmanager
from typing import Annotated, Any, AsyncGenerator

from fastapi import Depends, FastAPI, HTTPException, security, status
from fastapi.responses import StreamingResponse
from langchain_core.language_models.chat_models import BaseChatModel

from .agent import AgentExecutionError, run_discovery_agent, stream_discovery_agent
from .backend_client import BackendClient
from .chains import LlmOutputError, improve_event_copy
from .config import Settings, get_settings, llm_configured
from .llm import LlmNotConfigured, build_chat_model
from .schemas import (
    DiscoveryChatRequest,
    DiscoveryChatResponse,
    ImproveEventRequest,
    ImproveEventResponse,
    Usage,
)

logger = logging.getLogger("eventpulse.ai")
logging.basicConfig(level=logging.INFO, format="%(asctime)s %(name)s %(levelname)s %(message)s")

_bearer = security.HTTPBearer(auto_error=False, description="Spring Boot service token")


@asynccontextmanager
async def lifespan(app: FastAPI):
    logger.info("ai service starting; llm_configured=%s", llm_configured(get_settings()))
    yield


app = FastAPI(
    title="EventPulse AI Service",
    version="0.1.0",
    docs_url=None,
    redoc_url=None,
    openapi_url=None,
    lifespan=lifespan,
)


def require_service_auth(
    credentials: Annotated[security.HTTPAuthorizationCredentials | None, Depends(_bearer)],
) -> None:
    """服务间凭证：Spring Boot → 本服务的固定 Bearer token（常量时间比较）。"""
    settings = get_settings()
    provided = credentials.credentials if credentials else ""
    # 服务凭证配成空串时绝不放行（否则 "" 与 "" 的常量时间比较恒真，直接 fail-open）；
    # encode 让非 ASCII 的伪造 header 也得到 401，而不是 TypeError 500。
    if not settings.service_token.strip() or not secrets.compare_digest(
        provided.encode("utf-8"), settings.service_token.encode("utf-8")
    ):
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="invalid service token")


def get_chat_model(settings: Annotated[Settings, Depends(get_settings)]) -> BaseChatModel:
    try:
        return build_chat_model(settings)
    except LlmNotConfigured as exc:
        # 不冒充 AI 结果：未配置 Key 时明确不可用，不影响普通业务。
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail="AI assistant is not configured (missing LLM API key)",
        ) from exc


SettingsDep = Annotated[Settings, Depends(get_settings)]
ModelDep = Annotated[BaseChatModel, Depends(get_chat_model)]
AuthDep = Annotated[None, Depends(require_service_auth)]


@app.get("/healthz")
def healthz() -> dict[str, Any]:
    """存活 + 就绪。不探测外部 LLM：LLM 故障由调用路径按降级处理。"""
    return {"status": "ok", "llm_configured": llm_configured(get_settings())}


@app.post("/internal/v1/improve-event", response_model=ImproveEventResponse)
def improve_event(request: ImproveEventRequest, _: AuthDep, model: ModelDep, settings: SettingsDep) -> Any:
    try:
        suggestion, warnings, usage = improve_event_copy(model, request)
    except LlmOutputError:
        logger.warning("improve-event produced invalid output (request_id=%s)", request.request_id)
        raise HTTPException(
            status_code=status.HTTP_502_BAD_GATEWAY,
            detail="AI returned an unreadable result, please retry",
        ) from None
    return ImproveEventResponse(
        request_id=request.request_id,
        suggestion=suggestion,
        warnings=warnings,
        provider=settings.llm_provider,
        model=settings.llm_model,
        usage=Usage(**usage),
    )


@app.post("/internal/v1/discovery/chat", response_model=DiscoveryChatResponse)
def discovery_chat(request: DiscoveryChatRequest, _: AuthDep, model: ModelDep, settings: SettingsDep) -> Any:
    client = BackendClient(settings, request.request_id, request.user_context_token or None)
    try:
        answer, usage, _tool_calls = run_discovery_agent(model, settings, request, client)
    except AgentExecutionError as exc:
        logger.warning("discovery agent failed (request_id=%s, error=%s)", request.request_id, exc)
        # 明确降级：告诉用户暂时查不了，而不是编造活动。
        raise HTTPException(
            status_code=status.HTTP_502_BAD_GATEWAY,
            detail="AI could not query events right now, please retry or use the normal search",
        ) from None
    finally:
        client.close()

    return DiscoveryChatResponse(
        request_id=request.request_id,
        answer=answer.answer,
        events=answer.events,
        follow_up_questions=answer.follow_up_questions,
        provider=settings.llm_provider,
        model=settings.llm_model,
        usage=Usage(**usage),
    )


def _sse_frame(name: str, payload: dict[str, Any]) -> str:
    """包一个 SSE 帧。StreamingResponse 不引入 sse-starlette：帧格式很简单。"""
    import json as _json

    return f"event: {name}\ndata: {_json.dumps(payload, ensure_ascii=False)}\n\n"


async def _discovery_sse_events(
    request: DiscoveryChatRequest,
    model: BaseChatModel,
    settings: Settings,
) -> AsyncGenerator[str, None]:
    """把 Agent 事件流包装成 SSE 帧序列。

    逐字转发的字符全部来自可信的最终解析（权威收尾 result 会覆盖任何增量）；
    中途失败发一条 error 帧（前端明确降级），而不是把半截内容冒充完整。
    """
    client = BackendClient(settings, request.request_id, request.user_context_token or None)
    try:
        try:
            events = stream_discovery_agent(model, settings, request, client)
            for kind, *payload in events:
                if kind == "delta":
                    yield _sse_frame("delta", {"text": payload[0]})
                else:
                    # 权威收尾：answer / events / follow_up_questions 一次给全，
                    # 附带 provider/model/usage 供日志与计量。
                    answer, usage, tool_calls = payload
                    yield _sse_frame(
                        "result",
                        {
                            "answer": answer.answer,
                            "events": [
                                {"eventId": e.event_id, "reason": e.reason}
                                for e in answer.events
                            ],
                            "followUpQuestions": answer.follow_up_questions,
                            "provider": settings.llm_provider,
                            "model": settings.llm_model,
                            "usage": {"inputTokens": usage.get("input_tokens"), "outputTokens": usage.get("output_tokens")},
                            "toolCalls": tool_calls,
                        },
                    )
        except AgentExecutionError as exc:
            logger.warning("discovery stream failed (request_id=%s, error=%s)", request.request_id, exc)
            yield _sse_frame("error", {"message": "AI could not query events right now, please retry"})
    finally:
        client.close()


@app.post("/internal/v1/discovery/chat/stream")
def discovery_chat_stream(
    request: DiscoveryChatRequest, _: AuthDep, model: ModelDep, settings: SettingsDep
) -> StreamingResponse:
    """发现助手的 SSE 流式端点（Spring Boot 内网转发）。

    事件：delta（answer 文本逐字）→ result（权威收尾）或 error（明确降级）。
    服务间调用仍然先要过 require_service_auth；认证失败走普通 JSON 错误。
    """
    return StreamingResponse(
        _discovery_sse_events(request, model, settings),
        media_type="text/event-stream",
        headers={
            "Cache-Control": "no-cache",
            "X-Accel-Buffering": "no",
        },
    )


