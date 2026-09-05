"""流式发现 Agent：增量放行 answer 文本、权威收尾、死线/空回复/失败语义。"""

import json

import pytest
from langchain_core.messages import AIMessage

from app.agent import AgentExecutionError, stream_discovery_agent
from app.schemas import DiscoveryChatRequest

from conftest import FakeBackend, make_settings
from fake_model import streaming_scripted_model, tool_call_message
from app.backend_client import BackendClient


def chat_request(**overrides) -> DiscoveryChatRequest:
    payload = dict(
        request_id="req-s1",
        message="这个周末有什么技术活动？",
        history=[],
        now_iso="2026-09-02T10:00:00Z",
        time_zone="Asia/Shanghai",
        user=None,
        user_context_token="",
    )
    payload.update(overrides)
    return DiscoveryChatRequest(**payload)


def events_payload(ids: list[int]) -> list[dict]:
    return [
        {
            "id": i,
            "title": f"活动{i}",
            "category": "tech",
            "city": "Shanghai",
            "startsAt": "2026-09-05T14:00:00Z",
            "priceCents": 0,
            "remaining": 10,
            "status": "PUBLISHED",
        }
        for i in ids
    ]


def backend_returning(events: list[dict]) -> BackendClient:
    def handler(path, payload, headers):
        if path == "/internal/ai-tools/events/search":
            return 200, {"code": 1, "data": events}
        raise AssertionError(f"unexpected path {path}")

    fake = FakeBackend(handler)
    return BackendClient(make_settings(), "req-s1", None, fake.client())


def collect(model, settings, request, client):
    """把生成器收进 (deltas, result) —— result 为 None 表示没有权威收尾。"""
    deltas: list[str] = []
    result = None
    for event in stream_discovery_agent(model, settings, request, client):
        if event[0] == "delta":
            deltas.append(event[1])
        else:
            result = event
    return "".join(deltas), result


