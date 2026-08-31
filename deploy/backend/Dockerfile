# Multi-stage build: reproducible jar from pinned maven+jdk, then a slim JRE.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app

# Cache dependencies first so source changes rebuild fast.
COPY pom.xml .
RUN mvn -q -B dependency:go-offline

COPY src ./src
RUN mvn -q -B package -DskipTests

FROM eclipse-temurin:21-jre
WORKDIR /app
RUN useradd --system --uid 1001 eventpulse
USER eventpulse
COPY --from=build /app/target/eventpulse-backend-0.1.0.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
