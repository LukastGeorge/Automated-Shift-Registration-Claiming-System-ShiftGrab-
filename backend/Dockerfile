# syntax=docker/dockerfile:1.7
# ShiftGrab backend (SG-70). Build:  docker build -t shiftgrab-backend backend
# Tests do not run here: CI runs them (with Testcontainers) before images are built and pushed.

# ---- build: compile and split the jar into Spring Boot layers -------------------------------------------------
FROM eclipse-temurin:25-jdk-alpine AS build
WORKDIR /src
COPY .mvn .mvn
COPY mvnw pom.xml ./
# Dependencies first: this layer is reused as long as pom.xml does not change.
RUN --mount=type=cache,target=/root/.m2 sh mvnw -B -q dependency:go-offline
COPY src src
RUN --mount=type=cache,target=/root/.m2 sh mvnw -B -q package -DskipTests \
 && java -Djarmode=tools -jar target/shiftgrab-backend-*.jar extract --layers --launcher --destination /extracted

# ---- runtime: JRE only, non-root -----------------------------------------------------------------------------
FROM eclipse-temurin:25-jre-alpine
# Pick up OS security fixes published after the base image was built (scanned by Trivy, SG-73).
RUN apk upgrade --no-cache && addgroup -S shiftgrab && adduser -S -G shiftgrab shiftgrab
WORKDIR /app
# Least to most frequently changed, so a code change only replaces the last, small layer.
COPY --from=build /extracted/dependencies/ ./
COPY --from=build /extracted/spring-boot-loader/ ./
COPY --from=build /extracted/snapshot-dependencies/ ./
COPY --from=build /extracted/application/ ./
USER shiftgrab
EXPOSE 8080
# Heap follows the container memory limit; crash fast on OOM so the orchestrator restarts a clean process.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
HEALTHCHECK --interval=15s --timeout=3s --start-period=60s --retries=3 \
  CMD wget -qO- http://127.0.0.1:8080/actuator/health/liveness | grep -q '"UP"' || exit 1
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
