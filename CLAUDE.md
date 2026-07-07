# CLAUDE.md — ResumeScope

Guidance for Claude Code (and other AI assistants) working in this repository. Keep it accurate when practices change.

## What this is

ResumeScope is an AI-powered CV/resume analysis API. An admin creates a **job role**, uploads candidate **PDFs**, and triggers an **analysis run**. A background LLM call scores each CV (0–100) against the role, extracts name/email, summarizes strengths/weaknesses, and the candidates are **ranked** by descending score.

## Tech stack

- **Build:** Gradle, **Java 25** toolchain
- **Framework:** Spring Boot **4.0.4**, Spring **WebFlux** — reactive **end-to-end** (jOOQ over R2DBC; no `Mono.fromCallable` wrapping of blocking DB calls)
- **AI:** Spring AI **2.0.0-M3**, OpenAI starter — model `gpt-4o-mini`, temperature `0.0`. Swappable to a local OpenAI-compatible server (vLLM) via the `local-vllm` profile.
- **Persistence:** PostgreSQL + **jOOQ 3.19** (type-safe SQL) executed **reactively over R2DBC** at runtime (`r2dbc-postgresql`; repos return `Mono`/`Flux`). **Flyway 11** runs migrations over a **JDBC** connection at startup (`spring.flyway.url`; Flyway has no R2DBC support). The jOOQ `DSLContext` is built from the R2DBC `ConnectionFactory` in `config/PersistenceConfiguration` (this makes Boot's JDBC jOOQ auto-config back off). jOOQ **codegen** parses the Flyway migration SQL directly (`DDLDatabase`, no live database) and outputs to `build/generated-jooq` — **not committed**, regenerated on every build.
- **PDF:** Apache PDFBox 3.0.7
- **Boilerplate:** Lombok
- **Formatting:** Spotless 8.4 with Palantir Java Format (120-col)

## Architecture

Root package `dev.jbringb.resume_scope`:

- `api/` — REST controllers (`JobRoleController`, `JobRoleCandidateController`, `JobRoleAnalysisController`, `AnalysisRunController`, `UsageController`)
  - `api/dto/` — request/response **records**
- `service/` — business logic (`JobRoleService`, `CandidateService`, `AnalysisService`, `CvAnalyzerService`, `AnalysisEventBus`, idempotency lock)
- `repository/` — jOOQ data access (`*Repository`), reactive: methods return `Mono`/`Flux` (`Mono.from(...)`/`Flux.from(...)` over jOOQ R2DBC publishers)
- `config/` — `ChatClientConfiguration` (Spring AI `ChatClient` + `ObjectMapper`), `PersistenceConfiguration` (jOOQ `DSLContext` over the R2DBC `ConnectionFactory`), `ApiKeyAuthFilter`, `ApiKeySeeder`, `ApiKeyHashing`
- `pdf/` — `PdfTextExtractor` (PDFBox → text)
- `db/generated/` — **jOOQ-generated code**, produced under `build/generated-jooq` at build time (package `dev.jbringb.resume_scope.db.generated`). Not committed; never hand-edit — regenerate instead.

**Async analysis flow (reactive):** `AnalysisService.triggerAnalysis` returns `Mono<TriggerAnalysisResponse>`, is idempotent (optional `Idempotency-Key` header, Postgres advisory lock via jOOQ `transactionPublisher`), and returns **202 + runId** → `CvAnalyzerService.processAnalysisRun(runId)` (a `Mono<Void>` subscribed fire-and-forget on `boundedElastic`) calls the LLM per candidate, clamps scores to 0–100, inserts results, **ranks 1..N by score desc**, sets run status `COMPLETED`/`FAILED`, records prompt/completion token counts and an estimated EUR cost, and publishes to `AnalysisEventBus`. The PENDING insert auto-commits before processing is dispatched, so there is **no** `afterCommit` synchronization (and no visibility race). Clients poll `GET /api/analysis-runs/{runId}` **or** subscribe to the SSE stream `GET /api/analysis-runs/{runId}/events` (`AnalysisRunController` filters `AnalysisEventBus.stream()` by run id, completing on terminal status). Runs have a **timeout** (`analysis.run-timeout-minutes`, default 10): a `PENDING`/`RUNNING` run older than that is lazily marked `FAILED` on next access, and an expired idempotency key starts a fresh run. The LLM call (blocking) is offloaded to `boundedElastic` and inherits `spring.http.client.read-timeout`.

**API keys & per-key cost budget:** `ApiKeyAuthFilter` resolves `X-API-Key` against the `api_key` table (SHA-256 hash lookup, never plaintext) and stashes the matched `api_key.id` on the exchange (`ApiKeyAuthFilter.resolvedApiKeyId`). `ApiKeySeeder` (an `ApplicationRunner`) seeds the legacy `security.api-key`/`API_KEY` env var as a row named `"default"` on every boot (idempotent upsert via `ON CONFLICT DO NOTHING`), so existing single-key deployments keep working unchanged. Auth is enabled iff the `security.api-key`/`API_KEY` env var is set — decided from config at startup, not from whether the `api_key` table has rows, so there is no fail-open window during seeding; when it is unset, auth is inert (the "open in local/dev" default). Each key can carry its own `monthly_budget_eur` override; `AnalysisService.checkBudget`/`monthlyUsage` resolve the effective budget (key override, else the global `analysis.cost.monthly-budget-eur` default) and scope usage sums to that key's `analysis_run.api_key_id` (or `IS NULL` when auth is disabled).

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

1. **Schema change → regenerate jOOQ.** After editing/adding a Flyway migration, run `./gradlew generateJooq` — it reads the migration SQL directly, so **no database is needed**. (`flywayMigrate` against a running Postgres is only needed to actually apply the schema for local running/testing.) See the `add-db-migration` skill.
2. **REST change → update the OpenAPI spec** in the same change. Source of truth: `src/main/resources/static/openapi.json` (served at `/openapi.json`). See the `add-rest-endpoint` skill.
3. **Format before finishing:** `./gradlew spotlessApply`. CI runs `spotlessCheck`.
4. **Never edit `db/generated/**`** — it's jOOQ output under `build/`, regenerated on every build; edits are silently discarded.
5. **No secrets in code or commits.** `OPENAI_API_KEY` and DB credentials come from the environment.
6. **Never point jOOQ's codegen `target.directory` at a directory containing hand-written sources.** jOOQ's directory-cleanup once deleted every non-generated file under `src/main/java` when it was configured that way — codegen output must stay under `build/`.

## Commit conventions

Use [Conventional Commits](https://www.conventionalcommits.org/) — `type: short imperative summary`:

- `feat:` — new functionality
- `fix:` — bug fix
- `refactor:` — restructuring with no behavior change
- `chore:` — tooling, CI, build, or deployment changes (no app behavior change)

Keep each commit focused on one logical change.

## Build & run

```bash
docker compose up -d postgres                 # start Postgres (resumescope/resumescope on :5432) — only needed to run/test the app
./gradlew flywayMigrate                        # apply migrations to the running Postgres (needed to run/test locally)
OPENAI_API_KEY=sk-... ./gradlew bootRun        # run the app on :8086
./gradlew spotlessApply                        # format
./gradlew test                                 # unit tests (JUnit 5 + Mockito)
./gradlew check                                # spotlessCheck + tests
./gradlew bootJar                              # build build/libs/resume-scope.jar
docker compose up --build                      # full stack (Postgres + app) — image builds itself
```

- App port **8086**; health check: `GET /health` (open). `GET /api/job-roles` is a sanity check but requires `X-API-Key` when `API_KEY` is set.
- Local LLM (no OpenAI key): start a vLLM server on `:8000`, then `SPRING_PROFILES_ACTIVE=local-vllm ./gradlew bootRun`.
- **jOOQ codegen needs no database** — it parses the migration SQL directly and runs automatically on compile (`generateSchemaSourceOnCompilation = true`), so plain `./gradlew test`/`build`/`bootJar` work fully offline.
- The [`Dockerfile`](Dockerfile) is a **self-building multi-stage** image: it compiles the boot jar from source inside Docker (no host `bootJar` needed, no database reachable during the image build) and extracts Spring Boot layers via the `tools` jarmode. This is what all deployment targets build.

## Config & environment

- `application.yaml` (defaults) + `application-local-vllm.yaml` (`local-vllm` profile).
- **App runtime DB env:** `PGHOST`/`PGPORT`/`PGDATABASE`/`PGUSER`/`PGPASSWORD` feed **both** R2DBC (runtime queries, `spring.r2dbc.*`) and Flyway (migrations, `spring.flyway.url`). Override explicitly with `SPRING_R2DBC_URL` and/or `SPRING_DATASOURCE_URL` (the latter is Flyway's JDBC URL). Same Postgres for both.
- **Gradle Flyway/jOOQ tasks** use separate vars: `DB_URL`/`DB_USER`/`DB_PASSWORD` (or `-Pdb.url=` etc.), default `jdbc:postgresql://localhost:5432/resumescope`.
- **AI:** `OPENAI_API_KEY` (required at runtime), optional `OPENAI_BASE_URL`, `OPENAI_MODEL`.
- **Auth:** `API_KEY` seeds a `"default"` row in the `api_key` table on boot (`ApiKeySeeder`); `ApiKeyAuthFilter` (a reactive `WebFilter`) then resolves `X-API-Key` against that table on every `/api/**` request. Empty/no keys configured = disabled (local/dev). `/health` is always open and is the platform health-check path. Additional keys (with their own name/budget) currently need a manual `INSERT` — no admin endpoint yet. Update `openapi.json` (`ApiKeyAuth` scheme) if the auth contract changes.
- **Server:** `PORT` (default 8086).

## Key files

- [build.gradle](build.gradle) — toolchain, deps, jOOQ + Flyway + Spotless config
- [application.yaml](src/main/resources/application.yaml) — runtime config
- [openapi.json](src/main/resources/static/openapi.json) — API contract (keep in sync)
- `src/main/resources/db/migration/` — Flyway migrations V1–V7
- `src/main/java/dev/jbringb/resume_scope/service/` — core logic
