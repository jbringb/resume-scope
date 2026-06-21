package dev.jbringb.resume_scope.repository;

import static dev.jbringb.resume_scope.db.generated.Tables.ANALYSIS_RUN;

import dev.jbringb.resume_scope.db.generated.tables.records.AnalysisRunRecord;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Repository
@RequiredArgsConstructor
public class AnalysisRunRepository {

    private final DSLContext dsl;

    public Mono<AnalysisRunRecord> insertPending(UUID jobRoleId) {
        return Mono.from(dsl.insertInto(ANALYSIS_RUN, ANALYSIS_RUN.JOB_ROLE_ID, ANALYSIS_RUN.STATUS)
                .values(jobRoleId, "PENDING")
                .returning());
    }

    public Mono<AnalysisRunRecord> findById(UUID id) {
        return Mono.from(dsl.selectFrom(ANALYSIS_RUN).where(ANALYSIS_RUN.ID.eq(id)));
    }

    public Flux<AnalysisRunRecord> findByJobRoleIdOrderByTriggeredDesc(UUID jobRoleId) {
        return Flux.from(dsl.selectFrom(ANALYSIS_RUN)
                .where(ANALYSIS_RUN.JOB_ROLE_ID.eq(jobRoleId))
                .orderBy(ANALYSIS_RUN.TRIGGERED_AT.desc()));
    }

    public Mono<AnalysisRunRecord> findLatestCompletedByJobRoleId(UUID jobRoleId) {
        return Mono.from(dsl.selectFrom(ANALYSIS_RUN)
                .where(ANALYSIS_RUN.JOB_ROLE_ID.eq(jobRoleId).and(ANALYSIS_RUN.STATUS.eq("COMPLETED")))
                .orderBy(ANALYSIS_RUN.COMPLETED_AT.desc())
                .limit(1));
    }

    public Mono<Void> updateStatus(UUID id, String status, OffsetDateTime completedAt, String errorMessage) {
        return Mono.from(dsl.update(ANALYSIS_RUN)
                        .set(ANALYSIS_RUN.STATUS, status)
                        .set(ANALYSIS_RUN.COMPLETED_AT, completedAt)
                        .set(ANALYSIS_RUN.ERROR_MESSAGE, errorMessage)
                        .where(ANALYSIS_RUN.ID.eq(id)))
                .then();
    }

    public Mono<Void> updateStatusOnly(UUID id, String status) {
        return Mono.from(dsl.update(ANALYSIS_RUN)
                        .set(ANALYSIS_RUN.STATUS, status)
                        .where(ANALYSIS_RUN.ID.eq(id)))
                .then();
    }
}
