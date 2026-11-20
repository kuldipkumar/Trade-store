# syntax=docker/dockerfile:1
# Multi-stage build so `docker compose up` works from a fresh clone (A-49).

FROM eclipse-temurin:21.0.13_9-jdk-noble AS build
WORKDIR /workspace
COPY mvnw pom.xml ./
COPY .mvn .mvn
RUN ./mvnw -B -q dependency:go-offline
COPY src src
RUN ./mvnw -B -q package -DskipTests

FROM eclipse-temurin:21.0.13_9-jre-noble
# Run as a non-root user (A-47, US-606.2).
RUN groupadd --system app && useradd --system --gid app --no-create-home app
WORKDIR /app
COPY --from=build --chown=app:app /workspace/target/trade-store-*.jar app.jar
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
