# syntax=docker/dockerfile:1

# ---- Stage 1: build the layered boot jar inside Docker ----
# Self-contained: jOOQ sources are committed and codegen does not run on
# compile (generateSchemaSourceOnCompilation = false), so the build needs
# no database. Temurin 25: no Java 26 JDK Alpine on Docker Hub yet.
FROM eclipse-temurin:25-jdk-alpine AS build
WORKDIR /workspace

# Wrapper + build scripts first so dependency resolution caches independently
# of source changes.
COPY gradlew settings.gradle build.gradle ./
COPY gradle ./gradle
RUN chmod +x ./gradlew

# Build, then split the fat jar into layers (Spring Boot 4 'tools' jarmode).
COPY src ./src
RUN ./gradlew --no-daemon clean bootJar \
 && java -Djarmode=tools -jar build/libs/resume-scope.jar extract --layers --destination build/extracted

# ---- Stage 2: minimal runtime ----
FROM eclipse-temurin:25-jre-alpine
WORKDIR /app

# Run as an unprivileged user.
RUN addgroup -S app && adduser -S -G app app
USER app

# Most-stable layers first for better image-layer caching; application last.
COPY --from=build --chown=app:app /workspace/build/extracted/dependencies/ ./
COPY --from=build --chown=app:app /workspace/build/extracted/spring-boot-loader/ ./
COPY --from=build --chown=app:app /workspace/build/extracted/snapshot-dependencies/ ./
COPY --from=build --chown=app:app /workspace/build/extracted/application/ ./

EXPOSE 8086
ENTRYPOINT ["java", "-jar", "resume-scope.jar"]
