# ResumeScope

[![CI](https://github.com/jbringb/resume-scope/actions/workflows/ci.yml/badge.svg)](https://github.com/jbringb/resume-scope/actions/workflows/ci.yml)
[![CodeQL](https://github.com/jbringb/resume-scope/actions/workflows/codeql.yml/badge.svg)](https://github.com/jbringb/resume-scope/actions/workflows/codeql.yml)
![Java 25](https://img.shields.io/badge/Java-25-orange)
![Spring Boot 4](https://img.shields.io/badge/Spring%20Boot-4.0-brightgreen)

AI-powered CV analysis platform built with **Spring Boot 4**, **Spring AI**, **jOOQ**, and **PostgreSQL**.

Administrators upload PDF CVs connected to job roles. The AI analyses each CV, ranks candidates, and highlights strengths and weaknesses against the role requirements.

The analysis runs **asynchronously**: triggering an analysis returns `202 Accepted` with a run ID, a background worker scores every candidate against the role via the LLM, candidates are ranked by descending score, and the client polls the run — or subscribes to a live **Server-Sent Events** stream — for status and results.

The stack is **reactive end-to-end**: Spring WebFlux on top of jOOQ executed over **R2DBC** (non-blocking SQL), with Flyway running migrations over a JDBC connection at startup. Genuinely blocking work (the LLM call, PDF parsing) is offloaded to a bounded scheduler.

---

## Tech Stack

| Layer        | Technology                                |
|--------------|-------------------------------------------|
| Backend      | Spring Boot 4.0.4 · WebFlux (reactive) · Java 25 |
| AI           | Spring AI 2.0 · OpenAI-compatible (hosted or local vLLM) |
| Database     | PostgreSQL 17 · jOOQ 3.19 over **R2DBC** (non-blocking) · Flyway 11 (JDBC) |
| PDF parsing  | Apache PDFBox 3.0.7                       |
| Frontend     | Angular (separate — not yet built)        |

---

## Deployments

The [Dockerfile](Dockerfile) is **self-building** (it compiles the boot jar from source inside the image — no local build step). Deploys are triggered manually via GitHub Actions (`workflow_dispatch`). See:

- [Render.com](deploy/render/README.md) — Blueprint [`render.yaml`](render.yaml) (Docker web service + free Postgres), deployed via [`deploy-render.yml`](.github/workflows/deploy-render.yml)
- [AWS — ECS Express Mode](deploy/aws/README.md) — ECR image push + Fargate service + auto ALB/HTTPS, deployed via [`deploy-aws.yml`](.github/workflows/deploy-aws.yml)

---

## Prerequisites

- Docker Engine + CLI (with `docker compose`)
- Java 25+ (`JAVA_HOME` pointing to JDK 25; Docker image uses `eclipse-temurin:25-jre-alpine`)
- Either an **OpenAI API key** (`OPENAI_API_KEY`) for hosted inference, **or** a local [vLLM](https://docs.vllm.ai/) server with an OpenAI-compatible API (see [Local inference (vLLM)](#local-inference-vllm)).

---

## First-time Setup

### 1. Start the database

Copy [`.env.example`](.env.example) to `.env` if you have not already (needed when you run the full stack with Docker; see [Docker Compose](#docker-compose-and-spring_profiles_active)).

```bash
docker compose up -d postgres
```

### 2. Apply Flyway migrations

```bash
./gradlew flywayMigrate
```

This runs the migration scripts in `src/main/resources/db/migration/` (`V1`–`V7`) against the local database — needed to **run or test** the app locally.

### 3. jOOQ classes (generated automatically)

jOOQ reads the Flyway migration SQL directly (no live database involved) and generates type-safe classes into `build/generated-jooq/` on every compile (`generateSchemaSourceOnCompilation = true`). Nothing to run by hand, and **nothing is committed** — a plain `./gradlew build` / `test` works fully offline, with no database needed for compilation. Never hand-edit the generated sources; edit a migration and rebuild instead.

### 4. Run the application

```bash
OPENAI_API_KEY=sk-... ./gradlew bootRun
```

The API is available at `http://localhost:8086`.

---

## API Reference

### Job Roles

| Method | Path                           | Description           |
|--------|--------------------------------|-----------------------|
| GET    | `/api/job-roles`               | List all job roles    |
| POST   | `/api/job-roles`               | Create a job role     |
| GET    | `/api/job-roles/{id}`          | Get a job role        |
| PUT    | `/api/job-roles/{id}`          | Update a job role     |
| DELETE | `/api/job-roles/{id}`          | Delete a job role     |

**Create job role body:**
```json
{
  "title": "Senior Backend Engineer",
  "description": "We are looking for...",
  "requirements": "5+ years Java, Spring Boot, PostgreSQL..."
}
```

### Candidates (CV Upload)

| Method | Path                                          | Description                       |
|--------|-----------------------------------------------|-----------------------------------|
| GET    | `/api/job-roles/{id}/candidates`              | List candidates for a role        |
| POST   | `/api/job-roles/{id}/candidates`              | Upload PDF CVs (multipart/form-data, field: `files`) |
| DELETE | `/api/job-roles/{id}/candidates/{cId}`        | Remove a candidate                |

**Upload example (curl):**
```bash
curl -X POST http://localhost:8086/api/job-roles/{id}/candidates \
  -F "files=@alice.pdf" \
  -F "files=@bob.pdf"
```

### Analysis

| Method | Path                                        | Description                                           |
|--------|---------------------------------------------|-------------------------------------------------------|
| POST   | `/api/job-roles/{id}/analyze`               | Trigger AI analysis (returns 202 + run ID)            |
| GET    | `/api/job-roles/{id}/analysis-runs`         | List all analysis runs for a role                     |
| GET    | `/api/job-roles/{id}/results`               | Get ranked results from latest completed run          |
| GET    | `/api/analysis-runs/{runId}`                | Poll status of a specific run                         |
| GET    | `/api/analysis-runs/{runId}/results`        | Get all results for a specific run                    |
| GET    | `/api/analysis-runs/{runId}/events`         | **SSE** live run-status stream (closes on terminal status) |
| GET    | `/api/usage/monthly`                        | Aggregated LLM token usage and estimated EUR cost for the current calendar month |

**Cost tracking:** every completed run records the prompt/completion token counts and an estimated EUR cost (from Spring AI's usage metadata) on the `AnalysisRunResponse`. Spend is tracked **per API key** (see [API authentication](#api-authentication)): new analysis runs are rejected with `429` once that key's monthly budget is reached — check current spend via `GET /api/usage/monthly`.

**Live updates (SSE):** instead of polling `GET /api/analysis-runs/{runId}`, subscribe to the event stream — it pushes the current state immediately, then each status transition, and closes when the run is `COMPLETED`/`FAILED`:

```bash
curl -N -H "X-API-Key: $API_KEY" http://localhost:8086/api/analysis-runs/{runId}/events
```

**Analysis run status values:** `PENDING` → `RUNNING` → `COMPLETED` | `FAILED`

**Results response example:**
```json
{
  "jobRoleId": "...",
  "runId": "...",
  "runStatus": "COMPLETED",
  "results": [
    {
      "rank": 1,
      "overallScore": 87,
      "extractedName": "Alice Smith",
      "extractedEmail": "alice@example.com",
      "strengths": ["Strong Java background", "Relevant Spring experience", "..."],
      "weaknesses": ["No Kubernetes exposure", "..."],
      "summary": "Alice is a strong candidate with 7 years of Java development...",
      "recommendation": "Strong fit — recommend for interview"
    }
  ]
}
```

---

## Configuration

All config lives in `src/main/resources/application.yaml`. Key environment variables:

| Variable         | Description                     | Default         |
|------------------|---------------------------------|-----------------|
| `OPENAI_API_KEY` | API key (OpenAI or any OpenAI-compatible provider) | `change-me` in `application.yaml`; `local-dummy` in `local-vllm` profile |
| `OPENAI_BASE_URL`| Provider host (no `/v1` — Spring AI appends it). Override to use Groq / OpenRouter / vLLM. | `https://api.openai.com` |
| `OPENAI_MODEL`   | Chat model id | `gpt-4o-mini` |
| `API_KEY`        | Seeds a default API key on startup (sent as the `X-API-Key` header). Empty = auth disabled. | empty (open) |
| `ANALYSIS_COST_EUR_PER_1K_PROMPT_TOKENS` | EUR price per 1,000 prompt tokens, for cost estimation | `0.00014` (approximates `gpt-4o-mini`) |
| `ANALYSIS_COST_EUR_PER_1K_COMPLETION_TOKENS` | EUR price per 1,000 completion tokens, for cost estimation | `0.00055` (approximates `gpt-4o-mini`) |
| `ANALYSIS_COST_MONTHLY_BUDGET_EUR` | Global default monthly estimated-cost cap; new analysis runs return `429` once a key's effective budget is reached | `5.00` |

### API authentication

API keys are resolved from the `api_key` table (hashed lookup — nothing is stored or compared in plaintext). On startup, `API_KEY` (if set) is seeded as a row named `"default"` with no budget override, so existing single-key deployments keep working unchanged. Auth is enabled when the `API_KEY` environment variable is set: every `/api/**` request must then carry a valid `X-API-Key` header (resolved against the `api_key` table), otherwise it returns `401`. When `API_KEY` is unset, auth is disabled — open, the default for local development. This is decided from config at startup, not from whether the table has rows, so there is no fail-open window while the default key is being seeded. The `/health` endpoint always stays open for platform health checks.

```bash
# With auth enabled:
curl -H "X-API-Key: $API_KEY" https://<host>/api/job-roles
```

**Multiple keys / per-key budgets:** each row in `api_key` can carry its own `monthly_budget_eur` override (falls back to `ANALYSIS_COST_MONTHLY_BUDGET_EUR` when unset); `GET /api/usage/monthly` and the `429` budget check are scoped to the resolved caller's key. There's no admin endpoint yet — add a key by precomputing its SHA-256 hash (keys are never stored in plaintext) and inserting it directly:
```bash
KEY_HASH=$(printf '%s' 'their-raw-key' | openssl dgst -sha256 | awk '{print $2}')
psql "$DATABASE_URL" -c "INSERT INTO api_key (name, key_hash, monthly_budget_eur) VALUES ('acme-corp', '$KEY_HASH', 10.00);"
```

For any **OpenAI-compatible** provider (Groq, OpenRouter, Together, a local vLLM, …) no code change is needed — set `OPENAI_BASE_URL`, `OPENAI_MODEL`, and `OPENAI_API_KEY`. To switch to a **non-compatible** provider, replace `spring-ai-starter-model-openai` in `build.gradle` with that provider's starter (e.g. `spring-ai-starter-model-anthropic`) and update the `spring.ai.*` config block. Application code uses Spring AI’s provider-agnostic `ChatClient` API.

### Local inference (vLLM)

Use the `local-vllm` Spring profile to send chat completions to a **local OpenAI-compatible** server ([vLLM](https://docs.vllm.ai/)) instead of the public OpenAI API. Defaults in [`application-local-vllm.yaml`](src/main/resources/application-local-vllm.yaml) use **`http://localhost:8000`** as the API host (Spring AI adds **`/v1/chat/completions`**; do not put `/v1` in `base-url` or you get **`/v1/v1/...`**). Default model **`Qwen/Qwen3-4B-Instruct-2507`**.

1. Complete [First-time Setup](#first-time-setup) (PostgreSQL, Flyway, jOOQ) as usual.
2. Start vLLM (GPU). Example (PowerShell — set `cachePath` to your Hugging Face cache dir):

   ```powershell
   docker run `
     --gpus all `
     --rm `
     -it `
     -p 8000:8000 `
     -v "${cachePath}:/root/.cache/huggingface" `
     vllm/vllm-openai:latest `
     --model "Qwen/Qwen3-4B-Instruct-2507" `
     --gpu-memory-utilization 0.85 `
     --max-model-len 8192
   ```

   vLLM serves the OpenAI-compatible API at **`http://localhost:8000/v1/...`** on the host; set **`OPENAI_BASE_URL=http://localhost:8000`** for Spring AI (see [vLLM OpenAI-compatible server](https://docs.vllm.ai/en/latest/serving/openai_compatible_server.html)).

3. Run the app with the profile active:

   **Linux / macOS:**
   ```bash
   SPRING_PROFILES_ACTIVE=local-vllm ./gradlew bootRun
   ```

   **Windows (PowerShell):**
   ```powershell
   $env:SPRING_PROFILES_ACTIVE = "local-vllm"
   .\gradlew.bat bootRun
   ```

4. Optional environment overrides (see [`application-local-vllm.yaml`](src/main/resources/application-local-vllm.yaml)):

   | Variable           | Description |
   |--------------------|-------------|
   | `OPENAI_BASE_URL`  | API host only, e.g. `http://localhost:8000` (no `/v1`; Spring AI adds the path) |
   | `OPENAI_MODEL`     | Model id **exactly** as passed to vLLM `--model` |
   | `OPENAI_API_KEY`   | Dummy is fine locally (`local-dummy` in profile) |

**Tip:** `--max-model-len 8192` is enforced by vLLM; keep prompts + expected completion within that budget.

### Docker Compose and `SPRING_PROFILES_ACTIVE`

1. Copy [`.env.example`](.env.example) to **`.env`** in the project root (Compose requires this file because the `app` service uses `env_file: .env`).
2. Edit `.env`: set `OPENAI_API_KEY`, and optionally `SPRING_PROFILES_ACTIVE=local-vllm` plus `OPENAI_BASE_URL` / `OPENAI_MODEL` for local vLLM (see comments in `.env.example`).
3. Build the JAR, then start Compose:

```bash
./gradlew bootJar
docker compose up --build
```

**Local vLLM on the host:** Inside the container, `localhost` is not your machine. Set **`OPENAI_BASE_URL=http://host.docker.internal:8000`** (still no `/v1`). Compose already maps `host.docker.internal` to the host gateway (`extra_hosts: host.docker.internal:host-gateway`), so it resolves to the host on Linux, macOS, and Windows alike.

---

## Development workflow (after first-time setup)

```bash
# Start DB (if not running)
docker compose up -d postgres

# Run the app
OPENAI_API_KEY=sk-... ./gradlew bootRun

# Re-apply migrations after a schema change (jOOQ regenerates automatically on the next build)
./gradlew flywayMigrate
```

---

## Testing & Code Quality

```bash
./gradlew test          # unit tests (JUnit 5 + Mockito + AssertJ) — no database required
./gradlew spotlessApply # auto-format (Palantir Java Format, 120 columns)
./gradlew check         # spotlessCheck + tests
```

Code is formatted with [Spotless](https://github.com/diffplug/spotless) using Palantir Java Format; jOOQ-generated code lives under `build/generated-jooq` so it's outside Spotless's `src/**` target entirely (regenerate it instead of formatting). Run `./gradlew spotlessApply` before committing — CI enforces `spotlessCheck`.

Every push and pull request runs the [**CI** workflow](.github/workflows/ci.yml) (format check → tests → build) and the [**CodeQL** security scan](.github/workflows/codeql.yml).

## Smoke test

An end-to-end smoke test in [`smoke_test/smoke.py`](smoke_test/smoke.py) creates two job roles, uploads four CV fixtures to each, triggers LLM analysis, prints ranked results, and verifies the SSE event stream reaches a terminal status.

**Prerequisites:** Python 3.10+, `reportlab` (`pip install reportlab`). Docker is only needed for local mode.

**Local** (spins up the full stack via Docker Compose):
```bash
# 1. Copy the env template and set your key
cp smoke_test/.env.example smoke_test/.env
# edit smoke_test/.env — set OPENAI_API_KEY

# 2. Run from the project root
python smoke_test/smoke.py
```

**Remote / AWS** (runs against a deployed instance — no Docker needed):
```bash
BASE_URL=https://resume-scope.ecs.us-east-1.on.aws \
API_KEY=<your-api-key> \
python smoke_test/smoke.py
```

In local mode the script also builds and starts the Docker stack, then tears it down when done. Set `KEEP_RUNNING=1` to leave it up after the test.

---

## Known limitations

- **SSE is single-instance.** The run-status event bus is an in-memory reactive sink, so a client's SSE stream only sees events from the instance that is processing that run. Behind more than one replica, subscribe to the instance running the analysis or fall back to polling. Cross-instance fan-out (e.g. Postgres `LISTEN/NOTIFY` or Redis) is the scale-out path and is intentionally out of scope.
- **Prompt-injection surface.** A crafted résumé could try to influence its own score. This is mitigated as defense-in-depth — the CV is sent as **untrusted data** inside an unpredictable per-request delimiter, with an instruction never to obey text within it, and obvious manipulation attempts are logged — but not eliminated. Treat scores as advisory.

## AI-assisted development

This repo is set up for agentic AI coding tools. [`CLAUDE.md`](CLAUDE.md) documents the architecture and conventions, and [`.claude/skills/`](.claude/skills/) provides step-by-step playbooks for the two schema/API workflows that span multiple layers (adding a Flyway migration + jOOQ regen, and adding a REST endpoint with its OpenAPI update).
