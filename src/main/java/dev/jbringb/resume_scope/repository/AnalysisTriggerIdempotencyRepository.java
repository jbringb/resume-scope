package dev.jbringb.resume_scope.repository;

import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Table;
import org.jooq.impl.DSL;
import org.jooq.impl.SQLDataType;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class AnalysisTriggerIdempotencyRepository {

    private static final Table<?> ANALYSIS_TRIGGER_IDEMPOTENCY = DSL.table(DSL.name("analysis_trigger_idempotency"));
    private static final Field<UUID> JOB_ROLE_ID = DSL.field(DSL.name("job_role_id"), SQLDataType.UUID.nullable(false));
    private static final Field<String> IDEMPOTENCY_KEY =
            DSL.field(DSL.name("idempotency_key"), SQLDataType.VARCHAR(255).nullable(false));
    private static final Field<UUID> ANALYSIS_RUN_ID =
            DSL.field(DSL.name("analysis_run_id"), SQLDataType.UUID.nullable(false));

    private final DSLContext dsl;

    public Optional<UUID> findAnalysisRunId(UUID jobRoleId, String idempotencyKey) {
        return dsl.select(ANALYSIS_RUN_ID)
                .from(ANALYSIS_TRIGGER_IDEMPOTENCY)
                .where(JOB_ROLE_ID.eq(jobRoleId).and(IDEMPOTENCY_KEY.eq(idempotencyKey)))
                .fetchOptional(ANALYSIS_RUN_ID);
    }

    public void insert(UUID jobRoleId, String idempotencyKey, UUID analysisRunId) {
        dsl.insertInto(ANALYSIS_TRIGGER_IDEMPOTENCY, JOB_ROLE_ID, IDEMPOTENCY_KEY, ANALYSIS_RUN_ID)
                .values(jobRoleId, idempotencyKey, analysisRunId)
                .execute();
    }
}
