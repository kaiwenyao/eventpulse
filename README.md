# 🚀 EventPulse

**English** | [简体中文](README.zh-CN.md)

---

**EventPulse** is a distributed event ticketing platform covering both sides of the
business: attendees discover / search / favourite events, place bookings, manage
a cart and a wallet ledger, and track orders and e-tickets in real time; organisers
publish events and manage lifecycles and attendee data. A single backend image runs
in three roles — **api / worker / seeder** — and `make up` starts 2 api + 2 worker
replicas by default, because distributed behaviour is exactly what this project set
out to exercise: cross-instance SSE delivery, multi-worker Outbox claiming, and
Kafka partition rebalancing. One command brings up the full stack locally with
Docker Compose, and the same images deploy straight to Kubernetes (k3s). 🎉

| Layer | Technology |
| --- | --- |
| Backend | Java 21 · Spring Boot · PostgreSQL · Redis · Kafka |
| Frontend | React 19 · TypeScript · Vite |
| AI service | Python 3.12 · FastAPI · LangChain |
| Infrastructure | Docker Compose · Kubernetes (k3s) · Jenkins · GitHub Actions |

---

## 📋 Table of Contents

- [✨ Features](#-features)
- [🚀 Getting Started](#-getting-started)
  - [🔧 Prerequisites](#-prerequisites)
  - [🐳 Docker Compose Startup](#-docker-compose-startup)
  - [🔑 Configuration](#-configuration)
- [💻 Usage](#-usage)
- [🧩 Architecture & Multi-Instance Design](#-architecture--multi-instance-design)
- [🤖 AI Assistant](#-ai-assistant)
- [📷 Image Storage (SeaweedFS S3)](#-image-storage-seaweedfs-s3)
- [📦 Kubernetes Deployment](#-kubernetes-deployment)
- [🔨 Local Development](#-local-development)
- [🧪 Testing](#-testing)
- [🤝 Contributing](#-contributing)
- [📝 License](#-license)
- [📧 Contact](#-contact)

---

## ✨ Features

- **🎫 End-to-end ticketing**: attendees discover / search / favourite events, place bookings, and track orders and e-tickets in real time; organisers publish events and manage lifecycles and attendee data.
- **🛒 Cart & wallet**: a cross-device cart settled in a single transaction per checkout; cart checkout requires an `Idempotency-Key` (direct bookings and top-ups accept one optionally for the same retry protection), so retries never double-order or double-charge; the wallet ledger records the balance before and after every change.
- **📡 Real-time notifications (SSE)**: order / wallet / cart changes flow through Outbox → Kafka → Redis broadcast to the browsers connected to any api instance; changes missed during a disconnection are backfilled on reconnect.
- **🔁 Neither lost nor duplicated**: the Outbox is claimed by an atomic UPDATE with `FOR UPDATE SKIP LOCKED` (lease + heartbeat renewal), the consumer-side `consumed_events` idempotency table absorbs redeliveries, and `message_key` keeps a given order's messages in the same Kafka partition, in order.
- **⚖️ Multi-instance safety**: hot events and statistics never live in a JVM's own fields (Redis cache with PostgreSQL fallback); concurrent bookings never oversell; event lifecycles are two conditional UPDATEs, so concurrent workers simply update 0 rows.
- **🤖 AI assistant**: natural-language event discovery (a LangChain agent over read-only tools querying real events) + organiser copywriting polish (structured output); with no key configured it clearly reports unavailable and normal features are unaffected.
- **📷 Image object storage**: S3-compatible SeaweedFS, configurable public direct URLs or `/api/media/images/{id}` proxying, soft delete + grace-period background cleanup.
- **🧪 Tests & CI**: Testcontainers integration tests cover the distributed paths with a 90% backend line-coverage gate; the frontend has ESLint + Vitest + Playwright; the AI service is tested against a scripted LLM; GitHub Actions runs every check and Jenkins releases to k3s.

---

## 🚀 Getting Started

### 🔧 Prerequisites

You need Docker Desktop (macOS / Windows) or Docker Engine + Compose v2 (Linux).

One backend image runs three roles via Spring profiles (matching the Kubernetes deployment):

```text
                     ┌──────────┐
      seeder  Job    │  seeder  │ one-off demo data seeding; exits when done
                     └──────────┘
      api Deployment │   api    │ HTTP & SSE only; runs as multiple replicas
                     └──────────┘
   worker Deployment │  worker  │ Kafka consumption / Outbox relay / event lifecycle
                     └──────────┘
```

### 🐳 Docker Compose Startup

Clear any leftover Compose environment and Testcontainers containers first, then start:

```bash
cp .env.example .env        # 1. Create the env file (demo defaults work as-is)
make down                   # 2. Stop all containers and delete volumes (clean slate)
make testcontainers-cleanup # 3. Remove leftover Testcontainers containers
make up                     # 4. Build and start: 2 api + 2 worker by default
make ps                     # 5. Check status: postgres / redis / kafka / seeder / api ×2 / worker ×2 / frontend
```

`make up` is multi-instance by default (`API=2 WORKER=2`): a single instance cannot
exercise cross-instance SSE delivery, multi-worker Outbox claiming, or Kafka
partition rebalancing. Replica counts are also written into `docker-compose.yml` as
`deploy.replicas`, so a plain `docker compose up -d` also gives 2 + 2. To change
the scale temporarily:

```bash
make up API=3 WORKER=1       # 3 api + 1 worker
```

Startup order: PostgreSQL / Redis / Kafka healthy → `seeder` seeds and exits
successfully → `api` and `worker` start (a compose `service_completed_successfully`
dependency). The first run builds the images, which takes a few minutes; the api
health check has a 60s `start_period`.

| Port | Service |
| --- | --- |
| 3000 | frontend (`/api` reverse-proxied to the api service, SSE buffering disabled) |
| 5432 | PostgreSQL |
| 6379 | Redis |
| 9092 | Kafka (host side: 19092) |

```bash
# Verify through the frontend proxy (api no longer binds a fixed host port 8080, which is what makes --scale possible)
curl -s http://localhost:3000/actuator/health
# Expected: {"status":"UP", ...}
```

Open http://localhost:3000 and you're in.

### 🔑 Configuration

All configuration lives in `.env` at the repo root (copy from `.env.example`); the
demo defaults run as-is. Key variables:

| Variable | Default | Notes |
| --- | --- | --- |
| `SECRET_KEY` | placeholder | JWT signing key; replace the dev default in real deployments (startup does not enforce it), and rotating it invalidates issued tokens |
| `DB_PASSWORD` | `eventpulse` | PostgreSQL password |
| `CORS_ORIGINS` | two localhost origins | allowed cross-origin sources (frontend proxy + Vite dev server) |
| `LLM_MODEL` / `LLM_API_KEY` | `gpt-4o-mini` / empty | model and credentials for the AI service; with an empty key the AI endpoints clearly report unavailable |
| `LLM_BASE_URL` | empty | OpenAI-compatible gateway base URL; must include the API prefix (e.g. `https://host/v1`) |
| `LLM_MAX_OUTPUT_TOKENS` | `4096` | reasoning models spend thinking tokens from this budget; too small a budget yields empty replies |
| `AI_SERVICE_TOKEN` / `AI_INTERNAL_TOKEN` | dev values | Spring Boot ↔ ai-service service-to-service credentials; must be replaced in real deployments |
| `S3_ENABLED` | `false` | when true, images go to S3; multi-replica deployments (`API>1` or k3s) must enable it |
| `S3_ENDPOINT` / `S3_BUCKET` / `S3_ACCESS_KEY` / `S3_SECRET_KEY` | empty | SeaweedFS S3 endpoint and credentials |

See `.env.example` for the full annotated list.

#### Distributed verification commands

```bash
make up-infra         # start only PostgreSQL / Redis / Kafka
make seed             # run only the seeder; exit code is passed through
make up-runtime       # start api / worker / frontend (also 2 instances each by default)
make up-distributed   # equivalent to make up, kept as the old name
make test-distributed # multi-instance + end-to-end smoke test
```

Multiple workers rely on the Outbox claiming mechanism (`claimed_until` lease +
heartbeat renewal + conditional database updates) so messages are neither lost nor
duplicated; multiple api instances rely on Redis broadcast for cross-instance SSE
delivery. See [Architecture & Multi-Instance Design](#-architecture--multi-instance-design).

---

## 💻 Usage

The `seeder` creates 8 accounts and 19 events (covering four categories and six
cities — Berlin, New York, London, Tokyo, Melbourne, São Paulo — plus all six
lifecycle states), 18 orders with their e-tickets, favourites, behaviour logs,
daily statistics, and in-app messages. Each of the 19 events has a cover: images
are pre-uploaded to object storage in `DemoCatalog.EVENTS` order (fixed keys
`seed/demo-covers/NN.jpeg`, NN being the event number); seeding only writes
`media_assets` rows and wires them into the event's `coverAssetId` / `coverUrl`
without any object-storage IO. If an object is missing from the bucket the cover
404s — re-uploading the same key fixes it. Seed content is centralised in
`backend/src/main/java/dev/kaiwen/eventpulse/seed/DemoCatalog.java`; change demo
data only in that one file.

Event times are computed relative to startup, so "upcoming / ongoing / ended" is
always self-consistent; the statistics curves are derived from event IDs without
random numbers, so every seed run produces the same numbers.

Demo accounts:

| Role | Email | Password | Notes |
| --- | --- | --- | --- |
| Regular user | `user@eventpulse.dev` | `User123456` | has orders, e-tickets, favourites, and messages |
| Organiser | `organiser@eventpulse.dev` | `Organiser123456` | owns most events, including drafts and archived ones |
| Organiser | `studio@eventpulse.dev` | `Organiser123456` | Soundwave Live, including one cancelled music festival |
| Organiser | `guild@eventpulse.dev` | `Organiser123456` | City Wanderers |
| Regular user | `priya@eventpulse.dev` | `User123456` | Priya Sharma |
| Regular user | `diego@eventpulse.dev` | `User123456` | Diego Ramirez |
| Regular user | `amara@eventpulse.dev` | `User123456` | Amara Okafor |
| Regular user | `yuki@eventpulse.dev` | `User123456` | Yuki Tanaka |

Events owned by different organiser accounts are isolated from one another, which
is handy for verifying that unauthorised access is properly rejected. For a
hands-on walkthrough of the cart / orders / wallet flow, see
[docs/acceptance-walkthrough.md](docs/acceptance-walkthrough.md) (about 15 minutes
from scratch).

Day to day:

```bash
make logs        # follow api / worker logs
make stop        # stop all containers, keep volumes (demo data, accounts, and orders survive)
make down        # stop all containers and delete volumes; the next make up reruns migrations and reseeds
```

`make down` wipes data: it runs `docker compose down -v --remove-orphans`, which
also stops extra instances started with `--scale` and orphan containers left
behind by edited service definitions, and deletes the `pgdata` volume. To keep the
data and merely stop the containers, use `make stop`. (`make down-v` remains as
the old name for `make down`.)

---

## 🧩 Architecture & Multi-Instance Design

### Multi-instance behaviour at a glance

- **Hot events and statistics never live in a JVM's own fields**: hot events are
  cached only in Redis (30s TTL + invalidate on change) and fall straight back to
  PostgreSQL when Redis is unavailable; cache fallbacks are counted by the
  Micrometer metric `eventpulse.cache.fallbacks` rather than a field on some
  instance.
- **Cross-instance SSE delivery**: browsers connect to any api instance. After the
  database transaction commits, the worker publishes a lightweight "something
  changed" reminder to Redis (the `eventpulse:sse` channel); every api instance
  subscribes and pushes it to the browsers connected to it. The final order /
  ticket state always comes from PostgreSQL — the frontend re-fetches over REST
  upon the reminder, and changes missed during a disconnection are backfilled
  after reconnecting. SSE subscriptions use the `Authorization: Bearer` header
  and verify order ownership; multiple tabs may watch the same order, the
  heartbeat is 25s, and a shutting-down api proactively closes its connections so
  browsers reconnect to another instance immediately.
- **Multi-worker safety**: the Outbox is claimed by a single atomic UPDATE with
  `FOR UPDATE SKIP LOCKED` (a `claimed_by` / `claimed_until` lease), and the whole
  batch's lease is renewed before each send — as long as a worker is alive its
  lease cannot expire mid-batch, so a message is never processed by two workers at
  once. If a worker crashes it stops renewing; the lease expires, another worker
  takes over, and redeliveries are absorbed by the consumer-side
  `consumed_events` idempotency table. At the end of a round (early exits
  included) leftover leases are returned in one go, so one Kafka hiccup cannot
  stall the relay for a whole lease period. Messages for the same order carry a
  `message_key` so they land in the same Kafka partition, preserving order. Event
  lifecycles are two conditional UPDATEs — concurrent workers simply update 0
  rows, with no optimistic-lock conflicts.
- **Idempotent seeder**: the `seed_runs` table records completed versions (in the
  same transaction as the seeding), so Kubernetes Job retries or manual re-runs
  never produce duplicate data.

### Cart, order history & wallet ledger

- **Cart**: signed-in users can "add to cart" from an event page (no charge, no
  stock held); the cart is persisted in the database and visible across devices,
  supports quantity adjustment, per-item selection, removal, and clearing, and
  shows per-item invalidation reasons (cancelled / off-sale / sold out / price
  changed…). Checkout settles all selected items in one transaction: one order per
  event, and the whole checkout rolls back if any item is unbuyable or the balance
  is insufficient. Checkout must carry an `Idempotency-Key` header — retries /
  double clicks never double-order or double-charge.
- **Order history**: "My bookings" lists all real statuses by default (cancelled
  included) with server-side pagination, status filters, time ranges, and
  order-number / event-name search; amounts always come from the order snapshot,
  and cancellation reasons (already cancelled / already redeemed / event already
  started…) are clearly annotated. `GET /api/bookings` moved from a full array to
  a `{total, records}` paginated structure (shipped in the same release as the
  frontend).
- **Wallet ledger**: top-ups, booking charges, user-initiated cancellation
  refunds, and event-cancellation refunds are all written to `wallet_ledger`
  inside the business transaction (signed amount, balance before and after,
  business dedup key); the profile's "balance details" page filters by type /
  time and links to the related order. Migrating old accounts generates an
  opening-balance record without changing the balance. Top-up remains a demo
  feature and is `Idempotency-Key` idempotent.
- **Events**: alongside `booking-events` there are now `wallet-events`
  (ledger-recorded announcements) and `cart-events` (cart-change announcements) —
  separate consumer groups, partitioned per user, deduplicated via
  `consumed_events`; after commit the worker sends an SSE refresh reminder over
  Redis to all of that user's pages (`/api/user/events`). If Kafka is unavailable
  the business operation still succeeds; messages wait in the Outbox and are
  delivered after recovery. See [docs/order-flow.md](docs/order-flow.md).

---

## 🤖 AI Assistant

AI is an external LLM capability invoked at runtime (no model training, no
embeddings). Architecture:

```text
Browser ──> Spring Boot /api/ai/** ──> Python AI Service ──> external LLM
                      │  (when the Agent needs business data)
                      └────< /internal/ai-tools/** (service-to-service credentials + short-term signed user context) ──> PostgreSQL
```

Two features:

| Feature | Entry point | Notes |
| --- | --- | --- |
| Organiser copywriting polish | `POST /api/ai/organiser/improve-event` (JWT ORGANISER) | a plain LLM call with structured output; review in the frontend first, then use the normal save / publish endpoints — nothing is auto-saved |
| Natural-language event discovery | `POST /api/ai/discovery/chat` (JWT optional) | a LangChain agent queries real events through read-only tools; signed-in users get sessions persisted to PostgreSQL and guests get single turns; Spring Boot re-verifies event visibility before returning |

Boundaries and degradation:

- The browser never talks to the Python service directly; the LLM API key exists
  only in the ai-service Secret.
- `/internal/**` requires service-to-service credentials and is never exposed
  through the public Ingress; the userId comes from a short-term token signed by
  Spring Boot — neither the model nor the request body can determine identity.
- Without `LLM_API_KEY` configured, the AI endpoints clearly report unavailable;
  search, editing, and booking are unaffected.
- Rate limits (per user / IP per minute), tool-call counts, input / output
  lengths, timeouts, and retries all have caps; LLM output is treated as
  untrusted data — fabricated or delisted event IDs are discarded.
- Every request is recorded in `ai_requests` (status, latency, token usage) —
  no keys, no full prompts.

Configuration lives in the `AI` section of `.env.example` (provider / model / key /
base_url / timeouts / service credentials). Any OpenAI-compatible gateway works
(set `LLM_BASE_URL`). **A note on reasoning-style models (e.g. deepseek-v4)**:
thinking tokens count against the `LLM_MAX_OUTPUT_TOKENS` output budget — a
budget that is too small (e.g. 1024) yields empty replies; the default is 4096.
For local debugging: after `make up`, open http://localhost:3000 and ask away via
"AI event search" on the home page; organisers sign in and click "AI polish copy"
in the event form.

---

## 📷 Image Storage (SeaweedFS S3)

Images (event covers etc.) live on a self-hosted SeaweedFS S3-compatible endpoint
rather than an api pod's local disk: both api replicas read and write the same
objects and the frontend needs no changes. All business semantics are preserved —
uploads still require sign-in, are capped at 2MB, accept only JPEG / PNG / WebP,
and the upload response shape is unchanged. Object keys are backend-generated
(UUID prefix) and Content-Type is stored with the object; if the database save
fails after a successful upload, the just-uploaded object is deleted in
compensation; S3 unreachability / bad credentials map to 503 (reads and uploads
alike) and a missing object to 404 — storage failures are never reported as
request errors.

Deletion is soft: `DELETE` only changes database audit fields (`status=DELETED` +
`deleted_at`) with permission checks unchanged; the S3 object is deleted by the
worker's cleanup task after a grace period and then marked `PURGED` (it only
cleans DELETED objects recorded in the database whose grace period has passed; a
failed delete stays DELETED for the next round; S3 delete is idempotent — a
missing object counts as deleted).

Configuration (`eventpulse.s3.*` in `application.yml`, all overridable via
environment variables):

| Variable | Default | Notes |
| --- | --- | --- |
| `S3_ENABLED` | `false` | true routes images to S3; false falls back to local disk (single-instance local only) |
| `S3_ENDPOINT` | empty | e.g. `https://s3.kaiwen.dev` |
| `S3_REGION` | `us-east-1` | S3-compatible services usually use us-east-1 |
| `S3_BUCKET` | `eventpulse` | must already exist; the app neither creates buckets nor changes public permissions |
| `S3_ACCESS_KEY` / `S3_SECRET_KEY` | empty | credentials of a dedicated identity, via env vars / K8s Secret, never committed |
| `S3_PATH_STYLE` | `true` | SeaweedFS uses path-style (`https://endpoint/bucket/key`) |
| `S3_PUBLIC_BASE_URL` | empty | browser-direct base URL (see below); leave empty to serve images via the `/api/media/images/{id}` proxy |
| `S3_CONNECT_TIMEOUT` / `S3_READ_TIMEOUT` / `S3_API_CALL_TIMEOUT` | 2000 / 10000 / 30000 | milliseconds |

### How image URLs are resolved

The backend hands out the image URL in the `public_url` field, and **the frontend
treats it as an opaque string** — never assemble endpoint and key yourself. That
way switching CDN, switching bucket, or moving an asset class to pre-signed URLs
never touches the frontend.

With `S3_PUBLIC_BASE_URL` set you get **public direct URLs**: `public_url` points
at object storage, image bytes never pass through the api process, and browsers
and CDNs can cache long-term (objects carry
`Cache-Control: public, max-age=31536000, immutable`, and keys contain a UUID so
the content behind them never changes). The URL is assembled from the key by pure
string operations — no storage request is made.

Leave it empty and images fall back to the `/api/media/images/{id}` **proxy**: the
backend checks database state and streams content from storage. Local-disk mode
(`S3_ENABLED=false`) always uses this path. Both paths are here to stay — the
`MediaController` GET endpoint is not going away.

Direct URLs require the bucket to grant anonymous reads — **the application
neither checks nor modifies that permission**. On the SeaweedFS side, add a bucket
policy (object-level `s3:GetObject` only):

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Sid": "PublicReadObjects",
      "Effect": "Allow",
      "Principal": "*",
      "Action": ["s3:GetObject"],
      "Resource": ["arn:aws:s3:::eventpulse/*"]
    }
  ]
}
```

The trailing `/*` on `Resource` marks object level — it must not be the bucket
itself; do not add `s3:ListBucket` to `Action` — user-uploaded keys contain a UUID
and are unenumerable, and once listing is granted that protection is gone (it
would also expose objects still inside the soft-delete grace period). Verify
afterwards with unauthenticated requests:

```bash
curl -s -o /dev/null -w "%{http_code}\n" https://s3.kaiwen.dev/eventpulse/seed/demo-covers/01.jpeg   # expect 200
curl -s -o /dev/null -w "%{http_code}\n" https://s3.kaiwen.dev/eventpulse/                            # expect 403
```

Cleanup task (run by the worker): `MEDIA_PURGE_ENABLED` (default true),
`MEDIA_PURGE_AFTER_DAYS` (grace period in days, default 7),
`MEDIA_PURGE_BATCH_SIZE` (50), `MEDIA_PURGE_FIXED_DELAY_MS` (3600000).

### Local-disk fallback (default)

By default `S3_ENABLED=false` and images still land in `MEDIA_DIR` (default
`data/media`) — behaviour unchanged. To use SeaweedFS, set the variables above in
the runtime environment:

```bash
S3_ENABLED=true S3_ENDPOINT=https://s3.kaiwen.dev S3_BUCKET=eventpulse \
S3_ACCESS_KEY=... S3_SECRET_KEY=... \
mvn -pl backend spring-boot:run -Dspring-boot.run.profiles=api
```

Under compose the same variables go into `.env` (already passed through in
`docker-compose.yml`); `.env.example` has the full list. Multi-replica setups
(`API>1` or k3s) must enable S3.

### k3s

`k3s-home/apps/eventpulse/configmap.yaml` already carries the non-sensitive S3
variables (all three roles share one envFrom: api reads and writes objects, the
worker runs cleanup, the seeder only needs to boot); the `S3_ACCESS_KEY` /
`S3_SECRET_KEY` credentials are sealed in `sealed-secret.yaml`. For the
SeaweedFS-side identity, bucket, and permission verification, see the k3s-home
repo README's "S3 image storage" section.

### What about existing local images? (migration plan, not executed)

Historical images sit in `data/media` inside each api container; neither compose
nor k8s mounts a persistent volume for it, so recreating the container loses them
— usually there is nothing to migrate. If you do have local files worth keeping,
migrate in this order (local files are not deleted, so you can start over if
anything goes wrong):

1. Object keys reuse the `storage_key` already in the database — no database
   records change:
   `aws s3 sync ./data/media/ s3://eventpulse/ --endpoint-url https://s3.kaiwen.dev`
   (keys end in `.png` / `.jpg` / `.webp`, so the CLI infers Content-Type; pass
   `S3_ACCESS_KEY` / `S3_SECRET_KEY` through environment variables.)
2. After the sync completes, roll out `S3_ENABLED=true` together with the new image.
3. New uploads go straight to S3 after the switch; if any instance still wrote
   files locally during the switchover window, sync once more to catch up. Don't
   delete the local directory until you're sure; afterwards, deletion only
   affects local leftovers.

---

## 📦 Kubernetes Deployment

`deploy/k8s/` holds the manifests for the three roles of the same image:

```text
configmap.yml               common config (DB / Kafka / Redis addresses, partitions, heartbeat, batches, AI gateway address, S3 address)
ai-configmap.yml            AI service non-sensitive config (LLM provider / model / timeouts)
secret.example.yml          example secrets (DB_PASSWORD / SECRET_KEY / AI service credentials / S3 credentials); copy to secret.yml before use
api-deployment.yml          api, 2 replicas, readiness/liveness split, 40s graceful shutdown
api-service.yml             ClusterIP Service
worker-deployment.yml       worker, 1 replica for the first release (safe to scale to 2), exposes only Actuator
seeder-job.yml              Job (versioned name, backoffLimit=3, restartPolicy=Never)
ai-service-deployment.yml   Python AI service (FastAPI + LangChain), starts at 1 replica, scalable
ai-service-service.yml      ClusterIP Service (cluster-internal only, not exposed through Ingress)
ingress.yml                 /api routes to the api Service; buffering off and long timeouts for SSE
```

```bash
kubectl apply -f deploy/k8s/configmap.yml -f deploy/k8s/ai-configmap.yml
kubectl apply -f deploy/k8s/secret.example.yml   # fill in real values first, or switch to sealed-secrets
kubectl apply -f deploy/k8s/seeder-job.yml
kubectl wait --for=condition=complete job/eventpulse-seeder-v1 --timeout=300s
kubectl apply -f deploy/k8s/api-deployment.yml -f deploy/k8s/api-service.yml \
              -f deploy/k8s/worker-deployment.yml \
              -f deploy/k8s/ai-service-deployment.yml -f deploy/k8s/ai-service-service.yml \
              -f deploy/k8s/ingress.yml
```

Release flow: wait for the Seeder Job to succeed (`kubectl wait ...
condition=complete`) before confirming the API / Worker rollouts; if the Job
fails, halt the release and keep the logs. The image name is currently the
placeholder `eventpulse/backend:v1.0` — replace it with the real registry image
before releasing (e.g. `ghcr.io/<owner>/eventpulse-backend:<commit-sha>`). All
API instances share the same `SECRET_KEY`, and api / worker / seeder share the
same database connection settings.

### Jenkins auto-updates k3s-home

The backend pipeline runs unit tests, Testcontainers integration tests, the JaCoCo
report, the 90% line-coverage gate, and JAR packaging in a single `mvn verify`;
the subsequent Coverage stage only publishes the report. The Maven repository
uses a node-local `hostPath` — host path `/var/cache/jenkins/maven/repository`,
container mount path `/var/cache/maven/repository` — shared by Java projects and
branches on the same node, no longer per-project or per-job directories, and no
NFS. Kubelet creates the directory via `DirectoryOrCreate` and the Maven container
writes with the image's default root user; build nodes must allow that hostPath
and keep the path on local disk. Every onboarded project must mount the same
hostPath, use a compatible Maven 3.9.x, and pass the following arguments in its
Maven commands so that separate processes coordinate reads and writes on the
shared repository with the same file locks:

```sh
-Dmaven.repo.local=/var/cache/maven/repository \
-Daether.syncContext.named.factory=file-lock \
-Daether.syncContext.named.nameMapper=file-gav
```

`disableConcurrentBuilds()` only serialises builds within the same Jenkins job;
cross-project repository concurrency is handled by the file locks above. The cache
survives across Pods; the first build on a node downloads the dependencies, which
other projects then reuse. `cleanWs()` does not clear this cache; maintenance
cleanup should happen after all builds that use the node's cache have stopped.
Projects running `mvn install` should isolate their local artifacts separately so
same-coordinate branch artifacts never overwrite each other; EventPulse uses
`verify` and never installs project artifacts into the shared repository. Build
logs print the repository path in use and the Maven verify duration. Old
per-project caches are neither migrated nor deleted automatically.

The backend Jenkins console keeps Maven stage progress, test statistics, and
failure summaries. Surefire writes each test's stdout/stderr to
`target/surefire-reports/*-output.txt`, and successful cases' XML no longer embeds
those outputs. Whether tests pass or fail, the existing test reports are
compressed into the build attachment `backend/target/backend-test-logs.tar.gz`,
downloadable from Jenkins Artifacts for troubleshooting; JUnit results are still
published as usual, and test or coverage failures still block the release. CI sets
`SQL_LOG_LEVEL=WARN` to silence per-statement SQL DEBUG output; the
Kafka-unavailable tests only demote `AdminMetadataManager`'s repeated reconnect
INFO logs to WARN, keeping warnings, errors, and assertions intact.

The AI pipeline uses a node-local `emptyDir` working volume, keeping the uv cache
and `.venv` on the same filesystem and installing dependencies via hard links,
avoiding copying large numbers of small files off NFS one by one. The cache dies
with the build Pod — every new build re-downloads dependencies; the shared Maven
PVC is no longer used for the uv cache. Dependency sync still uses
`uv sync --frozen --extra dev`, and tests run via `uv run --no-sync pytest`,
reusing the freshly installed environment. The sync stage prints the uv cache path
and duration so actual CI performance can be compared.

The three Jenkinsfiles follow nightdeal's release approach: after main pushes
GHCR images successfully, a separate `gitops` container updates the
`kaiwenyao/k3s-home` main branch. PRs and ordinary branches never write to the
config repo; a failed or unstable pipeline never proceeds to release either.

| Pipeline | Manifests auto-updated (under `apps/eventpulse/`) |
| --- | --- |
| backend | `api-deployment.yaml`, `worker-deployment.yaml`, `seeder-job.yaml` |
| frontend | `frontend-deployment.yaml` |
| ai-service | `ai-service-deployment.yaml` |

Jenkins needs access to the same `k3s-home-write` credentials as nightdeal
(Username with password; the password is a GitHub token with Contents write
access to k3s-home). Image pushes keep using `ghcr-token`.
`scripts/update-k3s-home.sh` uses the just-pushed `FULL_IMAGE` directly and only
replaces the corresponding image lines; when the version is unchanged no commit
is created, and a missing target manifest or an image mismatch fails the build.
When the three jobs push at the same time and conflict, the service's change is
re-applied on top of the latest remote main, up to five attempts, never
force-pushing.

API, Worker, and Seeder are updated to the same backend image in a single Git
commit so all three carry the same Flyway migration files; if any manifest is
missing or an image match looks wrong the whole update fails — no partial pushes.
The Job name stays `eventpulse-seeder`; an already-created Job's Pod template is
immutable, so once the image changes, the resource-level annotation
`argocd.argoproj.io/sync-options: Force=true,Replace=true` on
`k3s-home/apps/eventpulse/seeder-job.yaml` makes Argo CD delete the old Job and
recreate it. The Job remains in wave 0 — it runs once the database is ready, and
only a successful run updates the wave-10 apps; on re-runs `seed_runs` skips
seeding that already completed. With plain `kubectl apply` you still have to
delete the old Job manually. The GitOps script only updates images and preserves
the annotation above; it never touches database migration history. Integration
tests use a temporary local repository and never reach GitHub:

```bash
python3 -m unittest discover -s scripts/tests -v
```

---

## 🔨 Local Development

You need JDK 21, Maven, and Node.js locally. Clean up leftovers first, then start
only the infrastructure:

```bash
make down
make testcontainers-cleanup
make up-infra    # start only postgres / redis / kafka
```

```bash
# Terminal 1: api role (HTTP & SSE only)
mvn -pl backend spring-boot:run -Dspring-boot.run.profiles=api
```

```bash
# Terminal 2: worker role (Kafka / Outbox / lifecycle)
mvn -pl backend spring-boot:run -Dspring-boot.run.profiles=worker
```

```bash
# Terminal 3: seed once (seeder role, exits when done)
mvn -pl backend spring-boot:run -Dspring-boot.run.profiles=seeder
```

```bash
# Terminal 4: Python AI service (with an empty LLM_API_KEY the AI clearly reports unavailable)
cd ai-service && uv sync && uv run uvicorn app.main:app --port 8090
```

```bash
# Terminal 5
cd frontend && npm ci && npm run dev
```

Frontend: http://localhost:5173

### Frontend structure

```
frontend/src
├─ App.tsx           routing and app shell (top bar / toasts / footer)
├─ api.ts  auth.tsx  networking and session
├─ types.ts          view models mapped from backend DTOs, category and status dictionaries
├─ lib/              pure helpers (ISO ↔ datetime-local conversion, relative time, SSE subscription)
├─ ui/               design-system primitives: Field / Badges / Modal / Toast / Skeleton / Icons
├─ components/       cross-page components (top bar, event ticket card)
├─ pages/            attendee pages (discover, detail, login, booking, favourites, messages)
├─ organiser/        organiser console (overview, event table, publish form, lifecycle, attendees, data)
└─ styles/           stylesheets split by concern, aggregated by styles.css
```

The publish-form logic (defaults, field validation, request-body mapping) is
centralised in `organiser/eventForm.ts` — pure functions with their own unit
tests; page components only wire state into the form controls. The order detail
page subscribes to order event reminders via `lib/sse.ts` (fetch-based,
Authorization header, exponential-backoff auto-reconnect) and re-fetches REST data
whenever a reminder arrives.

---

## 🧪 Testing

Backend tests pull Testcontainers (Docker required locally); `*IT.java` covers
the distributed behaviour:

| Test | Covers |
| --- | --- |
| `OutboxClaimIT` | two workers claim concurrently without duplicates, per-key ordering, lease-expiry takeover, isolation without blocking |
| `KafkaOutboxE2EIT` | real Kafka: Outbox → Relay → Consumer → notification persisted; per-key ordering; topic partition count |
| `KafkaPartitionIT` | topic created with 3 partitions per config; two workers in one group share the partitions and both consume; same key stays ordered in the same partition |
| `SseReminderDeliveryIT` | real Redis: publish → broadcast → subscribe → local connection; replay dedup |
| `*ProfileWiringIT` | what each of the api / worker / seeder profiles wires up and excludes |
| `JwtInterceptorAsyncTest` | ThreadLocal cleanup for async SSE requests; thread reuse never mixes identities |
| `BookingConcurrencyIT` | concurrent bookings never oversell |
| `AiMigrationIT` | V9 migration: fresh database and upgrade-from-old paths; old recommendation tables dropped |
| `AiGatewayServiceTest` / `AiServiceClientTest` / `InternalServiceInterceptorTest` | AI gateway rate limiting, event re-verification, fabricated-ID filtering, service-to-service auth |
| `MediaServiceTest` / `S3MediaStorageTest` / `MediaPurgeWorkerTest` | image upload validation, key generation, compensation delete on DB failure, 404/503 mapping on reads, soft delete never touches objects, S3 exception translation, cleanup-task semantics |
| `MediaS3ProfileWiringIT` / `MediaS3WorkerProfileWiringIT` / `MediaS3SeederProfileWiringIT` | S3-enabled wiring and startup compatibility for api / worker / seeder (S3Client construction makes no network calls); default falls back to local disk |
| `S3LiveMediaStorageIT` | real S3 read/write/delete connectivity (runs only with `MEDIA_S3_LIVE_TEST=true`; uses only the `__eventpulse-selftest/` temp prefix and cleans up after itself) |

The Python AI service tests (scripted LLM and tool responses — CI never calls
paid models and needs no real key):

```bash
make test-ai
```

```bash
make testcontainers-cleanup  # remove leftover Testcontainers containers
make test                    # backend mvn verify (unit + IT + JaCoCo 90% line-coverage gate)
make test-frontend           # ESLint + Vitest + Playwright
make test-all                # backend + frontend + AI service, all three layers
```

Full-stack smoke test (needs local `curl` and `python3`):

```bash
make up                       # 2 api + 2 worker by default
make smoke                    # targets http://localhost:3000 by default (frontend proxy)
# api no longer binds a fixed host port (multi-instance would conflict); everything
# goes through the frontend Nginx; to hit one instance directly:
# docker compose exec api curl -s localhost:8080/actuator/health
```

When everything passes it prints `SMOKE TEST: ALL GREEN`.

---

## 🤝 Contributing

Contributions are welcome! The workflow follows the repo's existing habits
(Conventional Commits + PRs):

1. Fork the repo (or branch off `main` in-repo):
   ```bash
   git checkout -b feat/your-feature
   ```
2. Commit with a Conventional Commits message (`feat:` / `fix:` / `ci:` / `docs:`
   + description; this repo writes commit subjects in Chinese):
   ```bash
   git commit -m 'feat: 支持按城市筛选活动'
   ```
3. Push the branch:
   ```bash
   git push origin feat/your-feature
   ```
4. Open a Pull Request and merge once CI is green.

PRs run GitHub Actions (gitleaks secret scan, dependency checks, backend
Testcontainers integration tests, frontend ESLint + Vitest + typecheck + build +
Playwright, AI service pytest, and Compose / K8s config validation); a successful
main push is then released by Jenkins — GHCR images plus the k3s config-repo
update. Backend changes should keep `make test` green (including the JaCoCo 90%
line-coverage gate); frontend changes should keep `make test-frontend` green.

---

## 📝 License

No open-source license has been declared for this project yet; all rights are
reserved by default. If you want to reuse the code, please reach out via
[Issues](https://github.com/kaiwenyao/eventpulse/issues) first.

---

## 📧 Contact

- **GitHub Issues**: <https://github.com/kaiwenyao/eventpulse/issues>
- **Author**: <https://github.com/kaiwenyao>

---

Made with ❤️ by [kaiwenyao](https://github.com/kaiwenyao)