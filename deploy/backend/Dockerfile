# Multi-stage build: reproducible jar from pinned maven+jdk, then a slim JRE.
# Build context is `backend/` (see docker-compose.yml). Tests are not run
# here — they need Testcontainers and are documented in the README.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app

# Cache dependencies first so source changes rebuild fast.
COPY pom.xml .
RUN mvn -q -B dependency:go-offline

COPY src ./src
RUN mvn -q -B package -DskipTests

FROM eclipse-temurin:21-jre
WORKDIR /app
# curl is required by the compose healthcheck (`/actuator/health`). Install
# as root before dropping to the runtime user.
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/* \
 && useradd --system --uid 1001 --no-create-home eventpulse
USER eventpulse
COPY --from=build /app/target/eventpulse-backend-0.1.0.jar app.jar
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=5s --start-period=60s --retries=12 \
  CMD curl -fs http://localhost:8080/actuator/health | grep -q '"status":"UP"' || exit 1
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
