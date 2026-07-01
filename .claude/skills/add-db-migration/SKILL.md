---
name: add-db-migration
description: Add a Flyway database migration to ResumeScope; jOOQ regenerates automatically. Use whenever you need to change the PostgreSQL schema — new table, column, index, or constraint.
---

# Add a Flyway migration (jOOQ regenerates automatically)

Schema changes flow **migration SQL → jOOQ codegen**. jOOQ reads the Flyway migration SQL directly (`DDLDatabase`, no live database involved) and regenerates classes into `build/generated-jooq/` on every compile — nothing is committed, nothing to run by hand for codegen.

## Steps

1. **Inspect existing migrations** in `src/main/resources/db/migration/` (V1–V6) to match style, naming, and column conventions (snake_case, timestamps, `JSONB` for arrays/objects, etc.).
2. **Create the next migration** `src/main/resources/db/migration/V{n}__{short_description}.sql`:
   - Use the next sequential version number (current highest is `V6`).
   - Migrations are **immutable and forward-only** — never edit an already-applied migration; add a new one instead.
   - Plain PostgreSQL DDL/DML.
3. **Regenerate + verify** (no database needed for this step):
   ```bash
   ./gradlew generateJooq
   ```
   Check `build/generated-jooq/.../db/generated/` picked up the new columns/tables as expected.
4. **Update affected repositories** in `repository/` to use the new columns/tables (jOOQ DSL). Follow the `*Repo` field naming and existing repository patterns.
5. **Apply the migration to a running Postgres** if you need to run or test the app locally:
   ```bash
   docker compose up -d postgres
   ./gradlew flywayMigrate
   ```
6. **Format & test:**
   ```bash
   ./gradlew spotlessApply test
   ```
7. If the change is reflected in the API, also update `src/main/resources/static/openapi.json` (see the `add-rest-endpoint` skill).

## Gotchas

- jOOQ's `JSONB` type needs the `forcedTypes` rule in `build.gradle` (matches the DDLDatabase-resolved `JSON` type name) — `DDLDatabase` alone maps `JSONB` columns to the generic `org.jooq.JSON` type, not `org.jooq.JSONB`.
- `flyway_schema_history` is excluded from jOOQ generation — don't reference it.
- **Never point jOOQ's `target.directory` at a directory containing hand-written sources** (e.g. `src/main/java`). jOOQ's directory-cleanup previously wiped every non-generated file when configured that way; output must stay under `build/`.
