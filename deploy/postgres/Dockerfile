# PostgreSQL 18.6 (official multi-arch image) + PostGIS 3.6 + pgvector.
# postgis/postgis upstream images ship no linux/arm64 manifest, so we compose
# our own from the official postgres image: PostGIS via PGDG apt (multi-arch),
# pgvector compiled from a pinned source tag.
#
# Same image is used by docker-compose (demo stack) and by Testcontainers
# (`eventpulse/postgres:18-3.6-pgvector`). Build before `mvn verify`:
#   docker build -t eventpulse/postgres:18-3.6-pgvector deploy/postgres
FROM postgres:18.6

ARG PGVECTOR_VERSION=0.8.6

RUN apt-get update \
 && apt-get install -y --no-install-recommends \
      build-essential git ca-certificates postgresql-server-dev-18 \
      postgresql-18-postgis-3 postgresql-18-postgis-3-scripts \
 && git clone --branch "v${PGVECTOR_VERSION}" --depth 1 \
      https://github.com/pgvector/pgvector.git /tmp/pgvector \
 && make -C /tmp/pgvector OPTFLAGS="" \
 && make -C /tmp/pgvector install \
 && rm -rf /tmp/pgvector \
 && apt-get purge -y build-essential git postgresql-server-dev-18 \
 && apt-get autoremove -y \
 && rm -rf /var/lib/apt/lists/*

# Extensions (postgis, vector) are created by Flyway migration V0__extensions.sql.
