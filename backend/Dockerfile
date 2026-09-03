# --- Build stage ---
FROM gradle:9.7-jdk21 AS build

WORKDIR /build

# Leverage Docker layer caching: warm the dependency/plugin metadata cache before copying
# source, so source-only changes don't force a full re-resolve.
COPY build.gradle.kts settings.gradle.kts ./
RUN gradle --no-daemon dependencies

COPY src ./src
RUN gradle --no-daemon bootJar \
    && mv build/libs/*.jar build/app.jar

# --- Runtime stage ---
FROM amazoncorretto:21-headless

# curl is required by docker-compose's healthcheck (GET /actuator/health), not by the app.
# Amazon Linux 2023 already ships curl-minimal (a working curl, just without the less-common
# protocols) - reinstalling it here is idempotent and safe, unlike installing the full `curl`
# package, which conflicts with the preinstalled curl-minimal and needs --allowerasing instead.
RUN dnf install -y curl-minimal \
    && dnf clean all

WORKDIR /app
COPY --from=build /build/build/app.jar app.jar

EXPOSE 8081

ENTRYPOINT ["java", "-jar", "app.jar"]
