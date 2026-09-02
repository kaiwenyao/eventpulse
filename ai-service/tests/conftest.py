"""pytest fixtures。"""

import os
import sys
from pathlib import Path

import httpx
import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

# 测试环境的默认配置：任何 get_settings() 调用之前生效（CI 不需要真实 Key）。
os.environ.setdefault("SERVICE_TOKEN", "service-token")
os.environ.setdefault("BACKEND_SERVICE_TOKEN", "internal-token")
os.environ.setdefault("LLM_API_KEY", "test-key")
os.environ.setdefault("BACKEND_INTERNAL_URL", "http://backend.test")

from app.config import Settings  # noqa: E402


def make_settings(**overrides) -> Settings:
    base = dict(
        llm_provider="openai",
        llm_model="fake-model",
        llm_api_key="test-key",
        backend_internal_url="http://backend.test",
        backend_service_token="internal-token",
        service_token="service-token",
        agent_max_tool_calls=4,
        agent_time_budget_seconds=10,
        tool_timeout_seconds=2,
    )
    base.update(overrides)
    return Settings(**base)


@pytest.fixture
def settings() -> Settings:
    return make_settings()


class FakeBackend:
    """替代 Spring Boot 的假后端：记录收到的请求并返回预置响应。"""

    def __init__(self, handler):
        self.handler = handler
        self.requests: list[httpx.Request] = []

    def transport_handler(self, request: httpx.Request) -> httpx.Response:
        self.requests.append(request)
        path = request.url.path
        body = request.read()
        payload = __import__("json").loads(body) if body else {}
        status_code, envelope = self.handler(path, payload, request.headers)
        return httpx.Response(status_code, json=envelope)

    def client(self) -> httpx.Client:
        return httpx.Client(
            base_url="http://backend.test", transport=httpx.MockTransport(self.transport_handler)
        )
