# CLAUDE.md — ResumeScope

Guidance for Claude Code (and other AI assistants) working in this repository. Keep it accurate when practices change.

## What this is

ResumeScope is an AI-powered CV/resume analysis API. An admin creates a **job role**, uploads candidate **PDFs**, and triggers an **analysis run**. A background LLM call scores each CV (0–100) against the role, extracts name/email, summarizes strengths/weaknesses, and the candidates are **ranked** by descending score.

## Tech stack

- **Build:** Gradle, **Java 25** toolchain
- **Framework:** Spring Boot **4.0.4**, Spring **WebFlux** (reactive)
- **AI:** Spring AI **2.0.0-M3**, OpenAI starter — model `gpt-4o-mini`, temperature `0.0`. Swappable to a local OpenAI-compatible server (vLLM) via the `local-vllm` profile.
- **Persistence:** PostgreSQL + **jOOQ 3.19** (type-safe SQL) + **Flyway 11** (migrations)
- **PDF:** Apache PDFBox 3.0.7
- **Boilerplate:** Lombok
- **Formatting:** Spotless 8.4 with Palantir Java Format (120-col)

## Architecture

Root package `dev.jbringb.resume_scope`:

- `api/` — REST controllers (`JobRoleController`, `JobRoleCandidateController`, `JobRoleAnalysisController`, `AnalysisRunController`)
  - `api/dto/` — request/response **records**
- `service/` — business logic (`JobRoleService`, `CandidateService`, `AnalysisService`, `CvAnalyzerService`, `AnalysisEventBus`, idempotency lock)
- `repository/` — jOOQ data access (`*Repository`)
- `config/` — `ChatClientConfiguration` (Spring AI `ChatClient` + `ObjectMapper`)
- `pdf/` — `PdfTextExtractor` (PDFBox → text)
- `db/generated/` — **jOOQ-generated code, committed to source. Never hand-edit; regenerate instead.**

**Async analysis flow:** `AnalysisService.triggerAnalysis` is idempotent (optional `Idempotency-Key` header, Postgres-backed lock) and returns **202 + runId** → `@Async CvAnalyzerService.processAnalysisRunAsync(runId)` calls the LLM per candidate, clamps scores to 0–100, inserts results, **ranks 1..N by score desc**, sets run status `COMPLETED`/`FAILED`, and publishes to `AnalysisEventBus`. Admin polls `GET /api/analysis-runs/{runId}`.

## Conventions (observed in code — follow them)

- **Services:** type suffix `Service`; injected fields/locals end in **`Svc`** (e.g. `JobRoleService jobRoleSvc`). Never `jobRoleService`.
- **Repositories:** type suffix `Repository`; injected fields/locals end in **`Repo`** (e.g. `jobRoleRepo`).
- **DTOs are records** (immutable). Persistence uses jOOQ `*Record` types. LLM JSON is parsed into a `CvAnalysisResult` record annotated `@JsonIgnoreProperties(ignoreUnknown = true)`.
- **Validation:** request records use Jakarta constraints (e.g. `JobRoleRequest` uses `@NotBlank`, `@Size`). Match this on new request bodies.
- **Lombok:** constructor injection via `@RequiredArgsConstructor`; `@Slf4j` for logging. Don't hand-write boilerplate Lombok covers.
- **`var`:** use only when the type is obvious from the right-hand side (constructor/factory/clearly-named method). Avoid for bare numerics or surprising generics.
- **Comments:** explain only the non-obvious (why, invariants, special cases). Prefer clear names and small methods.
- **Modern Java:** prefer records, sealed types, pattern matching, text blocks where they genuinely simplify. No preview features (`--enable-preview`).

## Golden rules for agents

1. **Schema change → regenerate jOOQ.** After editing/adding a Flyway migration, run `./gradlew flywayMigrate generateJooq` (needs a running Postgres). See the `add-db-migration` skill.
2. **REST change → update the OpenAPI spec** in the same change. Source of truth: `src/main/resources/static/openapi.json` (served at `/openapi.json`). See the `add-rest-endpoint` skill.
3. **Format before finishing:** `./gradlew spotlessApply`. CI runs `spotlessCheck`.
4. **Never edit `db/generated/**`** — it's jOOQ output (Spotless excludes it too).
5. **No secrets in code or commits.** `OPENAI_API_KEY` and DB credentials come from the environment.

## Commit conventions

Use [Conventional Commits](https://www.conventionalcommits.org/) — `type: short imperative summary`:

- `feat:` — new functionality
- `fix:` — bug fix
- `refactor:` — restructuring with no behavior change
- `chore:` — tooling, CI, build, or deployment changes (no app behavior change)

Keep each commit focused on one logical change.

## Build & run

```bash
docker compose up -d postgres                 # start Postgres (resumescope/resumescope on :5432)
./gradlew flywayMigrate generateJooq          # apply migrations + regenerate jOOQ (only after schema changes)
OPENAI_API_KEY=sk-... ./gradlew bootRun        # run the app on :8086
./gradlew spotlessApply                        # format
./gradlew test                                 # unit tests (JUnit 5 + Mockito)
./gradlew check                                # spotlessCheck + tests
./gradlew bootJar                              # build build/libs/resume-scope.jar
docker compose up --build                      # full stack (Postgres + app) — image builds itself
```

- App port **8086**; health/sanity check: `GET /api/job-roles`.
- Local LLM (no OpenAI key): start a vLLM server on `:8000`, then `SPRING_PROFILES_ACTIVE=local-vllm ./gradlew bootRun`.
- **jOOQ code is committed**, and `generateSchemaSourceOnCompilation = false` — so plain `./gradlew test`/`build` does **not** need a database.
- The [`Dockerfile`](Dockerfile) is a **self-building multi-stage** image: it compiles the boot jar from source inside Docker (no host `bootJar` needed) and extracts Spring Boot layers via the `tools` jarmode. This is what all deployment targets build.

## Config & environment

- `application.yaml` (defaults) + `application-local-vllm.yaml` (`local-vllm` profile).
- **App runtime DB env:** `SPRING_DATASOURCE_URL` or `PGHOST`/`PGPORT`/`PGDATABASE`/`PGUSER`/`PGPASSWORD`.
- **Gradle Flyway/jOOQ tasks** use separate vars: `DB_URL`/`DB_USER`/`DB_PASSWORD` (or `-Pdb.url=` etc.), default `jdbc:postgresql://localhost:5432/resumescope`.
- **AI:** `OPENAI_API_KEY` (required at runtime), optional `OPENAI_BASE_URL`, `OPENAI_MODEL`.
- **Server:** `PORT` (default 8086).

## Key files

- [build.gradle](build.gradle) — toolchain, deps, jOOQ + Flyway + Spotless config
- [application.yaml](src/main/resources/application.yaml) — runtime config
- [openapi.json](src/main/resources/static/openapi.json) — API contract (keep in sync)
- `src/main/resources/db/migration/` — Flyway migrations V1–V5
- `src/main/java/dev/jbringb/resume_scope/service/` — core logic
