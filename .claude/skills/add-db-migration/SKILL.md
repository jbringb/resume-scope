---
name: add-db-migration
description: Add a Flyway database migration to ResumeScope and regenerate the jOOQ code. Use whenever you need to change the PostgreSQL schema — new table, column, index, or constraint.
---

# Add a Flyway migration + regenerate jOOQ

In this project, schema changes flow **migration → live DB → jOOQ codegen**. jOOQ-generated classes are committed in `src/main/java/dev/jbringb/resume_scope/db/generated/`, so they must be regenerated and committed whenever the schema changes.

## Steps

1. **Inspect existing migrations** in `src/main/resources/db/migration/` (V1–V5) to match style, naming, and column conventions (snake_case, timestamps, `JSONB` for arrays/objects, etc.).
2. **Create the next migration** `src/main/resources/db/migration/V{n}__{short_description}.sql`:
   - Use the next sequential version number (current highest is `V5`).
   - Migrations are **immutable and forward-only** — never edit an already-applied migration; add a new one instead.
   - Plain PostgreSQL DDL/DML.
3. **Apply + regenerate** (needs Postgres running):
   ```bash
   docker compose up -d postgres
   ./gradlew flywayMigrate generateJooq
   ```
   This writes updated `*Record`/`Tables`/`Keys` classes into `db/generated/` — **commit those**, do not hand-edit them.
4. **Update affected repositories** in `repository/` to use the new columns/tables (jOOQ DSL). Follow the `*Repo` field naming and existing repository patterns.
5. **Format & test:**
   ```bash
   ./gradlew spotlessApply test
   ```
6. If the change is reflected in the API, also update `src/main/resources/static/openapi.json` (see the `add-rest-endpoint` skill).

## Gotchas

- `generateJooq` reads the **live** schema, so migrations must be applied first.
- The gradle Flyway/jOOQ tasks read `DB_URL`/`DB_USER`/`DB_PASSWORD` (defaults target `localhost:5432/resumescope`), which is separate from the app's runtime datasource env vars.
- `flyway_schema_history` is excluded from jOOQ generation — don't reference it.
