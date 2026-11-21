---
name: upgrade-deps
description: Check ResumeScope's dependencies for new versions and known vulnerabilities (CVEs), upgrade them with the Spring Boot lockstep rules, verify, and ship. Use for any dependency, plugin, Gradle wrapper, GitHub Actions or Docker image upgrade or security check — and as the monthly scheduled upgrade run. Dependabot version-update PRs are turned off; this skill replaces them.
---

# Upgrade dependencies (security first)

Two scripts do the checking; run them from the repo root (`python` or `python3`):

- `scripts/check_versions.py` — newer versions of everything in `build.gradle`, the Gradle wrapper, GitHub Actions and Docker images, plus the **lockstep check** below. Exit 1 = lockstep broken.
- `scripts/osv_scan.py` — resolves the real classpaths (`runtimeClasspath`, `testRuntimeClasspath`, `jooqGenerator`) and checks all ~235 artifacts against [OSV](https://osv.dev), with fixed-in versions; also flags artifact families resolved at more than one version. Exit 1 = vulnerable or mixed.

GitHub's Dependabot alerts only see what `build.gradle` declares, not transitive jars (Netty, Jackson, the Postgres drivers), so **`osv_scan.py` is the real security check** — an empty alerts page proves nothing.

## Lockstep rules (why the obvious "bump to latest" is wrong here)

Spring Boot's BOM (`spring-boot-dependencies`) manages most versions. These are pinned by hand and **must equal what Boot manages**, never "latest":

| Pinned in `build.gradle` | Must equal |
|---|---|
| `jooq { version = … }` and `jooqGenerator 'org.jooq:jooq-meta-extensions:…'` | Boot's `jooq.version` — the BOM also leaks into the codegen classpath, so a different version silently mixes jOOQ jars |
| `id 'org.flywaydb.flyway' version …` and the `buildscript` `flyway-database-postgresql` | Boot's `flyway.version` — otherwise local `flywayMigrate` uses a different Flyway than the app |
| `spring-ai-bom` | A Spring AI release built against the **same Boot minor** (2.0.x → Boot 4.1, 2.1.x → Boot 4.2) |

`check_versions.py` prints, for each Boot candidate, the jOOQ/Flyway/Spring AI versions to use. Testcontainers, Netty, Jackson, drivers etc. carry no version in `build.gradle` — they move with Boot.

## Steps

1. **Start clean.** `git checkout main && git pull`. Unattended (scheduled) run: create branch `fix-deps-<yyyy-mm-dd>`; never push to `main`.
2. **Baseline.** Run both scripts; keep the output for the report.
3. **Pick the target set:**
   - **Security fixes always.** For each `osv_scan.py` hit, prefer the Boot patch that ships the fix. If none does, add a forced version in `dependencyManagement { dependencies { … } }` with a one-line comment naming the advisory and when to drop it (see the existing SCRAM override).
   - **Spring Boot:** latest patch always; latest **minor** only together with a Spring AI release for that minor (if "Spring AI none yet", stay on the current minor). Boot **major**: report only.
   - **jOOQ / Flyway / Spring AI:** exactly the values the chosen Boot line prints.
   - **Everything else** in `build.gradle` (PDFBox, Spotless, Palantir formatter, `nu.studer.jooq`, `io.spring.dependency-management`): latest stable minor/patch. Majors: report only, unless trivially safe.
   - **Overrides:** drop a forced version once the library that pulled the vulnerable one ships the fix itself (check with `./gradlew -q dependencyInsight --dependency <artifact> --configuration runtimeClasspath`).
   - **Gradle wrapper:** run `./gradlew wrapper --gradle-version <v> --distribution-type bin` **twice** (the second run updates the wrapper jar and scripts).
   - **GitHub Actions:** bump `uses: …@vN` to the major shown; skim the release notes for removed inputs first.
   - **Docker images:** tags float (`eclipse-temurin:25-*`, `postgres:17-alpine`), so patches arrive on rebuild. Majors (new Java, new Postgres) are a deliberate decision — report only. If you bump Java, also update `java.toolchain`, CI `java-version` and the Dockerfile comment; a Postgres major also means the test containers (`postgres:17-alpine` in `src/test`) and compose files.
4. **Apply**, then `./gradlew spotlessApply`. A formatter bump may restyle code; that's expected, but check that `git diff -w` on `src/` shows no logic changes. (Git may then list unchanged files as modified because only their timestamps changed; `git diff --name-only` is the truth.)
5. **Verify — all must pass:**
   - `check_versions.py` → `Lockstep OK.`; `osv_scan.py` → `vulnerable artifacts: 0, mixed families: 0`.
   - `./gradlew clean check bootJar` — Spotless, all tests, jar. Four test classes use Testcontainers and need Docker. Without Docker, run `./gradlew spotlessCheck bootJar test --tests 'dev.jbringb.resume_scope.service.*' --tests 'dev.jbringb.resume_scope.config.*' --tests 'dev.jbringb.resume_scope.pdf.*'`, and say in the PR that the full suite runs in CI.
   - **Smoke test** (real Docker image + live OpenAI, about €0.003): only if Docker works and `smoke_test/.env` has an `OPENAI_API_KEY`. Run in the background: `API_KEY="smoke-$(date +%s)" python smoke_test/smoke.py`. All 14 steps must pass. It also fails by itself when scores break the expectations in `smoke_test/scoring.py`. When Spring AI changes, also run `python smoke_test/eval.py --repeat 3` (see the `scoring-eval` skill). If you can't run it, write "smoke test not run" in the report.
   - If a minor bump breaks the build and the fix isn't obvious, fall back to the patch-only set and report the blocker.
6. **Docs.** Update the version mentions in `CLAUDE.md` (Tech stack) and the `README.md` stack table to match.
7. **Ship.** One commit with a one-line Conventional Commits message (e.g. `chore: upgrade to Spring Boot 4.2.1 and patch vulnerable dependencies`). No body and **no attribution trailers** (no `Co-Authored-By`, no "Generated with" line), in commits and PR bodies alike.
   - Interactive: commit and push only when the user asks.
   - Unattended: push the branch and open a PR titled with the commit subject. Body: what changed (old → new), the before/after scan results, what was verified (and what wasn't, e.g. smoke test), and **report-only items** (majors, Docker majors, blockers). If nothing needs changing and nothing is vulnerable, don't open a PR — just report that.

## Report (always)

Plain-language summary: what was vulnerable and how it was fixed, what was upgraded, what was deliberately left alone and why (lockstep pins, majors), what was verified, and anything that could bite later.
