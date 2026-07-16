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

    public Mono<AnalysisRunRecord> insertPending(UUID jobRoleId, UUID apiKeyId) {
        return Mono.from(
                dsl.insertInto(ANALYSIS_RUN, ANALYSIS_RUN.JOB_ROLE_ID, ANALYSIS_RUN.STATUS, ANALYSIS_RUN.API_KEY_ID)
                        .values(jobRoleId, "PENDING", apiKeyId)
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

    // Guarded by status NOT IN (...) so this can never clobber a run that has already reached a
    // terminal state from another writer — e.g. the background worker's completeWithUsage racing
    // against a client-triggered expireIfStale near the run timeout boundary. Returns whether a row
    // actually transitioned, so callers can tell a real failure from a no-op.
    public Mono<Boolean> updateStatus(UUID id, String status, OffsetDateTime completedAt, String errorMessage) {
        return Mono.from(dsl.update(ANALYSIS_RUN)
                        .set(ANALYSIS_RUN.STATUS, status)
                        .set(ANALYSIS_RUN.COMPLETED_AT, completedAt)
                        .set(ANALYSIS_RUN.ERROR_MESSAGE, errorMessage)
                        .where(ANALYSIS_RUN.ID.eq(id))
                        .and(ANALYSIS_RUN.STATUS.notIn("COMPLETED", "FAILED")))
                .map(rowsAffected -> rowsAffected > 0);
    }

    public Mono<Void> updateStatusOnly(UUID id, String status) {
        return Mono.from(dsl.update(ANALYSIS_RUN)
                        .set(ANALYSIS_RUN.STATUS, status)
                        .where(ANALYSIS_RUN.ID.eq(id)))
                .then();
    }

    // Same terminal-state guard as updateStatus, in the other direction: a slow-but-genuine
    // completion must not resurrect a run that a concurrent expireIfStale already marked FAILED.
    public Mono<Boolean> completeWithUsage(
            UUID id, OffsetDateTime completedAt, int promptTokens, int completionTokens, BigDecimal estimatedCostEur) {
        return Mono.from(dsl.update(ANALYSIS_RUN)
                        .set(ANALYSIS_RUN.STATUS, "COMPLETED")
                        .set(ANALYSIS_RUN.COMPLETED_AT, completedAt)
                        .set(ANALYSIS_RUN.PROMPT_TOKENS, promptTokens)
                        .set(ANALYSIS_RUN.COMPLETION_TOKENS, completionTokens)
                        .set(ANALYSIS_RUN.ESTIMATED_COST_EUR, estimatedCostEur)
                        .where(ANALYSIS_RUN.ID.eq(id))
                        .and(ANALYSIS_RUN.STATUS.notIn("COMPLETED", "FAILED")))
                .map(rowsAffected -> rowsAffected > 0);
    }

    // Whether a non-terminal (PENDING/RUNNING), non-expired run already exists for this API key (or
    // for keyless/auth-disabled runs when apiKeyId is null). Used to cap in-flight runs per key at
    // one, so a burst of concurrent trigger requests can't all bypass the budget check before any of
    // them completes — completed cost is the only thing sumUsageSince can see (see checkBudget).
    public Mono<Boolean> existsActiveRun(UUID apiKeyId, OffsetDateTime notExpiredSince) {
        var keyCondition = apiKeyId == null ? ANALYSIS_RUN.API_KEY_ID.isNull() : ANALYSIS_RUN.API_KEY_ID.eq(apiKeyId);
        return Mono.from(dsl.selectOne()
                        .from(ANALYSIS_RUN)
                        .where(keyCondition)
                        .and(ANALYSIS_RUN.STATUS.in("PENDING", "RUNNING"))
                        .and(ANALYSIS_RUN.TRIGGERED_AT.ge(notExpiredSince))
                        .limit(1))
                .hasElement();
    }

    // Sums token usage and estimated cost across runs triggered since `since`, scoped to a single
    // API key (or to keyless/auth-disabled runs when apiKeyId is null) — used for the monthly usage
    // endpoint and the budget check before starting a new run.
    public Mono<UsageTotals> sumUsageSince(OffsetDateTime since, UUID apiKeyId) {
        var keyCondition = apiKeyId == null ? ANALYSIS_RUN.API_KEY_ID.isNull() : ANALYSIS_RUN.API_KEY_ID.eq(apiKeyId);
        return Mono.from(dsl.select(
                                DSL.coalesce(DSL.sum(ANALYSIS_RUN.PROMPT_TOKENS), BigDecimal.ZERO),
                                DSL.coalesce(DSL.sum(ANALYSIS_RUN.COMPLETION_TOKENS), BigDecimal.ZERO),
                                DSL.coalesce(DSL.sum(ANALYSIS_RUN.ESTIMATED_COST_EUR), BigDecimal.ZERO))
                        .from(ANALYSIS_RUN)
                        .where(ANALYSIS_RUN.TRIGGERED_AT.ge(since).and(keyCondition)))
                .map(r -> new UsageTotals(r.value1().longValue(), r.value2().longValue(), r.value3()));
    }

    public record UsageTotals(long promptTokens, long completionTokens, BigDecimal estimatedCostEur) {}
}
