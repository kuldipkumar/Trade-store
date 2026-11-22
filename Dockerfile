# syntax=docker/dockerfile:1
# Multi-stage build so `docker compose up` works from a fresh clone (A-49).

# Build stage: the official Maven image, so the build doesn't download a Maven distribution through the wrapper
# (that download was refused by Maven Central on CI runners). Dependency downloads are retried by Maven itself.
FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /workspace
ENV MAVEN_ARGS="-B -q -Daether.connector.http.retryHandler.count=5"
COPY pom.xml ./
RUN mvn dependency:go-offline
COPY src src
# Tests are skipped entirely (not compiled): CI runs them before the image is built. -DskipTests alone is not
# enough because the pom ties the surefire skip to skipUnitTests.
RUN mvn package -Dmaven.test.skip=true

FROM eclipse-temurin:21.0.13_9-jre-noble
# Run as a non-root user (A-47, US-606.2).
RUN groupadd --system app && useradd --system --gid app --no-create-home app
WORKDIR /app
COPY --from=build --chown=app:app /workspace/target/trade-store-*.jar app.jar
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
