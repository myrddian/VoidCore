# =============================================================================
# VOIDcore Spring Boot application image.
#
# This Dockerfile is a multi-stage build that:
#   1. builds the fat JAR with Gradle
#   2. extracts Spring Boot layers for better Docker caching
#   3. produces a minimal runtime image with JRE 21
#
# The actual application code lives alongside this Dockerfile in ./app/.
# Until the app exists, this Dockerfile is a placeholder; once you wire up
# the Gradle project, the same multi-stage build works unchanged.
# =============================================================================

# ---------- Build stage ------------------------------------------------------
FROM eclipse-temurin:21-jdk-alpine AS build

# Node + npm for the frontend bundle (esbuild). Pinned major version so the
# image is reproducible; bumping happens in lockstep with package.json.
RUN apk add --no-cache nodejs npm

WORKDIR /workspace

# Copy gradle wrapper and config first for layer caching
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle ./gradle
RUN chmod +x gradlew && ./gradlew --version

# Frontend deps cached separately from sources so source-only edits don't
# blow away node_modules.
COPY src/main/frontend/package.json src/main/frontend/package-lock.json src/main/frontend/
RUN cd src/main/frontend && npm ci --no-audit --no-fund

# Copy the rest of the sources and build (Gradle calls esbuild for us).
COPY src ./src
RUN ./gradlew --no-daemon clean bootJar

# Extract layers for better Docker caching at runtime
RUN mkdir -p /workspace/extracted \
 && java -Djarmode=layertools -jar build/libs/*.jar extract --destination /workspace/extracted

# ---------- Runtime stage ----------------------------------------------------
FROM eclipse-temurin:21-jre-noble

RUN apt-get update \
 && apt-get install -y --no-install-recommends wget tini tzdata ca-certificates \
 && rm -rf /var/lib/apt/lists/* \
 && groupadd --system voidcore \
 && useradd --system --gid voidcore --home-dir /home/voidcore --create-home --shell /usr/sbin/nologin voidcore

WORKDIR /app

# Layered copy for fast iteration
COPY --from=build --chown=voidcore:voidcore /workspace/extracted/dependencies/         ./
COPY --from=build --chown=voidcore:voidcore /workspace/extracted/spring-boot-loader/   ./
COPY --from=build --chown=voidcore:voidcore /workspace/extracted/snapshot-dependencies/ ./
COPY --from=build --chown=voidcore:voidcore /workspace/extracted/application/           ./

USER voidcore

EXPOSE 8080

ENV JAVA_OPTS=""
ENTRYPOINT ["/usr/bin/tini","--","sh","-c","exec java $JAVA_OPTS org.springframework.boot.loader.launch.JarLauncher"]
