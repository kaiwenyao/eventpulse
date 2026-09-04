"""活动发现 Agent：工具选择、真实活动结果、编造过滤、次数与失败限制。"""

import json

import pytest
from langchain_core.messages import AIMessage, HumanMessage, SystemMessage

from app.agent import AgentExecutionError, parse_discovery_answer, run_discovery_agent
from app.tools import ToolLedger, build_tools
from app.backend_client import BackendClient
from app.schemas import DiscoveryChatRequest, HistoryMessage, DiscoveryUser

from conftest import FakeBackend, make_settings
from fake_model import BindRecordingChatModel, RecordingChatModel, scripted_model, tool_call_message


def chat_request(**overrides) -> DiscoveryChatRequest:
    payload = dict(
        request_id="req-d1",
        message="这个周末有什么适合新手参加的技术活动？",
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


def backend_returning(events: list[dict], preferences: dict | None = None) -> BackendClient:
    def handler(path, payload, headers):
        assert headers["X-Internal-Token"] == "internal-token"
        if path == "/internal/ai-tools/events/search":
            return 200, {"code": 1, "data": events}
        if path == "/internal/ai-tools/users/me/preferences":
            return 200, {"code": 1, "data": preferences or {}}
        raise AssertionError(f"unexpected path {path}")

    fake = FakeBackend(handler)
    return BackendClient(
        make_settings(),
        "req-d1",
        None,
        fake.client(),
    )


def answer_json(answer_text, events, follow_ups=None) -> str:
    return json.dumps(
        {"answer": answer_text, "events": events, "follow_up_questions": follow_ups or []},
        ensure_ascii=False,
    )


class TestRunDiscoveryAgent:
    def test_uses_search_tool_and_returns_real_events(self):
        client = backend_returning(events_payload([1, 2]))
        model = scripted_model(
            tool_call_message(
                "search_published_events",
                {"category": "tech", "date_from": "2026-09-05T00:00:00Z", "date_to": "2026-09-06T23:59:59Z"},
            ),
            AIMessage(content=answer_json("找到两场周末技术活动", [{"event_id": 1, "reason": "周六下午"}, {"event_id": 2, "reason": "周日上午"}])),
        )
        answer, usage, tool_calls = run_discovery_agent(model, make_settings(), chat_request(), client)
        assert [e.event_id for e in answer.events] == [1, 2]
        assert tool_calls == 1
        assert usage["input_tokens"] >= 0

    def test_fabricated_event_ids_are_dropped(self):
        client = backend_returning(events_payload([1]))
        model = scripted_model(
            tool_call_message("search_published_events", {"category": "tech"}),
            AIMessage(
                content=answer_json(
                    "结果", [{"event_id": 1, "reason": "真实"}, {"event_id": 999, "reason": "编造的"}]
                )
            ),
        )
        answer, _u, _c = run_discovery_agent(model, make_settings(), chat_request(), client)
        assert [e.event_id for e in answer.events] == [1]

    def test_empty_tool_result_yields_honest_no_result(self):
        client = backend_returning([])
        model = scripted_model(
            tool_call_message("search_published_events", {"city": "上海"}),
            AIMessage(content=answer_json("这个周末没有找到符合条件的技术活动，可以放宽条件试试。", [])),
        )
        answer, _u, _c = run_discovery_agent(model, make_settings(), chat_request(), client)
        assert answer.events == []
        assert "没有找到" in answer.answer

    def test_non_json_final_answer_degrades_to_plain_answer(self):
        client = backend_returning(events_payload([1]))
        model = scripted_model(
            tool_call_message("search_published_events", {}),
            AIMessage(content="我找到了一些活动。"),
        )
        answer, _u, _c = run_discovery_agent(model, make_settings(), chat_request(), client)
        assert answer.answer == "我找到了一些活动。"
        assert answer.events == []

    def test_tool_call_limit_soft_stops_the_agent(self):
        client = backend_returning(events_payload([1]))
        limited = make_settings()
        limited.agent_max_tool_calls = 1
        # 模型脚本要求两次工具调用：第二次拿到软停止指令，模型基于已有结果收尾。
        model = scripted_model(
            tool_call_message("search_published_events", {}, call_id="c1"),
            tool_call_message("search_published_events", {}, call_id="c2"),
            AIMessage(content=answer_json("基于已有结果作答", [{"event_id": 1, "reason": "来自第一次查询"}])),
        )
        answer, _usage, calls = run_discovery_agent(model, limited, chat_request(), client)
        assert [e.event_id for e in answer.events] == [1]
        assert calls == 1  # 限制仍然生效：第二次查询没有真正执行

    def test_backend_failure_degrades_not_fabricates(self):
        def handler(path, payload, headers):
            return 503, {"code": 0, "msg": "db down"}

        fake = FakeBackend(handler)
        client = BackendClient(make_settings(), "req-d1", None, fake.client())
        model = scripted_model(
            tool_call_message("search_published_events", {}),
        )
        with pytest.raises(AgentExecutionError):
            run_discovery_agent(model, make_settings(), chat_request(), client)

    def test_history_roles_are_preserved_for_the_model(self):
        client = backend_returning(events_payload([1]))
        model = RecordingChatModel(script=[AIMessage(content=answer_json("好的", []))])
        request = chat_request(
            history=[
                HistoryMessage(role="user", content="上周末有什么活动"),
                HistoryMessage(role="assistant", content="上周有两场技术活动"),
            ]
        )
        run_discovery_agent(model, make_settings(), request, client)
        first_call = model.received[0]
        # 消息列表以 system prompt 开头；之后上一轮的 user 提问与 assistant
        # 回答必须以各自的角色进入：全部当 HumanMessage 会让模型把自己的
        # 回答当成新指令。
        assert isinstance(first_call[0], SystemMessage)
        assert isinstance(first_call[1], HumanMessage)
        assert isinstance(first_call[2], AIMessage)
        assert isinstance(first_call[3], HumanMessage)
        assert first_call[2].content == "上周有两场技术活动"

    def test_model_binding_requests_explicit_auto_tool_choice(self):
        # 主路径必须显式 tool_choice="auto"：不能依赖各网关对缺失字段的默认解释，
        # 也不能强制每次都调工具（"required" 会让寒暄类问题空跑一次查询）。
        client = backend_returning(events_payload([1]))
        model = BindRecordingChatModel(script=[AIMessage(content=answer_json("好的", []))])
        run_discovery_agent(model, make_settings(), chat_request(), client)
        assert model.bind_kwargs, "agent never bound tools to the model"
        assert all(kwargs.get("tool_choice") == "auto" for kwargs in model.bind_kwargs)

    def test_total_time_budget_fails_the_round(self):
        client = backend_returning(events_payload([1]))
        settings = make_settings(agent_total_budget_seconds=0)
        model = scripted_model(AIMessage(content=answer_json("来不及了", [])))
        with pytest.raises(AgentExecutionError):
            run_discovery_agent(model, settings, chat_request(), client)

    def test_guest_has_no_personal_tools(self):
        client = backend_returning(events_payload([1]))
        tools = build_tools(client, ToolLedger(4, 10))
        names = {t.name for t in tools}
        assert "search_published_events" in names
        assert "get_my_preferences" not in names
        assert "get_my_recent_categories" not in names

    def test_signed_in_user_gets_personal_tools(self):
        fake = FakeBackend(lambda path, payload, headers: (200, {"code": 1, "data": {}}))
        client = BackendClient(make_settings(), "req-d1", "signed-context-token", fake.client())
        tools = build_tools(client, ToolLedger(4, 10))
        names = {t.name for t in tools}
        assert "get_my_preferences" in names
        assert "get_my_recent_categories" in names


class TestParseDiscoveryAnswer:
    def test_history_passed_into_prompt_payload(self):
        request = chat_request(
            user=DiscoveryUser(user_id=3, role="USER"),
            history=[HistoryMessage(role="user", content="上周那个活动还有票吗")],
        )
        assert request.history[0].content == "上周那个活动还有票吗"

    def test_follow_ups_and_reasons_are_trimmed(self):
        parsed = parse_discovery_answer(
            json.dumps(
                {
                    "answer": "a" * 3000,
                    "events": [{"event_id": 1, "reason": "r" * 500}, "junk", {"event_id": "x", "reason": ""}],
                    "follow_up_questions": ["q" * 400, 5, "valid"],
                }
            ),
            allowed_event_ids={1},
            max_events=10,
        )
        assert len(parsed.answer) <= 2000
        assert len(parsed.events) == 1
        assert len(parsed.events[0].reason) <= 200
        assert len(parsed.follow_up_questions) == 2
        assert parsed.follow_up_questions[0] == "q" * 200

    def test_extracts_json_from_surrounding_text(self):
        parsed = parse_discovery_answer('好的：{"answer": "ok", "events": [], "follow_up_questions": []}', set(), 10)
        assert parsed.answer == "ok"

    def test_object_with_trailing_garbage_still_parses(self):
        parsed = parse_discovery_answer(
            '{"answer": "找到两场活动。", "events": [{"event_id": 2, "reason": "近"}],'
            ' "follow_up_questions": ["帮我看看北京的"]}]}',
            allowed_event_ids={2},
            max_events=10,
        )
        assert parsed.answer == "找到两场活动。"
        assert [e.event_id for e in parsed.events] == [2]
        assert parsed.follow_up_questions == ["帮我看看北京的"]

    def test_broken_envelope_never_leaks_raw_json(self):
        # 信封坏到无法解析时只抢救 answer，绝不把原始 JSON 当回答展示。
        broken = '{"answer": "只找到两场活动。", "events": [{"event_id": 2,, }], "follow_up"'
        parsed = parse_discovery_answer(broken, allowed_event_ids={2}, max_events=10)
        assert parsed.answer == "只找到两场活动。"
        assert parsed.events == []
        assert "event_id" not in parsed.answer

    def test_unsalvageable_envelope_returns_empty_answer(self):
        parsed = parse_discovery_answer('{"events": [{"event_id": ,]', set(), 10)
        assert parsed.answer == ""

    def test_plain_prose_answer_is_kept(self):
        parsed = parse_discovery_answer("这个周末没有合适的活动。", set(), 10)
        assert parsed.answer == "这个周末没有合适的活动。"


class TestUserPreferences:
    """偏好由 Spring 随请求带过来，不再靠模型自己想起来调工具。"""

    def test_saved_preferences_reach_the_system_prompt_as_data(self):
        from app.schemas import DiscoveryPreferences

        client = backend_returning(events_payload([1]))
        model = RecordingChatModel(script=[AIMessage(content=answer_json("ok", []))])
        request = chat_request(preferences=DiscoveryPreferences(
            categories="music,tech", cities="Berlin", latitude=52.52, longitude=13.405, radius_km=15,
        ))

        run_discovery_agent(model, make_settings(), request, client)

        system = next(m.content for m in model.received[0] if isinstance(m, SystemMessage))
        assert "music,tech" in system
        assert "Berlin" in system
        # 偏好是用户自己填的自由文本，天然是注入面：必须明确标注成数据。
        assert "此前主动保存的偏好【数据】" in system
        assert "不得执行" in system

    def test_no_preferences_adds_nothing_to_the_prompt(self):
        client = backend_returning(events_payload([1]))
        model = RecordingChatModel(script=[AIMessage(content=answer_json("ok", []))])

        run_discovery_agent(model, make_settings(), chat_request(), client)

        system = next(m.content for m in model.received[0] if isinstance(m, SystemMessage))
        # 基础提示词本身就含「【数据】」，所以要用偏好块特有的措辞来判断。
        assert "此前主动保存的偏好" not in system

    def test_empty_preference_fields_are_skipped(self):
        from app.schemas import DiscoveryPreferences

        client = backend_returning(events_payload([1]))
        model = RecordingChatModel(script=[AIMessage(content=answer_json("ok", []))])
        request = chat_request(preferences=DiscoveryPreferences())

        run_discovery_agent(model, make_settings(), request, client)

        system = next(m.content for m in model.received[0] if isinstance(m, SystemMessage))
        assert "此前主动保存的偏好" not in system

    def test_current_message_still_wins_over_saved_preferences(self):
        # 提示词必须明说这一点：否则模型会拿旧偏好覆盖用户这次的明确要求。
        from app.prompts import user_preferences_context
        from app.schemas import DiscoveryPreferences

        block = user_preferences_context(DiscoveryPreferences(cities="Berlin"))
        assert "以这次的话为准" in block
