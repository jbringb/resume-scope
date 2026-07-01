package dev.jbringb.resume_scope.repository;

import static dev.jbringb.resume_scope.db.generated.Tables.ANALYSIS_RUN;

import dev.jbringb.resume_scope.db.generated.tables.records.AnalysisRunRecord;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
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

    public Mono<Void> completeWithUsage(
            UUID id, OffsetDateTime completedAt, int promptTokens, int completionTokens, BigDecimal estimatedCostEur) {
        return Mono.from(dsl.update(ANALYSIS_RUN)
                        .set(ANALYSIS_RUN.STATUS, "COMPLETED")
                        .set(ANALYSIS_RUN.COMPLETED_AT, completedAt)
                        .set(ANALYSIS_RUN.PROMPT_TOKENS, promptTokens)
                        .set(ANALYSIS_RUN.COMPLETION_TOKENS, completionTokens)
                        .set(ANALYSIS_RUN.ESTIMATED_COST_EUR, estimatedCostEur)
                        .where(ANALYSIS_RUN.ID.eq(id)))
                .then();
    }

    // Sums token usage and estimated cost across all runs triggered since `since` (used for the
    // monthly usage endpoint and the budget check before starting a new run).
    public Mono<UsageTotals> sumUsageSince(OffsetDateTime since) {
        return Mono.from(dsl.select(
                                DSL.coalesce(DSL.sum(ANALYSIS_RUN.PROMPT_TOKENS), BigDecimal.ZERO),
                                DSL.coalesce(DSL.sum(ANALYSIS_RUN.COMPLETION_TOKENS), BigDecimal.ZERO),
                                DSL.coalesce(DSL.sum(ANALYSIS_RUN.ESTIMATED_COST_EUR), BigDecimal.ZERO))
                        .from(ANALYSIS_RUN)
                        .where(ANALYSIS_RUN.TRIGGERED_AT.ge(since)))
                .map(r -> new UsageTotals(r.value1().longValue(), r.value2().longValue(), r.value3()));
    }

    public record UsageTotals(long promptTokens, long completionTokens, BigDecimal estimatedCostEur) {}
}
