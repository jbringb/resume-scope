---
name: add-rest-endpoint
description: Add or change a REST endpoint in ResumeScope end-to-end (controller, DTO records, service, repository) and keep the OpenAPI spec in sync. Use whenever adding/modifying an API route, request/response shape, or status code.
---

# Add or change a REST endpoint end-to-end

Touch every layer in one change, and keep `openapi.json` (the committed API contract) in lockstep.

## Steps

1. **Controller** in `api/` — add/extend the relevant controller (`JobRoleController`, `JobRoleCandidateController`, `JobRoleAnalysisController`, `AnalysisRunController`). Inject services as `*Svc` fields via `@RequiredArgsConstructor`. Return appropriate status codes (async triggers return **202 + runId**; see `JobRoleAnalysisController`).
2. **DTOs** in `api/dto/` — request/response **records**. Apply Jakarta validation on request bodies like the existing ones:
   ```java
   public record JobRoleRequest(@NotBlank @Size(max = 255) String title, String description, String requirements) {}
   ```
   Ensure the controller param is annotated `@Valid`.
3. **Service** in `service/` — put business logic in a `*Service` class, injected as `*Svc`. Keep controllers thin.
4. **Repository** in `repository/` — add jOOQ queries in the matching `*Repository` (injected as `*Repo`). Never query `db/generated` records by hand-editing them.
5. **Update the OpenAPI contract** — edit `src/main/resources/static/openapi.json` (served at `/openapi.json`) in the **same** change: new path, request/response schema, and status codes. Do not leave it stale.
6. **Test** — add or adjust a JUnit 5 + Mockito test under `src/test/java/...` mirroring `AnalysisServiceTest` / `CvAnalyzerServiceTest` (mock `ChatClient`, repositories, `AnalysisEventBus`; assert status codes, invocation order, transformations).
7. **Format & verify:**
   ```bash
   ./gradlew spotlessApply test
   ```

## Checklist

- [ ] Controller route + status codes
- [ ] Request/response records with validation
- [ ] Service method (`*Svc`)
- [ ] Repository method (`*Repo`)
- [ ] `openapi.json` updated to match
- [ ] Test added/updated
- [ ] `spotlessApply` + `test` green
