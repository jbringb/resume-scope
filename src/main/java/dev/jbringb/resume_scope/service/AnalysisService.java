package dev.jbringb.resume_scope.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.jbringb.resume_scope.api.dto.AnalysisRunResponse;
import dev.jbringb.resume_scope.api.dto.CandidateResultResponse;
import dev.jbringb.resume_scope.api.dto.JobRoleResultsResponse;
import dev.jbringb.resume_scope.api.dto.MonthlyUsageResponse;
import dev.jbringb.resume_scope.api.dto.TriggerAnalysisResponse;
import dev.jbringb.resume_scope.db.generated.tables.records.AnalysisRecord;
import dev.jbringb.resume_scope.db.generated.tables.records.AnalysisRunRecord;
import dev.jbringb.resume_scope.repository.AnalysisRepository;
import dev.jbringb.resume_scope.repository.AnalysisRunRepository;
import dev.jbringb.resume_scope.repository.AnalysisTriggerIdempotencyRepository;
import dev.jbringb.resume_scope.repository.ApiKeyRepository;
import dev.jbringb.resume_scope.repository.CandidateRepository;
import dev.jbringb.resume_scope.repository.JobRoleRepository;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jooq.JSONB;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Service
@RequiredArgsConstructor
@Slf4j
public class AnalysisService {

    private final JobRoleRepository jobRoleRepo;
    private final CandidateRepository candidateRepo;
    private final AnalysisRunRepository analysisRunRepo;
    private final AnalysisRepository analysisRepo;
    private final CvAnalyzerService cvAnalyzerSvc;
    private final ObjectMapper objectMapper;
    private final AnalysisTriggerIdempotencyRepository analysisTriggerIdempotencyRepo;
    private final AnalysisTriggerIdempotencyLock analysisTriggerIdempotencyLock;
    private final ApiKeyRepository apiKeyRepo;

    private static final Set<String> TERMINAL_STATUSES = Set.of("COMPLETED", "FAILED");
    private static final String EXPIRED_MESSAGE = "Expired: no result within the allowed time";

    // A run is considered expired this many minutes after it was triggered (see expireIfStale).
    @Value("${analysis.run-timeout-minutes:10}")
    private int runTimeoutMinutes = 10;

    // New runs are rejected once this month's estimated LLM spend reaches the budget (see checkBudget).
    @Value("${analysis.cost.monthly-budget-eur}")
    private BigDecimal monthlyBudgetEur;

    public Mono<TriggerAnalysisResponse> triggerAnalysis(
            UUID jobRoleId, Optional<String> idempotencyKeyHeader, UUID apiKeyId) {
        log.info("Triggering analysis for job role {}", jobRoleId);
        return jobRoleRepo
                .findById(jobRoleId)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Job role not found")))
                .flatMap(role -> candidateRepo.findByJobRoleId(jobRoleId).hasElements())
                .flatMap(hasCandidates -> {
                    if (!hasCandidates) {
                        return Mono.error(
                                new ResponseStatusException(HttpStatus.BAD_REQUEST, "No candidates to analyze"));
                    }
                    var key = normalizeIdempotencyKey(idempotencyKeyHeader);
                    return key.isEmpty()
                            ? insertRunAndSchedule(jobRoleId, apiKeyId)
                            : idempotentTrigger(jobRoleId, key.get(), apiKeyId);
                });
    }

    // Serialized per (jobRoleId, key) by the advisory lock: replay an active run, otherwise start a
    // fresh one and repoint the key. A brand-new key inserts the mapping.
    private Mono<TriggerAnalysisResponse> idempotentTrigger(UUID jobRoleId, String key, UUID apiKeyId) {
        Mono<TriggerAnalysisResponse> action = analysisTriggerIdempotencyRepo
                .findAnalysisRunId(jobRoleId, key)
                .flatMap(existingRunId -> analysisRunRepo
                        .findById(existingRunId)
                        .flatMap(existingRun -> isActive(existingRun)
                                ? Mono.just(new TriggerAnalysisResponse(existingRunId))
                                : expireIfStale(existingRun).then(newRunRepointing(jobRoleId, key, apiKeyId)))
                        // Mapping exists but the run row is gone: start fresh and repoint.
                        .switchIfEmpty(Mono.defer(() -> newRunRepointing(jobRoleId, key, apiKeyId))))
                .switchIfEmpty(Mono.defer(() -> newRunWithMapping(jobRoleId, key, apiKeyId)));
        return analysisTriggerIdempotencyLock.withJobRoleKeyLock(jobRoleId, key, action);
    }

