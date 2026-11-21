# syntax=docker/dockerfile:1

# ---- Stage 1: build the Angular frontend ----
# Its typed API client is generated from openapi.json, so the spec is copied in at the path the
# generator expects (../src/main/resources/static/openapi.json, relative to web/).
FROM node:26-alpine AS web
WORKDIR /workspace/web
COPY web/package.json web/package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY web/ ./
COPY src/main/resources/static/openapi.json ../src/main/resources/static/openapi.json
RUN npm run build

# ---- Stage 2: build the layered boot jar inside Docker ----
# Self-contained: jOOQ codegen parses the Flyway migration SQL directly
# (DDLDatabase, no live database), so the build needs no database — the jOOQ
# sources are generated under build/ during compile and are never committed.
# Temurin 25: no Java 26 JDK Alpine on Docker Hub yet.
FROM eclipse-temurin:25-jdk-alpine AS build
WORKDIR /workspace

# Wrapper + build scripts first so dependency resolution caches independently
# of source changes.
COPY gradlew settings.gradle build.gradle ./
COPY gradle ./gradle
RUN chmod +x ./gradlew

# Build, then split the fat jar into layers (Spring Boot 4 'tools' jarmode). The Angular build goes
# into static/ next to openapi.json, so Spring Boot serves the app (SpaFallbackFilter handles its
# client-side routes).
COPY src ./src
COPY --from=web /workspace/web/dist/resume-scope-web/browser/ ./src/main/resources/static/
RUN ./gradlew --no-daemon clean bootJar \
 && java -Djarmode=tools -jar build/libs/resume-scope.jar extract --layers --destination build/extracted

# ---- Stage 3: minimal runtime ----
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
