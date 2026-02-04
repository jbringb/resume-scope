package dev.jbringb.resume_scope.repository;

import static dev.jbringb.resume_scope.db.generated.Tables.ANALYSIS_RUN;

import dev.jbringb.resume_scope.db.generated.tables.records.AnalysisRunRecord;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class AnalysisRunRepository {

    private final DSLContext dsl;

    public AnalysisRunRecord insertPending(UUID jobRoleId) {
        return dsl.insertInto(ANALYSIS_RUN, ANALYSIS_RUN.JOB_ROLE_ID, ANALYSIS_RUN.STATUS)
                .values(jobRoleId, "PENDING")
                .returning()
                .fetchSingleInto(AnalysisRunRecord.class);
    }

    public Optional<AnalysisRunRecord> findById(UUID id) {
        return dsl.selectFrom(ANALYSIS_RUN).where(ANALYSIS_RUN.ID.eq(id)).fetchOptionalInto(AnalysisRunRecord.class);
    }

    public List<AnalysisRunRecord> findByJobRoleIdOrderByTriggeredDesc(UUID jobRoleId) {
        return dsl.selectFrom(ANALYSIS_RUN)
                .where(ANALYSIS_RUN.JOB_ROLE_ID.eq(jobRoleId))
                .orderBy(ANALYSIS_RUN.TRIGGERED_AT.desc())
                .fetchInto(AnalysisRunRecord.class);
    }

    public Optional<AnalysisRunRecord> findLatestCompletedByJobRoleId(UUID jobRoleId) {
        return dsl.selectFrom(ANALYSIS_RUN)
                .where(ANALYSIS_RUN.JOB_ROLE_ID.eq(jobRoleId).and(ANALYSIS_RUN.STATUS.eq("COMPLETED")))
                .orderBy(ANALYSIS_RUN.COMPLETED_AT.desc())
                .limit(1)
                .fetchOptionalInto(AnalysisRunRecord.class);
    }

    public void updateStatus(UUID id, String status, OffsetDateTime completedAt, String errorMessage) {
        dsl.update(ANALYSIS_RUN)
                .set(ANALYSIS_RUN.STATUS, status)
                .set(ANALYSIS_RUN.COMPLETED_AT, completedAt)
                .set(ANALYSIS_RUN.ERROR_MESSAGE, errorMessage)
                .where(ANALYSIS_RUN.ID.eq(id))
                .execute();
    }

    public void updateStatusOnly(UUID id, String status) {
        dsl.update(ANALYSIS_RUN)
                .set(ANALYSIS_RUN.STATUS, status)
                .where(ANALYSIS_RUN.ID.eq(id))
                .execute();
    }
}
