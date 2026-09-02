.PHONY: up down down-v logs ps infra up-infra seed up-runtime up-distributed test-distributed test test-frontend test-all smoke clean testcontainers-cleanup psql

# 完整栈：先等基础设施健康，seeder 播种完成后自动启动 api/worker/frontend。
up:
	docker compose up -d --build

# 基础设施 only：Postgres / Redis / Kafka。
infra up-infra:
	docker compose up -d postgres redis kafka

# 只运行 seeder（一次性任务），退出码透传；已播种过则幂等跳过。
seed:
	docker compose up --exit-code-from seeder seeder

# 常驻服务：api + worker + frontend（compose 会自动先跑完 seeder 再起它们）。
up-runtime:
	docker compose up -d api worker frontend

# 分布式验证：2 个 api + 2 个 worker。多 Worker 依赖 Outbox 领取机制
# （claimed_until 租约 + 数据库条件更新），已就绪。
up-distributed:
	docker compose up -d --scale api=2 --scale worker=2

# 双实例冒烟：起 2 api + 2 worker，跑一遍端到端 API 用例。
test-distributed: up-distributed
	docker compose up -d --wait frontend
	bash scripts/smoke-test.sh

down:
	docker compose down

down-v:
	docker compose down -v

logs:
	docker compose logs -f api worker

ps:
	docker compose ps

# Backend unit tests + Testcontainers ITs + JaCoCo 90% line coverage.
test:
	mvn verify; status=$$?; $(MAKE) testcontainers-cleanup; exit $$status

test-frontend:
	cd frontend && npm ci && npm run lint && npm run coverage && npx playwright install --with-deps chromium && npm run e2e

test-all: test test-frontend

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
