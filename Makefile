.PHONY: up down stop down-v logs ps infra up-infra seed up-runtime up-distributed test-distributed test test-frontend test-ai test-all smoke clean testcontainers-cleanup psql

# 实例数。api 与 worker 默认各 2 个：这个项目要验证的就是多实例行为
# （SSE 跨实例送达、Outbox 领取租约、Kafka 分区再均衡、Nginx 轮询），
# 单实例一条都跑不出来。docker-compose.yml 里的 deploy.replicas 是同一个值，
# 所以直接 `docker compose up -d` 也是 2 + 2。
# 临时改规模：make up API=3 WORKER=1
API ?= 2
WORKER ?= 2

# 完整栈：先等基础设施健康，seeder 播种完成后自动启动 api/worker/frontend。
# 默认 2 个 api + 2 个 worker。
up:
	docker compose up -d --build --scale api=$(API) --scale worker=$(WORKER)

# 基础设施 only：Postgres / Redis / Kafka。
infra up-infra:
	docker compose up -d postgres redis kafka

# 只运行 seeder（一次性任务），退出码透传；已播种过则幂等跳过。
seed:
	docker compose up --exit-code-from seeder seeder

# 常驻服务：api + worker + frontend（compose 会自动先跑完 seeder 再起它们）。
up-runtime:
	docker compose up -d --scale api=$(API) --scale worker=$(WORKER) api worker frontend

# 保留的旧名字：up 现在默认就是多实例，这里等价于 make up。
up-distributed: up

# 多实例冒烟：起 2 api + 2 worker，跑一遍端到端 API 用例。
test-distributed: up
	docker compose up -d --wait frontend
	bash scripts/smoke-test.sh

# 停掉本项目全部容器（含 --scale 起的额外实例，以及服务定义变更后残留的
# 孤儿容器）并删除数据卷 —— 数据会被清空。
# 下次 make up 会重新跑 Flyway 迁移并重新 seed。只想停容器、保留数据用 make stop。
down:
	docker compose down -v --remove-orphans

# 停掉全部容器但保留数据卷（演示数据、账号、订单都还在）。
stop:
	docker compose down --remove-orphans

# 保留的旧名字：down 现在已经会删数据卷。
down-v: down

logs:
	docker compose logs -f api worker

ps:
	docker compose ps

# Backend unit tests + Testcontainers ITs + JaCoCo 90% line coverage.
test:
	mvn verify; status=$$?; $(MAKE) testcontainers-cleanup; exit $$status

test-frontend:
	cd frontend && npm ci && npm run lint && npm run coverage && npx playwright install --with-deps chromium && npm run e2e

# Python AI 服务测试：模拟 LLM 与工具响应，不调用付费模型、不需要真实 Key。
test-ai:
	cd ai-service && uv sync --extra dev && uv run pytest

test-all: test test-frontend test-ai

# Ryuk is disabled for determinism; clean leftover Testcontainers containers.
# Portable across GNU/BSD xargs (macOS has no xargs -r).
testcontainers-cleanup:
	@ids=$$(docker ps -aq --filter label=org.testcontainers=true); \
	if [ -n "$$ids" ]; then docker rm -f $$ids; fi

# End-to-end API smoke test against a running stack (default via frontend nginx).
smoke:
	bash scripts/smoke-test.sh

psql:
	docker compose exec postgres psql -U eventpulse -d eventpulse

clean:
	mvn clean
	rm -rf frontend/dist
