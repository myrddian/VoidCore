# =============================================================================
# VOIDcore Spring Boot application image.
#
# This Dockerfile is a multi-stage build that:
#   1. builds the fat JAR with Gradle
#   2. extracts Spring Boot layers for better Docker caching
#   3. produces a minimal runtime image with GraalVM JDK 21
#
# The actual application code lives alongside this Dockerfile in ./app/.
# Until the app exists, this Dockerfile is a placeholder; once you wire up
# the Gradle project, the same multi-stage build works unchanged.
# =============================================================================

# ---------- Frontend stage ---------------------------------------------------
FROM node:22-bookworm-slim AS frontend

WORKDIR /workspace/src/main/frontend

# Frontend deps cached separately from sources so source-only edits don't
# blow away node_modules.
COPY src/main/frontend/package.json src/main/frontend/package-lock.json ./
RUN npm install --no-audit --no-fund

# Copy the frontend sources and build the static bundle into the path Gradle
# later packages from src/main/resources/static/.
COPY src/main/frontend ./
RUN npm run build

# ---------- Build stage ------------------------------------------------------
FROM ghcr.io/graalvm/jdk-community:21 AS build

RUN microdnf install -y findutils \
 && microdnf clean all

WORKDIR /workspace

# Copy gradle wrapper and config first for layer caching
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle ./gradle
RUN chmod +x gradlew && ./gradlew --version

# Copy the rest of the sources and inject the prebuilt frontend assets.
COPY src ./src
COPY --from=frontend /workspace/src/main/resources/static ./src/main/resources/static
COPY dev-instance ./dev-instance
RUN ./gradlew --no-daemon clean bootJar -x npmInstall -x frontendBuild

# Extract layers for better Docker caching at runtime
RUN mkdir -p /workspace/extracted \
 && java -Djarmode=layertools -jar build/libs/*.jar extract --destination /workspace/extracted

# ---------- Runtime stage ----------------------------------------------------
FROM ghcr.io/graalvm/jdk-community:21

RUN microdnf install -y wget tzdata shadow-utils \
 && microdnf clean all \
 && groupadd --system voidcore \
 && useradd --system --gid voidcore --home-dir /home/voidcore --create-home --shell /sbin/nologin voidcore

WORKDIR /app

# Layered copy for fast iteration
COPY --from=build --chown=voidcore:voidcore /workspace/extracted/dependencies/         ./
COPY --from=build --chown=voidcore:voidcore /workspace/extracted/spring-boot-loader/   ./
COPY --from=build --chown=voidcore:voidcore /workspace/extracted/snapshot-dependencies/ ./
COPY --from=build --chown=voidcore:voidcore /workspace/extracted/application/           ./
COPY --from=build --chown=voidcore:voidcore /workspace/dev-instance                      ./dev-instance

USER voidcore

EXPOSE 8080

ENV JAVA_OPTS=""
ENTRYPOINT ["sh","-c","exec java $JAVA_OPTS org.springframework.boot.loader.launch.JarLauncher"]
