"""BackendClient：头、参数、响应解包与失败转 ToolError。"""

import json

import httpx
import pytest

from app.backend_client import BackendClient, ToolError

from conftest import FakeBackend, make_settings


def client_with(handler, context_token=None) -> tuple[BackendClient, FakeBackend]:
    fake = FakeBackend(handler)
    return BackendClient(make_settings(), "req-x", context_token, fake.client()), fake


def test_search_sends_headers_and_payload():
    seen = {}

    def handler(path, payload, headers):
        seen.update(path=path, payload=payload, headers=headers)
        return 200, {"code": 1, "data": [{"id": 1, "title": "活动"}]}

    client, fake = client_with(handler, context_token="ctx-token")
    result = client.search_events(category="tech", max_price_cents=0, limit=50, has_remaining=True)

    assert result[0]["id"] == 1
    assert seen["path"] == "/internal/ai-tools/events/search"
    assert seen["headers"]["X-Internal-Token"] == "internal-token"
    assert seen["headers"]["X-User-Context"] == "ctx-token"
    assert seen["headers"]["X-Request-Id"] == "req-x"
    # limit 被钳制到服务端允许的上限内，价格单位是分。
    assert seen["payload"]["limit"] <= 20
    assert seen["payload"]["maxPriceCents"] == 0
    assert seen["payload"]["hasRemaining"] is True


def test_non_200_and_bad_envelope_become_tool_error():
    client, _ = client_with(lambda path, payload, headers: (503, {"code": 0, "msg": "down"}))
    with pytest.raises(ToolError):
        client.search_events()

    client2, _ = client_with(lambda path, payload, headers: (200, {"unexpected": 1}))
    with pytest.raises(ToolError):
        client2.popular_events()

    client3, _ = client_with(lambda path, payload, headers: (200, {"code": 0, "msg": "rejected"}))
    with pytest.raises(ToolError):
        client3.get_event(1)


def test_timeout_becomes_tool_error():
    def timeout_transport(request: httpx.Request) -> httpx.Response:
        raise httpx.ConnectTimeout("timeout", request=request)

    http = httpx.Client(base_url="http://backend.test", transport=httpx.MockTransport(timeout_transport))
    client = BackendClient(make_settings(), "req-x", None, http)
    with pytest.raises(ToolError):
        client.search_events()


def test_details_and_nearby_and_preferences():
    def handler(path, payload, headers):
        if path == "/internal/ai-tools/events/3":
            return 200, {"code": 1, "data": {"id": 3, "title": "活动3"}}
        if path == "/internal/ai-tools/events/nearby":
            return 200, {"code": 1, "data": [{"id": 3}]}
        if path == "/internal/ai-tools/users/me/preferences":
            return 200, {"code": 1, "data": {"categories": "music"}}
        if path == "/internal/ai-tools/users/me/recent-categories":
            return 200, {"code": 1, "data": [{"category": "music", "count": 2}]}
        return 404, {"code": 0, "msg": "nf"}

    client, _ = client_with(handler, context_token="ctx")
    assert client.get_event(3)["id"] == 3
    assert client.nearby_events(lat=31.2, lng=121.4, radius_km=10)[0]["id"] == 3
    assert client.my_preferences()["categories"] == "music"
    assert client.my_recent_categories()[0]["count"] == 2


def test_event_list_shape_is_enforced():
    client, _ = client_with(lambda path, payload, headers: (200, {"code": 1, "data": {"not": "a list"}}))
    with pytest.raises(ToolError):
        client.search_events()
