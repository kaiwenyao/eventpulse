.PHONY: up down down-v logs ps infra test test-frontend test-all smoke clean testcontainers-cleanup psql

# Build and start the full demo stack (postgres+kafka+backend+frontend).
up:
	docker compose up -d --build

# Infra only: Postgres / Kafka, for host-side backend + Vite.
infra:
	docker compose up -d postgres kafka

down:
	docker compose down

down-v:
	docker compose down -v

logs:
	docker compose logs -f backend

ps:
	docker compose ps

# Backend unit tests + JaCoCo 90% line coverage.
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

# End-to-end API smoke test against a running stack.
smoke:
	bash scripts/smoke-test.sh

psql:
	docker compose exec postgres psql -U eventpulse -d eventpulse

clean:
	mvn clean
	rm -rf frontend/dist
