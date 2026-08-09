# syntax=docker/dockerfile:1

# --- Build stage -------------------------------------------------------
FROM eclipse-temurin:21-jdk AS build
WORKDIR /build

# Copy the reactor pom so the "service" module resolves as if built from
# the repo root, then copy just this module's pom to leverage Docker layer
# caching for dependency resolution before the source changes.
COPY pom.xml /build/pom.xml
COPY service/pom.xml /build/service/pom.xml

# Bring in the Maven wrapper if present; otherwise this stage expects the
# `mvn` binary to be available on the image (eclipse-temurin:21-jdk does not
# ship Maven, so we install it directly here to keep the build self-contained).
RUN apt-get update \
    && apt-get install -y --no-install-recommends maven \
    && rm -rf /var/lib/apt/lists/*

# Warm the dependency cache (best-effort; module list may be incomplete at
# this point if other modules aren't copied yet, so failures here are fine).
RUN mvn -q -f service/pom.xml -DskipTests dependency:go-offline || true

COPY service/src /build/service/src
RUN mvn -q -f service/pom.xml -DskipTests package

# --- Run stage -----------------------------------------------------------
FROM eclipse-temurin:21-jre AS run
WORKDIR /app

RUN useradd --system --create-home --shell /usr/sbin/nologin appuser

COPY --from=build /build/service/target/service.jar /app/service.jar

# SQLite is file-based, so persistence lives entirely in this directory.
# Mount a volume here (e.g. `-v urlshortener-data:/app/data`) or data will
# be lost when the container is removed. Because SQLite only supports a
# single writer, this image is designed to run as a single instance only -
# do not scale this container horizontally against the same DB file.
RUN mkdir -p /app/data && chown -R appuser:appuser /app
VOLUME ["/app/data"]

ENV DB_PATH=/app/data/urlshortener.db
EXPOSE 8080

USER appuser

ENTRYPOINT ["java", "-jar", "/app/service.jar"]
