"""调用 Spring Boot /internal/ai-tools/** 的受控客户端。

每次请求一个实例（带同一个 requestId 与短期签名用户上下文），工具查询
失败一律转成 ToolError，让 Agent 停下来如实说明，而不是编造数据。
"""

import json
from typing import Any

import httpx


class ToolError(Exception):
    """工具调用失败（网络 / 状态码 / 响应结构不合法）。"""


class BackendClient:
    def __init__(
        self,
        settings: Any,
        request_id: str,
        context_token: str | None = None,
        http_client: httpx.Client | None = None,
    ) -> None:
        self._settings = settings
        self._request_id = request_id
        self._context_token = context_token
        self._http = http_client or httpx.Client(
            base_url=settings.backend_internal_url,
            timeout=settings.tool_timeout_seconds,
        )

    def close(self) -> None:
        if self._http is not None:
            self._http.close()

    @property
    def has_user_context(self) -> bool:
        """是否携带签名的用户上下文（决定个人化工具是否注入）。"""
        return bool(self._context_token)

    def _headers(self) -> dict[str, str]:
        headers = {
            "X-Internal-Token": self._settings.backend_service_token,
            "X-Request-Id": self._request_id,
        }
        if self._context_token:
            headers["X-User-Context"] = self._context_token
        return headers

    def _call(self, method: str, path: str, json_body: dict[str, Any] | None = None) -> Any:
        try:
            response = self._http.request(method, path, json=json_body, headers=self._headers())
        except httpx.HTTPError as exc:
            raise ToolError(f"backend request failed: {type(exc).__name__}") from exc
        if response.status_code != 200:
            raise ToolError(f"backend returned {response.status_code}")
        try:
            envelope = response.json()
            data = envelope["data"]
        except (ValueError, KeyError) as exc:
            raise ToolError("backend returned malformed body") from exc
        if envelope.get("code") != 1:
            raise ToolError("backend rejected the tool query")
        return data

    # ---- 只读工具：活动 ----

    def search_events(
        self,
        *,
        q: str | None = None,
        city: str | None = None,
        category: str | None = None,
        date_from: str | None = None,
        date_to: str | None = None,
        min_price_cents: int | None = None,
        max_price_cents: int | None = None,
        has_remaining: bool | None = None,
        limit: int = 10,
    ) -> list[dict[str, Any]]:
        limit = max(1, min(int(limit), self._settings.max_tool_results))
        data = self._call(
            "POST",
            "/internal/ai-tools/events/search",
            {
                "q": q,
                "city": city,
                "category": category,
                "dateFrom": date_from,
                "dateTo": date_to,
                "minPriceCents": min_price_cents,
                "maxPriceCents": max_price_cents,
                "hasRemaining": has_remaining,
                "limit": limit,
            },
        )
        return _as_event_list(data)

    def get_event(self, event_id: int) -> dict[str, Any]:
        data = self._call("GET", f"/internal/ai-tools/events/{int(event_id)}")
        if not isinstance(data, dict) or "id" not in data:
            raise ToolError("backend returned malformed event")
        return data

    def nearby_events(self, *, lat: float, lng: float, radius_km: float = 20, limit: int = 10) -> list[dict[str, Any]]:
        limit = max(1, min(int(limit), self._settings.max_tool_results))
        data = self._call(
            "POST",
            "/internal/ai-tools/events/nearby",
            {"lat": lat, "lng": lng, "radiusKm": radius_km, "limit": limit},
        )
        return _as_event_list(data)

    def popular_events(self, limit: int = 8) -> list[dict[str, Any]]:
        limit = max(1, min(int(limit), self._settings.max_tool_results))
        data = self._call("GET", f"/internal/ai-tools/events/popular?limit={limit}")
        return _as_event_list(data)

    # ---- 只读工具：当前用户（需要签名的用户上下文） ----

    def my_preferences(self) -> dict[str, Any]:
        return self._call("GET", "/internal/ai-tools/users/me/preferences")

    def my_recent_categories(self) -> list[dict[str, Any]]:
        data = self._call("GET", "/internal/ai-tools/users/me/recent-categories")
        if not isinstance(data, list):
            raise ToolError("backend returned malformed categories")
        return data


def _as_event_list(data: Any) -> list[dict[str, Any]]:
    if not isinstance(data, list):
        raise ToolError("backend returned malformed event list")
    return [item for item in data if isinstance(item, dict)]


def dumps(value: Any) -> str:
    """工具返回统一转成 JSON 字符串（ToolMessage content）。"""
    return json.dumps(value, ensure_ascii=False, default=str)