    private Mono<TriggerAnalysisResponse> newRunWithMapping(UUID jobRoleId, String key, UUID apiKeyId) {
        return insertRunAndSchedule(jobRoleId, apiKeyId).flatMap(resp -> analysisTriggerIdempotencyRepo
                .insert(jobRoleId, key, resp.runId())
                .thenReturn(resp));
    }

    private Mono<TriggerAnalysisResponse> newRunRepointing(UUID jobRoleId, String key, UUID apiKeyId) {
        return insertRunAndSchedule(jobRoleId, apiKeyId).flatMap(resp -> analysisTriggerIdempotencyRepo
                .repoint(jobRoleId, key, resp.runId())
                .thenReturn(resp));
    }

    private boolean isActive(AnalysisRunRecord run) {
        return !TERMINAL_STATUSES.contains(run.getStatus()) && !isExpired(run);
    }

    private boolean isExpired(AnalysisRunRecord run) {
        var triggeredAt = run.getTriggeredAt();
        return triggeredAt == null || triggeredAt.isBefore(OffsetDateTime.now().minusMinutes(runTimeoutMinutes));
    }

    // Lazily fail a non-terminal run that has outlived its timeout, so it stops showing PENDING/RUNNING.
    private Mono<AnalysisRunRecord> expireIfStale(AnalysisRunRecord run) {
        if (TERMINAL_STATUSES.contains(run.getStatus()) || !isExpired(run)) {
            return Mono.just(run);
        }
        var now = OffsetDateTime.now();
        return analysisRunRepo
                .updateStatus(run.getId(), "FAILED", now, EXPIRED_MESSAGE)
                .thenReturn(run)
                .map(r -> {
                    r.setStatus("FAILED");
                    r.setCompletedAt(now);
                    r.setErrorMessage(EXPIRED_MESSAGE);
                    return r;
                });
    }

    private Mono<TriggerAnalysisResponse> insertRunAndSchedule(UUID jobRoleId, UUID apiKeyId) {
        return checkBudget(apiKeyId)
                .then(Mono.defer(() -> analysisRunRepo.insertPending(jobRoleId, apiKeyId)))
                .map(AnalysisRunRecord::getId)
                .doOnNext(runId -> log.info("Analysis run inserted: {}", runId))
                .doOnNext(this::dispatchProcessing)
                .map(TriggerAnalysisResponse::new);
    }

    private Mono<Void> checkBudget(UUID apiKeyId) {
        return Mono.zip(analysisRunRepo.sumUsageSince(currentMonthStart(), apiKeyId), resolveBudgetAndName(apiKeyId))
                .flatMap(t -> {
                    var usage = t.getT1();
                    var budget = t.getT2().budget();
                    if (usage.estimatedCostEur().compareTo(budget) >= 0) {
                        return Mono.error(new ResponseStatusException(
                                HttpStatus.TOO_MANY_REQUESTS,
                                "Monthly LLM cost budget of €%s exceeded (spent €%s this month)"
                                        .formatted(budget, usage.estimatedCostEur())));
                    }
                    return Mono.empty();
                });
    }

    public Mono<MonthlyUsageResponse> monthlyUsage(UUID apiKeyId) {
        var periodStart = currentMonthStart();
        return analysisRunRepo.sumUsageSince(periodStart, apiKeyId).flatMap(usage -> resolveBudgetAndName(apiKeyId)
                .map(budgetAndName -> new MonthlyUsageResponse(
                        periodStart,
                        usage.promptTokens(),
                        usage.completionTokens(),
                        usage.promptTokens() + usage.completionTokens(),
                        usage.estimatedCostEur(),
                        budgetAndName.budget(),
                        usage.estimatedCostEur().compareTo(budgetAndName.budget()) >= 0,
                        budgetAndName.name())));
    }

