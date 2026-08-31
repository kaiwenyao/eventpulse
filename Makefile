.PHONY: up down logs ps test smoke seed clean db-sql

# Build and start the full demo stack (postgres+kafka+redis+backend+frontend).
up:
	docker compose up -d --build

down:
	docker compose down

down-v:
	docker compose down -v

logs:
	docker compose logs -f backend

ps:
	docker compose ps

# Backend unit + Testcontainers integration tests (requires Docker).
# Tests reuse the compose postgres image (PG18 + PostGIS + pgvector, multi-arch).
test:
	docker build -t eventpulse/postgres:18-3.6-pgvector deploy/postgres
	cd backend && mvn test; make -C .. testcontainers-cleanup

# Ryuk is disabled for determinism; clean leftover Testcontainers containers manually.
testcontainers-cleanup:
	-docker ps -aq --filter label=org.testcontainers=true | xargs -r docker rm -f

# End-to-end API smoke test against a running stack.
smoke:
	bash scripts/smoke-test.sh

psql:
	docker compose exec postgres psql -U eventpulse -d eventpulse

clean:
	cd backend && mvn clean
	rm -rf frontend/dist
