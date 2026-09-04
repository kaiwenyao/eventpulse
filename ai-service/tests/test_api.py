"""HTTP 层：服务认证、健康检查、未配置 Key 的降级、响应结构。"""

import json

import pytest
from fastapi.testclient import TestClient
from langchain_core.messages import AIMessage

import app.main as main_module
from app.main import app

from fake_model import scripted_model, tool_call_message


def auth_header(token="service-token"):
    return {"Authorization": f"Bearer {token}"}


@pytest.fixture
def client():
    return TestClient(app, raise_server_exceptions=False)


def test_healthz_reports_llm_state(client, monkeypatch):
    monkeypatch.setenv("LLM_API_KEY", "")
    main_module.get_settings.cache_clear()
    response = client.get("/healthz")
    assert response.status_code == 200
    assert response.json() == {"status": "ok", "llm_configured": False}

    monkeypatch.setenv("LLM_API_KEY", "k")
    main_module.get_settings.cache_clear()
    assert client.get("/healthz").json()["llm_configured"] is True
    main_module.get_settings.cache_clear()


def test_requires_service_token(client):
    assert client.post("/internal/v1/improve-event", json={}).status_code == 401
    assert (
        client.post("/internal/v1/improve-event", json={}, headers=auth_header("wrong")).status_code == 401
    )
    assert client.post("/internal/v1/discovery/chat", json={}).status_code == 401


def test_improve_event_without_llm_key_returns_503(client, monkeypatch):
    monkeypatch.setenv("LLM_API_KEY", "")
    main_module.get_settings.cache_clear()
    response = client.post(
        "/internal/v1/improve-event",
        json={"requestId": "r1", "title": "t"},
        headers=auth_header(),
    )
    assert response.status_code == 503
    assert "not configured" in response.json()["detail"]
    main_module.get_settings.cache_clear()


def test_discovery_without_llm_key_returns_503(client, monkeypatch):
    monkeypatch.setenv("LLM_API_KEY", "")
    main_module.get_settings.cache_clear()
    response = client.post(
        "/internal/v1/discovery/chat",
        json={"requestId": "r1", "message": "hi", "nowIso": "2026-09-02T00:00:00Z"},
        headers=auth_header(),
    )
    assert response.status_code == 503
    main_module.get_settings.cache_clear()


def override_model(monkeypatch, model):
    app.dependency_overrides[main_module.get_copy_model] = lambda: model
    app.dependency_overrides[main_module.get_discovery_model] = lambda: model


def test_improve_event_happy_path(client, monkeypatch):
    override_model(
        monkeypatch,
        scripted_model(
            AIMessage(
                content=json.dumps(
                    {"title": "新标题", "summary": "新摘要", "description": "新描述",
                     "attendance_notes": "须知", "warnings": []},
                    ensure_ascii=False,
                )
            )
        ),
    )
    response = client.post(
        "/internal/v1/improve-event",
        json={"requestId": "r1", "title": "旧标题", "description": "旧描述"},
        headers=auth_header(),
    )
    assert response.status_code == 200
    body = response.json()
    assert body["suggestion"]["title"] == "新标题"
    # wire 格式是 camelCase：Spring Boot 直接反序列化。
    assert body["suggestion"]["attendanceNotes"] == "须知"
    assert body["provider"] == "openai"
    assert "inputTokens" in body["usage"]
    app.dependency_overrides.clear()


def test_discovery_happy_path_with_tools(client, monkeypatch):
    def fake_backend(path, payload, headers):
        assert path == "/internal/ai-tools/events/search"
        return 200, {"code": 1, "data": [{"id": 7, "title": "音乐节"}]}

    from conftest import FakeBackend
    from app.backend_client import BackendClient

    fake = FakeBackend(fake_backend)

    def override_client(settings, request_id, context_token, http_client=None):
        return BackendClient(settings, request_id, context_token, fake.client())

    monkeypatch.setattr(main_module, "BackendClient", override_client)
    override_model(
        monkeypatch,
        scripted_model(
            tool_call_message("search_published_events", {"category": "music"}),
            AIMessage(
                content=json.dumps(
                    {"answer": "找到了音乐节", "events": [{"event_id": 7, "reason": "周末"}],
                     "follow_up_questions": ["要免费的吗?"]},
                    ensure_ascii=False,
                )
            ),
        ),
    )
    response = client.post(
        "/internal/v1/discovery/chat",
        json={
            "requestId": "r2",
            "message": "这个周末有什么音乐活动",
            "nowIso": "2026-09-02T10:00:00Z",
            "timeZone": "Asia/Shanghai",
            "userContextToken": "signed-token",
        },
        headers=auth_header(),
    )
    assert response.status_code == 200
    body = response.json()
    assert body["answer"] == "找到了音乐节"
    assert body["events"][0]["eventId"] == 7
    assert body["followUpQuestions"] == ["要免费的吗?"]
    app.dependency_overrides.clear()


def test_message_length_validated(client, monkeypatch):
    monkeypatch.setenv("LLM_API_KEY", "k")
    main_module.get_settings.cache_clear()
    override_model(monkeypatch, scripted_model(AIMessage(content="{}")))
    response = client.post(
        "/internal/v1/discovery/chat",
        json={"requestId": "r3", "message": "x" * 3000, "nowIso": "2026-09-02T10:00:00Z"},
        headers=auth_header(),
    )
    assert response.status_code == 422
    app.dependency_overrides.clear()
    main_module.get_settings.cache_clear()


def test_null_fields_from_spring_do_not_422(client, monkeypatch):
    """Spring 的 Jackson 会把未填字段序列化成 null：wire 模型必须可空。"""
    override_model(
        monkeypatch,
        scripted_model(
            AIMessage(content=json.dumps({"title": "T", "summary": "", "description": "",
                                          "attendance_notes": "", "warnings": []}, ensure_ascii=False))
        ),
    )
    response = client.post(
        "/internal/v1/improve-event",
        # 所有可选字段显式为 null（Java record 未填时的 JSON 形态）。
        json={"requestId": "r1", "title": None, "summary": None, "description": None,
              "category": None, "city": None, "venueName": None, "audience": None,
              "tone": None, "startsAtIso": None, "priceCents": None},
        headers=auth_header(),
    )
    assert response.status_code == 200, response.text
    app.dependency_overrides.clear()


def test_guest_null_context_token_is_accepted(client, monkeypatch):
    from conftest import FakeBackend
    from app.backend_client import BackendClient

    seen = {}

    def fake_backend(path, payload, headers):
        seen["headers"] = dict(headers)
        return 200, {"code": 1, "data": []}

    fake = FakeBackend(fake_backend)

    def override_client(settings, request_id, context_token, http_client=None):
        seen["context_token"] = context_token
        return BackendClient(settings, request_id, context_token, fake.client())

    monkeypatch.setattr(main_module, "BackendClient", override_client)
    override_model(
        monkeypatch,
        scripted_model(
            AIMessage(content=json.dumps({"answer": "没有匹配", "events": []}, ensure_ascii=False))
        ),
    )
    response = client.post(
        "/internal/v1/discovery/chat",
        json={"requestId": "r9", "message": "hi", "history": [], "nowIso": "2026-09-02T10:00:00Z",
              "timeZone": None, "user": None, "userContextToken": None},
        headers=auth_header(),
    )
    assert response.status_code == 200, response.text
    assert seen["context_token"] is None
    app.dependency_overrides.clear()
