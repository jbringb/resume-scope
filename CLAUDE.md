# CLAUDE.md — ResumeScope

Guidance for AI coding agents in this repository. When you change something this file describes (layout, a convention, a rule, a workflow), update it in the same change.

## What this is

ResumeScope is an AI-powered CV analysis API with an Angular frontend in `web/`. An admin creates a **job role**, uploads candidate **PDFs** and triggers an **analysis run**. A background LLM call scores each CV 0–100 against the role, extracts name and email, lists strengths and weaknesses, and the candidates are **ranked** by score.

## Tech stack

- **Java 25** (Gradle toolchain), Gradle wrapper. No preview features.
- **Spring Boot 4 + WebFlux**, reactive end to end.
- **Spring AI 2**, OpenAI `gpt-4o-mini` at temperature `0.0`. The `local-vllm` profile swaps in any OpenAI-compatible server.
- **PostgreSQL**: queries via **jOOQ over R2DBC** (repos return `Mono`/`Flux`); migrations via **Flyway over JDBC** at startup (Flyway has no R2DBC support).
- **Jackson 3**, **Testcontainers 2**, JUnit 5 + Mockito + AssertJ, PDFBox 3, Lombok, Micrometer + Prometheus, Spotless + Palantir Java Format (120 columns).
- **Frontend (`web/`):** Angular 22 (zoneless, signals, Signal Forms) + Angular Material, a typed API client generated from `openapi.json`, Vitest, ESLint and Prettier. Rules are in [web/CLAUDE.md](web/CLAUDE.md).
- Exact versions live in [build.gradle](build.gradle) and Spring Boot's BOM, and in [web/package.json](web/package.json) for the frontend. Don't copy version numbers into this file; they go stale.

## Layout

Root package `dev.jbringb.resume_scope`:

- `api/`: REST controllers (`JobRole`, `JobRoleCandidate`, `JobRoleAnalysis`, `AnalysisRun` incl. SSE, `Usage`, `Health`). `api/dto/` holds request/response records.
- `service/`:
  - `AnalysisService`: trigger, idempotency, budget, run lookups.
  - `CvAnalyzerService`: the prompt, the LLM call, scoring, ranking, metrics.
  - `JobRoleService`, `CandidateService` (PDF upload).
  - `AnalysisEventBus`: SSE fan-out.
  - The Postgres advisory locks and transactions sit behind small interfaces (`AnalysisTriggerIdempotencyLock`, `ApiKeyBudgetLock`, `TransactionRunner`, each with a `Postgres*` implementation), so services stay unit-testable.
