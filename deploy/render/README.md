# Deploy on Render.com

This project ships a [Render Blueprint](../../render.yaml): a **Web Service** (Docker) plus a **free-tier PostgreSQL** instance. Spring reads Postgres via `PGHOST`, `PGPORT`, `PGDATABASE`, `PGUSER`, `PGPASSWORD` (see [`application.yaml`](../../src/main/resources/application.yaml)).

The [Dockerfile](../../Dockerfile) is **self-building** — Render compiles the boot jar from source inside the image, so there is **no local build step**. Deploys are driven by GitHub Actions ([`deploy-render.yml`](../../.github/workflows/deploy-render.yml)); `render.yaml` sets `autoDeploy: false` so the workflow is the single trigger.

**Logs:** ECS JSON on stdout (`logging` in `application.yaml`); optional `APP_VERSION`, `LOGGING_STRUCTURED_FORMAT_CONSOLE` (empty = plain text). The blueprint sets `DEPLOYMENT_ENV=production`.

## One-time setup

1. **Create the Blueprint.** Push this repo to GitHub, then in the [Render Dashboard](https://dashboard.render.com/) choose **New → Blueprint**, connect the repo, and select `render.yaml`. Confirm the resources (web service + free Postgres) and **Apply**.
2. **Set the secrets.** During the first deploy Render prompts for the `sync: false` vars:
   - `OPENAI_API_KEY` — your OpenAI key.
   - `API_KEY` — a shared secret that clients must send as the `X-API-Key` header on every `/api/**` request. Pick a long random value. Leave it unset only if you intend the API to be public. (The health check at `/health` is always open, so leaving `API_KEY` set does not affect Render's health checks.)
3. **Wire the deploy hook into GitHub.** On the web service: **Settings → Deploy Hook**, copy the URL, and add it as a GitHub repository secret named **`RENDER_DEPLOY_HOOK_URL`** (Settings → Secrets and variables → Actions). Recommended: create a GitHub **Environment** named `render` and scope the secret to it.
4. **(Optional) Real pass/fail feedback.** To make the workflow *wait* for the deploy to go live, also add:
   - **`RENDER_API_KEY`** — Account Settings → API Keys.
   - **`RENDER_SERVICE_ID`** — the `srv-…` id from the service URL.

   Without these, the workflow just triggers the deploy and you track progress in the dashboard.

## Deploying

Deploys are **manual only**: Actions → **Deploy · Render** → **Run workflow**. (The workflow is `workflow_dispatch`-only — pushes to `main` do not deploy.)

After it goes live, open the web service URL and try `GET /api/job-roles` (an empty list `[]` means healthy).

## Plans and limits

Render's **free** web and database tiers can sleep when idle and have resource limits — first request after a sleep is slow (cold JVM + Flyway). See [Render pricing](https://render.com/pricing); bump the `plan` fields in `render.yaml` to scale up.