class TestStreamDiscoveryAgent:
    def test_streams_answer_text_then_authoritative_result(self):
        client = backend_returning(events_payload([1, 2]))
        model = streaming_scripted_model(
            tool_call_message(
                "search_published_events",
                {"category": "tech", "date_from": "2026-09-05T00:00:00Z", "date_to": "2026-09-06T23:59:59Z"},
            ),
            AIMessage(
                content=json.dumps(
                    {"answer": "找到两场周末活动", "events": [{"event_id": 1, "reason": "周六"}, {"event_id": 2, "reason": "周日"}]},
                    ensure_ascii=False,
                ),
                usage_metadata={"input_tokens": 30, "output_tokens": 12, "total_tokens": 42},
            ),
        )
        streamed, result = collect(model, make_settings(), chat_request(), client)
        # 逐字流把 answer 文本放行出来（不把 JSON 信封放给用户）。
        assert streamed == "找到两场周末活动"
        assert "event_id" not in streamed
        kind, answer, usage, calls = result
        assert kind == "result"
        assert answer.answer == "找到两场周末活动"
        assert [e.event_id for e in answer.events] == [1, 2]
        assert usage == {"input_tokens": 30, "output_tokens": 12}
        assert calls == 1

    def test_fabricated_event_ids_still_dropped_in_streaming(self):
        client = backend_returning(events_payload([1]))
        model = streaming_scripted_model(
            tool_call_message("search_published_events", {}),
            AIMessage(
                content=json.dumps(
                    {"answer": "结果", "events": [{"event_id": 1, "reason": "真实"}, {"event_id": 999, "reason": "编造"}]},
                    ensure_ascii=False,
                )
            ),
        )
        streamed, result = collect(model, make_settings(), chat_request(), client)
        assert streamed == "结果"
        assert [e.event_id for e in result[1].events] == [1]

    def test_no_envelope_degrades_to_plain_result_but_nothing_streams(self):
        # 模型没给 JSON 信封、只写了一句话：没有 answer 字段可增量放行，所以
        # 什么也不流；权威收尾把它作为整段文本交给前端（同步路径同语义）。
        client = backend_returning(events_payload([1]))
        model = streaming_scripted_model(
            tool_call_message("search_published_events", {}),
            AIMessage(content="我找到了一些活动。"),
        )
        streamed, result = collect(model, make_settings(), chat_request(), client)
        assert streamed == ""
        assert result[1].answer == "我找到了一些活动。"
        assert result[1].events == []

    def test_envelope_that_abandons_streaming_still_yields_authoritative_result(self):
        # 模型在信封外写了一大段散文再给 JSON：提取器放弃流式（什么也不放行），
        # 但权威收尾仍然带着完整信封。绝不把散文或原始 JSON 冒充成流式内容。
        client = backend_returning(events_payload([1]))
        model = streaming_scripted_model(
            tool_call_message("search_published_events", {}),
            AIMessage(
                content=("模型先写了一堆不该给用户的散文。" * 60)
                + json.dumps({"answer": "真实答案", "events": [{"event_id": 1, "reason": "r"}]}, ensure_ascii=False),
            ),
        )
        limited = make_settings(stream_answer_budget_chars=100)
        streamed, result = collect(model, limited, chat_request(), client)
        assert streamed == ""
        assert result[1].answer == "真实答案"
        assert [e.event_id for e in result[1].events] == [1]

    def test_empty_streamed_reply_retries_once(self):
        # reasoning 模型第一次吐空（思考耗尽输出预算）：对用户不可见地重试一次，
        # 第二次给出答案。只有没有任何增量放行时才重试，避免回答被重复。
        client = backend_returning(events_payload([1]))
        model = streaming_scripted_model(
            AIMessage(content=""),
            AIMessage(
                content=json.dumps({"answer": "第二次成功", "events": []}, ensure_ascii=False),
            ),
        )
        streamed, result = collect(model, make_settings(), chat_request(), client)
        assert streamed == "第二次成功"
        assert result[1].answer == "第二次成功"

    def test_double_empty_reply_fails_the_round(self):
        client = backend_returning(events_payload([1]))
        model = streaming_scripted_model(AIMessage(content=""), AIMessage(content=""))
        # 生成器内部抛的 AgentExecutionError 必须带原消息穿出包装层——否则
        # 服务端日志里 error=%s 只剩 "AgentExecutionError" 一个类名，排障零线索。
        with pytest.raises(AgentExecutionError, match="agent returned empty responses"):
            collect(model, make_settings(), chat_request(), client)

    def test_model_timeout_fails_the_round_not_fabricates(self):
        from fake_model import ExplodingChatModel

        client = backend_returning(events_payload([1]))
        with pytest.raises(AgentExecutionError):
            collect(ExplodingChatModel(), make_settings(), chat_request(), client)

    def test_total_time_budget_fails_the_round(self):
        client = backend_returning(events_payload([1]))
        model = streaming_scripted_model(AIMessage(content='{"answer": "来不及了"}'))
        with pytest.raises(AgentExecutionError):
            collect(model, make_settings(agent_total_budget_seconds=0), chat_request(), client)

    def test_tool_result_failure_degrades_in_streaming(self):
        def handler(path, payload, headers):
            return 503, {"code": 0, "msg": "db down"}

        fake = FakeBackend(handler)
        client = BackendClient(make_settings(), "req-s1", None, fake.client())
        model = streaming_scripted_model(
            tool_call_message("search_published_events", {}),
            AIMessage(
                content=json.dumps(
                    {"answer": "暂时查不了，请稍后再试", "events": []},
                    ensure_ascii=False,
                )
            ),
        )
        streamed, result = collect(model, make_settings(), chat_request(), client)
        assert streamed == "暂时查不了，请稍后再试"
        assert result[1].answer == "暂时查不了，请稍后再试"
        assert result[1].events == []