    // Resolves the effective monthly budget for a request: the key's own override if set, otherwise
    // the global default. apiKeyId is null when auth is disabled (no keys configured).
    private Mono<BudgetAndName> resolveBudgetAndName(UUID apiKeyId) {
        if (apiKeyId == null) {
            return Mono.just(new BudgetAndName(monthlyBudgetEur, null));
        }
        return apiKeyRepo
                .findById(apiKeyId)
                .map(k -> new BudgetAndName(
                        k.getMonthlyBudgetEur() != null ? k.getMonthlyBudgetEur() : monthlyBudgetEur, k.getName()))
                .defaultIfEmpty(new BudgetAndName(monthlyBudgetEur, null));
    }

    private record BudgetAndName(BigDecimal budget, String name) {}

    private static OffsetDateTime currentMonthStart() {
        return OffsetDateTime.now(ZoneOffset.UTC).withDayOfMonth(1).truncatedTo(ChronoUnit.DAYS);
    }

    // The PENDING insert auto-commits on its own connection, so by the time this fires the run is
    // durably visible — no afterCommit synchronization needed. Processing runs detached from the request.
    private void dispatchProcessing(UUID runId) {
        cvAnalyzerSvc
                .processAnalysisRun(runId)
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(null, e -> log.error("Background analysis for run {} failed to start", runId, e));
    }

    private static Optional<String> normalizeIdempotencyKey(Optional<String> headerValue) {
        if (headerValue.isEmpty()) {
            return Optional.empty();
        }
        var trimmed = headerValue.get().trim();
        if (trimmed.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Idempotency-Key must not be blank");
        }
        if (trimmed.length() > 255) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Idempotency-Key too long");
        }
        return Optional.of(trimmed);
    }

    public Mono<List<AnalysisRunResponse>> listRuns(UUID jobRoleId) {
        return jobRoleRepo
                .findById(jobRoleId)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Job role not found")))
                .thenMany(analysisRunRepo.findByJobRoleIdOrderByTriggeredDesc(jobRoleId))
                .concatMap(this::expireIfStale)
                .map(this::toRunDto)
                .collectList();
    }

    public Mono<JobRoleResultsResponse> latestResultsForJobRole(UUID jobRoleId) {
        return jobRoleRepo
                .findById(jobRoleId)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Job role not found")))
                .flatMap(role -> analysisRunRepo.findLatestCompletedByJobRoleId(jobRoleId))
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "No completed analysis")))
                .flatMap(run -> mapRunResults(jobRoleId, run));
    }

    public Mono<AnalysisRunResponse> getRun(UUID runId) {
        return analysisRunRepo
                .findById(runId)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Analysis run not found")))
                .flatMap(this::expireIfStale)
                .map(this::toRunDto);
    }

    public Mono<JobRoleResultsResponse> resultsForRun(UUID runId) {
        return analysisRunRepo
                .findById(runId)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Analysis run not found")))
                .flatMap(this::expireIfStale)
                .flatMap(run -> mapRunResults(run.getJobRoleId(), run));
    }

    private Mono<JobRoleResultsResponse> mapRunResults(UUID jobRoleId, AnalysisRunRecord run) {
        return analysisRepo
                .findByAnalysisRunId(run.getId())
                .map(this::toResultDto)
                .collectList()
                .map(results -> new JobRoleResultsResponse(jobRoleId, run.getId(), run.getStatus(), results));
    }

    private AnalysisRunResponse toRunDto(AnalysisRunRecord r) {
        return new AnalysisRunResponse(
                r.getId(),
                r.getJobRoleId(),
                r.getStatus(),
                r.getTriggeredAt(),
                r.getCompletedAt(),
                r.getErrorMessage(),
                r.getPromptTokens(),
                r.getCompletionTokens(),
                r.getEstimatedCostEur());
    }

    private CandidateResultResponse toResultDto(AnalysisRecord a) {
        return new CandidateResultResponse(
                a.getCandidateId(),
                a.getRank() == null ? 0 : a.getRank(),
                a.getOverallScore(),
                a.getExtractedName(),
                a.getExtractedEmail(),
                parseJsonArray(a.getStrengths()),
                parseJsonArray(a.getWeaknesses()),
                a.getSummary(),
                a.getRecommendation());
    }

    private List<String> parseJsonArray(JSONB jsonb) {
        if (jsonb == null) {
            return List.of();
        }
        try {
            return objectMapper.readValue(jsonb.data(), new TypeReference<List<String>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }
}