- `repository/`: jOOQ queries, reactive (`Mono.from(...)`/`Flux.from(...)` over jOOQ's R2DBC publishers).
- `config/`:
  - `ChatClientConfiguration`.
  - `PersistenceConfiguration`: the jOOQ `DSLContext` built on the R2DBC `ConnectionFactory`, which makes Boot's JDBC jOOQ auto-config back off.
  - `ApiKeyAuthFilter`, `ApiKeySeeder`, `ApiKeyHashing`.
  - `GlobalExceptionHandler`: errors as `ProblemDetail`.
  - `SpaFallbackFilter`: serves `index.html` for the Angular app's client-side routes; never for `/api`, `/actuator`, `/health` or file paths.
- `pdf/PdfTextExtractor`: PDFBox to text.
- `db/generated/`: jOOQ code generated into `build/generated-jooq` on every compile from the migration SQL (no database needed). Not committed; never edit it.
- Migrations: `src/main/resources/db/migration/V<n>__<desc>.sql`. API contract: `src/main/resources/static/openapi.json` (served at `/openapi.json`).
- `web/`: the Angular frontend. The Docker image builds it into `static/`, so the API serves it at `/`.
- `smoke_test/`: end-to-end smoke test and scoring eval (Python). `deploy/`: AWS and Render deployment plus Grafana dashboards.

## How an analysis runs

1. `POST /api/job-roles/{id}/analyze` → `AnalysisService.triggerAnalysis`. An optional `Idempotency-Key` header replays the active run for the same role and key, serialized by an advisory lock.
2. Under a per-API-key advisory lock, the run is rejected with **429** if one is already active for the key or the month's spend has reached the budget. Otherwise a `PENDING` run is inserted (it auto-commits, so no `afterCommit` hook is needed) and **202 + runId** is returned.
3. `CvAnalyzerService.processAnalysisRun` runs fire-and-forget on `boundedElastic`:
   - marks the run `RUNNING`;
   - makes one LLM call per candidate, then clamps the score to 0–100 and inserts the result;
   - ranks the candidates 1..N by score;
   - marks the run `COMPLETED` with token counts and an estimated EUR cost (or `FAILED`);
   - publishes to `AnalysisEventBus`.
4. Clients poll `GET /api/analysis-runs/{runId}` or stream `GET /api/analysis-runs/{runId}/events` (SSE, completes on a terminal status).
5. A `PENDING`/`RUNNING` run older than `analysis.run-timeout-minutes` (default 10) is marked `FAILED` on its next access, and an expired idempotency key starts a fresh run.

## LLM prompt and injection defenses (`CvAnalyzerService`) — don't weaken

- The CV is **untrusted data**: the prompt says never to obey it, and it sits inside a random per-call fence it can't predict.
- The prompt states the **0–100 scale** explicitly. The generated schema only says "integer", and the model once drifted to 0–10. A unit test pins this.
- `looksLikePromptInjection` (a phrase list) logs a warning and appends `INJECTION_WEAKNESS` to the stored weaknesses. It never changes the score. The model resists injected instructions but rarely mentions them on its own.
- The reply is parsed by `BeanOutputConverter` into the `CvAnalysisResult` record (`@JsonIgnoreProperties(ignoreUnknown = true)`).
- Changing the prompt, the schema, the model or Spring AI means running the scoring eval (golden rule 8).

## API keys and budget

- `ApiKeyAuthFilter` checks `X-API-Key` on `/api/**` against the `api_key` table by SHA-256 hash (never plaintext), and stores the key id on the exchange (`ApiKeyAuthFilter.resolvedApiKeyId`). `/health` is always open; it's the platform health check.
- Auth is on **iff** `security.api-key` / `API_KEY` is set. This is decided from config at startup, so there's no fail-open window while seeding. Unset means open (local/dev).
- `ApiKeySeeder` upserts `API_KEY` as the row `"default"` on every boot (`ON CONFLICT DO NOTHING`). Further keys currently need a manual `INSERT`; there's no admin endpoint.
- Budget: the key's `monthly_budget_eur`, else `analysis.cost.monthly-budget-eur`. Usage is summed per `analysis_run.api_key_id` (`IS NULL` when auth is off).

## Conventions

- **Naming:** services end in `Service`, and injected fields/locals in **`Svc`** (`JobRoleService jobRoleSvc`, never `jobRoleService`). Repositories end in `Repository`, and fields in **`Repo`** (`jobRoleRepo`).
- **Records** for DTOs and LLM output; jOOQ `*Record` types for persistence.
- **Validation:** request records use Jakarta constraints (`@NotBlank`, `@Size`, as in `JobRoleRequest`).
- **Errors:** throw `ResponseStatusException`. `GlobalExceptionHandler` renders every error as RFC 9457 `ProblemDetail`; don't build error bodies by hand.
- **Reactive:** never block in a reactive chain. Blocking work (the LLM call, PDF parsing) goes in `Mono.fromCallable(...).subscribeOn(Schedulers.boundedElastic())`. Never wrap DB calls that way; they're already reactive.
- **Jackson 3:** `ObjectMapper` etc. come from `tools.jackson.*`, but annotations are still `com.fasterxml.jackson.annotation.*`.
- **Testcontainers 2:** `org.testcontainers.postgresql.PostgreSQLContainer` (not generic), artifacts `testcontainers-postgresql` / `testcontainers-junit-jupiter`.
- **Lombok:** `@RequiredArgsConstructor` for injection and `@Slf4j` for logging.
- **`var`** only when the type is obvious from the right-hand side.
- **Comments** explain only the non-obvious: the why, invariants, special cases.
- **Modern Java** (records, sealed types, pattern matching, text blocks) where it genuinely simplifies.

## Golden rules

1. **Schema change → new Flyway migration, then `./gradlew generateJooq`** (reads the SQL, no database needed). See the `add-db-migration` skill.
2. **REST change → update `openapi.json` in the same change.** See the `add-rest-endpoint` skill. The frontend's typed client is generated from it, so also run the `web/` build (see below) to catch a breaking change.
3. **Format before finishing:** `./gradlew spotlessApply`. CI runs `spotlessCheck`.
4. **Never edit `db/generated/**`.** It's regenerated on every build, and edits are silently lost.
5. **Never point jOOQ codegen's `target.directory` at hand-written sources.** Its cleanup once deleted every non-generated file under `src/main/java`.
6. **No secrets in code, commits or output.** `OPENAI_API_KEY`, `API_KEY` and DB credentials come from the environment. Never print `smoke_test/.env`.
7. **Dependency upgrades and CVE checks → the `upgrade-deps` skill.** jOOQ, the Flyway plugin and Spring AI are pinned in lockstep with Spring Boot; never bump them to "latest". Dependabot version updates are off; a monthly scheduled run uses the skill and opens a PR.
8. **Prompt, output-schema, model or Spring AI change → `python smoke_test/eval.py --repeat 3`** (the `scoring-eval` skill). Unit tests mock the LLM, so only the eval and the smoke test catch a scoring regression.

## Verifying a change

- **Small change:** `./gradlew spotlessApply`, then the affected test classes: `./gradlew test --tests 'dev.jbringb.resume_scope.service.CvAnalyzerServiceTest'`.
- **Broad or shared change:** `./gradlew check` (Spotless plus all tests). `AnalysisFlowIntegrationTest`, `ApiKeyFlowIntegrationTest` and `AnalysisRepositoryTest` use Testcontainers and **need Docker**. Without Docker, skip them and say so; CI runs everything on every push and PR to `main`.
- **End to end** (real Docker image plus live OpenAI, about €0.003, a few minutes): `API_KEY="smoke-$(date +%s)" python smoke_test/smoke.py`.
  - Needs `smoke_test/.env` with `OPENAI_API_KEY` and `pip install reportlab`.
  - It also fails when scores break `smoke_test/scoring.py`.
  - Also available as the manual **Smoke Test** GitHub workflow.
- **Frontend (`web/`) or `openapi.json` change:** in `web/`, run `npm run format && npm run lint && npm test -- --watch=false && npm run build`. CI's **Frontend** job runs the same steps.
- **Dockerfile or `SpaFallbackFilter` change:** run the smoke test (it builds the image), then load `/` and a deep link such as `/job-roles` in the browser.

## Skills (`.claude/skills/`)

- `add-db-migration`: schema changes.
- `add-rest-endpoint`: endpoint plus DTOs, service, repository and OpenAPI.
- `upgrade-deps`: version and CVE checks with the lockstep rules.
- `scoring-eval`: checks the LLM's real scoring before shipping prompt or model changes.

## Commits

[Conventional Commits](https://www.conventionalcommits.org/): `feat:`, `fix:`, `refactor:`, `chore:` (tooling, CI, build, deploy). Commits are **one-line subjects only**: no body and no attribution trailers (`Co-Authored-By`, "Generated with …"), in commits and PR bodies alike. One logical change per commit.

## Build and run

```bash
docker compose up -d postgres                 # Postgres (resumescope/resumescope on :5432)
./gradlew flywayMigrate                       # apply migrations to it (only to run the app locally)
OPENAI_API_KEY=sk-... ./gradlew bootRun       # app on :8086; health: GET /health
./gradlew test                                # all tests (three classes need Docker)
./gradlew bootJar                             # build/libs/resume-scope.jar
docker compose up --build                     # full stack incl. the frontend at :8086; the image compiles from source
cd web && npm ci && npm start                 # frontend dev server on :4200, proxies /api etc. to :8086
```

- Local LLM: start vLLM on `:8000`, then `SPRING_PROFILES_ACTIVE=local-vllm ./gradlew bootRun`.
- The [`Dockerfile`](Dockerfile) is multi-stage and self-building: Angular build (Node) → boot jar (JDK, with the Angular output copied into `static/`) → JRE runtime. No host build and no database are needed during the build. Every deployment target (Render, AWS) builds it; see the README's Deployments section.
- `./gradlew bootRun` alone serves no frontend, since `static/` only gets it inside Docker. Use `npm start` in `web/` instead.

## Config and environment

- `application.yaml` holds the defaults; `application-local-vllm.yaml` is the `local-vllm` profile.
- **DB (app):** `PGHOST`/`PGPORT`/`PGDATABASE`/`PGUSER`/`PGPASSWORD` feed both R2DBC (`spring.r2dbc.*`) and Flyway (`spring.flyway.url`). Override with `SPRING_R2DBC_URL` / `SPRING_DATASOURCE_URL` (Flyway's JDBC URL).
- **DB (Gradle `flyway*` tasks):** `DB_URL`/`DB_USER`/`DB_PASSWORD` (or `-Pdb.url=` etc.), default `jdbc:postgresql://localhost:5432/resumescope`.
- **AI:** `OPENAI_API_KEY` (required at runtime), optional `OPENAI_BASE_URL`, `OPENAI_MODEL`. The LLM call times out after `spring.http.client.read-timeout` (60s).
- **Auth:** `API_KEY`; see API keys above. If the auth contract changes, update `openapi.json`'s `ApiKeyAuth` scheme.
- **Analysis:** `ANALYSIS_RUN_TIMEOUT_MINUTES` (10), `ANALYSIS_COST_MONTHLY_BUDGET_EUR` (5.00).
- **Server:** `PORT` (8086). Only `/actuator/prometheus` is exposed; the custom metrics `resumescope.analysis.*` are defined in `CvAnalyzerService`, and the dashboards are in `deploy/grafana/`.
