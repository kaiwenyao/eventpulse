# EventPulse AI Service — FastAPI + LangChain，独立镜像（不经公网 Ingress 暴露）。
# 依赖由 uv.lock 精确锁定；测试不依赖真实 LLM Key（见 tests/）。
FROM ghcr.io/astral-sh/uv:python3.12-bookworm-slim

WORKDIR /app

# 先锁文件后源码：依赖不变时 Docker 层缓存直接命中。
COPY pyproject.toml uv.lock ./
RUN uv sync --frozen --no-dev --no-install-project

COPY app ./app
RUN uv sync --frozen --no-dev

# 非 root 运行；运行期直接用 venv 里的 uvicorn，不再需要 uv cache。
RUN useradd --system --uid 1001 --no-create-home aiservice \
 && chown -R aiservice:aiservice /app
USER aiservice
ENV PATH="/app/.venv/bin:$PATH"

EXPOSE 8090
HEALTHCHECK --interval=10s --timeout=3s --start-period=10s --retries=12 \
  CMD python -c "import urllib.request,sys;sys.exit(0 if urllib.request.urlopen('http://127.0.0.1:8090/healthz',timeout=2).status==200 else 1)" || exit 1

CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8090"]
