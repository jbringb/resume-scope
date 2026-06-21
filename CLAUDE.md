# CLAUDE.md — ResumeScope

Guidance for Claude Code (and other AI assistants) working in this repository. Keep it accurate when practices change.

## What this is

ResumeScope is an AI-powered CV/resume analysis API. An admin creates a **job role**, uploads candidate **PDFs**, and triggers an **analysis run**. A background LLM call scores each CV (0–100) against the role, extracts name/email, summarizes strengths/weaknesses, and the candidates are **ranked** by descending score.

## Tech stack

- **Build:** Gradle, **Java 25** toolchain
- **Framework:** Spring Boot **4.0.4**, Spring **WebFlux** — reactive **end-to-end** (jOOQ over R2DBC; no `Mono.fromCallable` wrapping of blocking DB calls)
- **AI:** Spring AI **2.0.0-M3**, OpenAI starter — model `gpt-4o-mini`, temperature `0.0`. Swappable to a local OpenAI-compatible server (vLLM) via the `local-vllm` profile.
- **Persistence:** PostgreSQL + **jOOQ 3.19** (type-safe SQL) executed **reactively over R2DBC** at runtime (`r2dbc-postgresql`; repos return `Mono`/`Flux`). **Flyway 11** runs migrations over a **JDBC** connection at startup (`spring.flyway.url`; Flyway has no R2DBC support). The jOOQ `DSLContext` is built from the R2DBC `ConnectionFactory` in `config/PersistenceConfiguration` (this makes Boot's JDBC jOOQ auto-config back off). jOOQ **codegen** still uses JDBC and is committed — unchanged.
- **PDF:** Apache PDFBox 3.0.7
- **Boilerplate:** Lombok
- **Formatting:** Spotless 8.4 with Palantir Java Format (120-col)

## Architecture

Root package `dev.jbringb.resume_scope`:

- `api/` — REST controllers (`JobRoleController`, `JobRoleCandidateController`, `JobRoleAnalysisController`, `AnalysisRunController`)
  - `api/dto/` — request/response **records**
- `service/` — business logic (`JobRoleService`, `CandidateService`, `AnalysisService`, `CvAnalyzerService`, `AnalysisEventBus`, idempotency lock)
- `repository/` — jOOQ data access (`*Repository`), reactive: methods return `Mono`/`Flux` (`Mono.from(...)`/`Flux.from(...)` over jOOQ R2DBC publishers)
- `config/` — `ChatClientConfiguration` (Spring AI `ChatClient` + `ObjectMapper`), `PersistenceConfiguration` (jOOQ `DSLContext` over the R2DBC `ConnectionFactory`), `ApiKeyAuthFilter`
- `pdf/` — `PdfTextExtractor` (PDFBox → text)
- `db/generated/` — **jOOQ-generated code, committed to source. Never hand-edit; regenerate instead.**

**Async analysis flow (reactive):** `AnalysisService.triggerAnalysis` returns `Mono<TriggerAnalysisResponse>`, is idempotent (optional `Idempotency-Key` header, Postgres advisory lock via jOOQ `transactionPublisher`), and returns **202 + runId** → `CvAnalyzerService.processAnalysisRun(runId)` (a `Mono<Void>` subscribed fire-and-forget on `boundedElastic`) calls the LLM per candidate, clamps scores to 0–100, inserts results, **ranks 1..N by score desc**, sets run status `COMPLETED`/`FAILED`, and publishes to `AnalysisEventBus`. The PENDING insert auto-commits before processing is dispatched, so there is **no** `afterCommit` synchronization (and no visibility race). Clients poll `GET /api/analysis-runs/{runId}` **or** subscribe to the SSE stream `GET /api/analysis-runs/{runId}/events` (`AnalysisRunController` filters `AnalysisEventBus.stream()` by run id, completing on terminal status). Runs have a **timeout** (`analysis.run-timeout-minutes`, default 10): a `PENDING`/`RUNNING` run older than that is lazily marked `FAILED` on next access, and an expired idempotency key starts a fresh run. The LLM call (blocking) is offloaded to `boundedElastic` and inherits `spring.http.client.read-timeout`.

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

- App port **8086**; health check: `GET /health` (open). `GET /api/job-roles` is a sanity check but requires `X-API-Key` when `API_KEY` is set.
- Local LLM (no OpenAI key): start a vLLM server on `:8000`, then `SPRING_PROFILES_ACTIVE=local-vllm ./gradlew bootRun`.
- **jOOQ code is committed**, and `generateSchemaSourceOnCompilation = false` — so plain `./gradlew test`/`build` does **not** need a database.
- The [`Dockerfile`](Dockerfile) is a **self-building multi-stage** image: it compiles the boot jar from source inside Docker (no host `bootJar` needed) and extracts Spring Boot layers via the `tools` jarmode. This is what all deployment targets build.

## Config & environment

- `application.yaml` (defaults) + `application-local-vllm.yaml` (`local-vllm` profile).
- **App runtime DB env:** `PGHOST`/`PGPORT`/`PGDATABASE`/`PGUSER`/`PGPASSWORD` feed **both** R2DBC (runtime queries, `spring.r2dbc.*`) and Flyway (migrations, `spring.flyway.url`). Override explicitly with `SPRING_R2DBC_URL` and/or `SPRING_DATASOURCE_URL` (the latter is Flyway's JDBC URL). Same Postgres for both.
- **Gradle Flyway/jOOQ tasks** use separate vars: `DB_URL`/`DB_USER`/`DB_PASSWORD` (or `-Pdb.url=` etc.), default `jdbc:postgresql://localhost:5432/resumescope`.
- **AI:** `OPENAI_API_KEY` (required at runtime), optional `OPENAI_BASE_URL`, `OPENAI_MODEL`.
- **Auth:** `API_KEY` gates `/api/**` via the `X-API-Key` header (`ApiKeyAuthFilter`, a reactive `WebFilter`). Empty = disabled (local/dev). `/health` is always open and is the platform health-check path. Update `openapi.json` (`ApiKeyAuth` scheme) if the auth contract changes.
- **Server:** `PORT` (default 8086).

## Key files

- [build.gradle](build.gradle) — toolchain, deps, jOOQ + Flyway + Spotless config
- [application.yaml](src/main/resources/application.yaml) — runtime config
- [openapi.json](src/main/resources/static/openapi.json) — API contract (keep in sync)
- `src/main/resources/db/migration/` — Flyway migrations V1–V5
- `src/main/java/dev/jbringb/resume_scope/service/` — core logic
