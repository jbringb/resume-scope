package dev.jbringb.resume_scope.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.jbringb.resume_scope.db.generated.tables.records.AnalysisRunRecord;
import dev.jbringb.resume_scope.db.generated.tables.records.CandidateRecord;
import dev.jbringb.resume_scope.db.generated.tables.records.JobRoleRecord;
import dev.jbringb.resume_scope.repository.AnalysisRepository;
import dev.jbringb.resume_scope.repository.AnalysisRunRepository;
import dev.jbringb.resume_scope.repository.AnalysisRunRepository.UsageTotals;
import dev.jbringb.resume_scope.repository.AnalysisTriggerIdempotencyRepository;
import dev.jbringb.resume_scope.repository.CandidateRepository;
import dev.jbringb.resume_scope.repository.JobRoleRepository;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@ExtendWith(MockitoExtension.class)
class AnalysisServiceTest {

    @Mock
    JobRoleRepository jobRoleRepo;

    @Mock
    CandidateRepository candidateRepo;

    @Mock
    AnalysisRunRepository analysisRunRepo;

    @Mock
    AnalysisRepository analysisRepo;

    @Mock
    CvAnalyzerService cvAnalyzerSvc;

    @Mock
    ObjectMapper objectMapper;

    @Mock
    AnalysisTriggerIdempotencyRepository analysisTriggerIdempotencyRepo;

    @Mock
    AnalysisTriggerIdempotencyLock analysisTriggerIdempotencyLock;

    @InjectMocks
    AnalysisService analysisSvc;

    @BeforeEach
    void stubCommon() {
        ReflectionTestUtils.setField(analysisSvc, "monthlyBudgetEur", new BigDecimal("5.00"));
        // Lock is a passthrough: run the supplied action Mono directly.
        lenient()
                .when(analysisTriggerIdempotencyLock.withJobRoleKeyLock(any(), any(), any()))
                .thenAnswer(inv -> inv.getArgument(2));
        // Background processing is fire-and-forget; return an empty Mono so dispatch is a no-op.
        lenient().when(cvAnalyzerSvc.processAnalysisRun(any())).thenReturn(Mono.empty());
        // Under budget by default; individual tests override to exercise the 429 path.
        lenient()
                .when(analysisRunRepo.sumUsageSince(any()))
                .thenReturn(Mono.just(new UsageTotals(0, 0, BigDecimal.ZERO)));
    }

    @Test
    void triggerAnalysis_whenRoleNotFound_throws404() {
        var id = UUID.randomUUID();
        when(jobRoleRepo.findById(id)).thenReturn(Mono.empty());

        StepVerifier.create(analysisSvc.triggerAnalysis(id, Optional.empty()))
                .expectErrorSatisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.NOT_FOUND))
                .verify();
    }

    @Test
    void triggerAnalysis_whenNoCandidates_throws400() {
        var id = UUID.randomUUID();
        when(jobRoleRepo.findById(id)).thenReturn(Mono.just(new JobRoleRecord()));
        when(candidateRepo.findByJobRoleId(id)).thenReturn(Flux.empty());

        StepVerifier.create(analysisSvc.triggerAnalysis(id, Optional.empty()))
                .expectErrorSatisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST))
                .verify();
    }

    @Test
    void triggerAnalysis_createsRunAndSchedulesProcessing() {
        var jobRoleId = UUID.randomUUID();
        var runId = UUID.randomUUID();
        when(jobRoleRepo.findById(jobRoleId)).thenReturn(Mono.just(new JobRoleRecord()));
        when(candidateRepo.findByJobRoleId(jobRoleId)).thenReturn(Flux.just(new CandidateRecord()));

        var run = new AnalysisRunRecord();
        run.setId(runId);
        when(analysisRunRepo.insertPending(jobRoleId)).thenReturn(Mono.just(run));

        StepVerifier.create(analysisSvc.triggerAnalysis(jobRoleId, Optional.empty()))
                .assertNext(response -> assertThat(response.runId()).isEqualTo(runId))
                .verifyComplete();

        verify(cvAnalyzerSvc).processAnalysisRun(runId);
    }

    @Test
    void triggerAnalysis_whenBlankIdempotencyKey_throws400() {
        var id = UUID.randomUUID();
        when(jobRoleRepo.findById(id)).thenReturn(Mono.just(new JobRoleRecord()));
        when(candidateRepo.findByJobRoleId(id)).thenReturn(Flux.just(new CandidateRecord()));

        StepVerifier.create(analysisSvc.triggerAnalysis(id, Optional.of("   ")))
                .expectErrorSatisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST))
                .verify();
        verify(analysisTriggerIdempotencyLock, never()).withJobRoleKeyLock(any(), any(), any());
    }

    @Test
    void triggerAnalysis_whenIdempotencyKeyTooLong_throws400() {
        var id = UUID.randomUUID();
        when(jobRoleRepo.findById(id)).thenReturn(Mono.just(new JobRoleRecord()));
        when(candidateRepo.findByJobRoleId(id)).thenReturn(Flux.just(new CandidateRecord()));

        StepVerifier.create(analysisSvc.triggerAnalysis(id, Optional.of("x".repeat(256))))
                .expectErrorSatisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST))
                .verify();
    }

    @Test
    void triggerAnalysis_sameIdempotencyKey_returnsExistingRunWithoutDuplicateSchedule() {
        var jobRoleId = UUID.randomUUID();
        var runId = UUID.randomUUID();
        var key = "idem-1";
        when(jobRoleRepo.findById(jobRoleId)).thenReturn(Mono.just(new JobRoleRecord()));
        when(candidateRepo.findByJobRoleId(jobRoleId)).thenReturn(Flux.just(new CandidateRecord()));
        when(analysisTriggerIdempotencyRepo.findAnalysisRunId(jobRoleId, key))
                .thenReturn(Mono.empty())
                .thenReturn(Mono.just(runId));

        var run = new AnalysisRunRecord();
        run.setId(runId);
        run.setStatus("PENDING");
        run.setTriggeredAt(OffsetDateTime.now());
        when(analysisRunRepo.insertPending(jobRoleId)).thenReturn(Mono.just(run));
        // Replay only happens while the existing run is still active (recent, non-terminal).
        when(analysisRunRepo.findById(runId)).thenReturn(Mono.just(run));
        when(analysisTriggerIdempotencyRepo.insert(jobRoleId, key, runId)).thenReturn(Mono.empty());

        var first = analysisSvc.triggerAnalysis(jobRoleId, Optional.of(key)).block();
        var second = analysisSvc.triggerAnalysis(jobRoleId, Optional.of(key)).block();

        assertThat(first.runId()).isEqualTo(runId);
        assertThat(second.runId()).isEqualTo(runId);
        verify(analysisRunRepo, times(1)).insertPending(jobRoleId);
        verify(cvAnalyzerSvc, times(1)).processAnalysisRun(runId);
        verify(analysisTriggerIdempotencyRepo).insert(jobRoleId, key, runId);
    }

    @Test
    void triggerAnalysis_whenExistingRunExpired_startsNewRunAndRepoints() {
        var jobRoleId = UUID.randomUUID();
        var oldRunId = UUID.randomUUID();
        var newRunId = UUID.randomUUID();
        var key = "idem-expired";
        when(jobRoleRepo.findById(jobRoleId)).thenReturn(Mono.just(new JobRoleRecord()));
        when(candidateRepo.findByJobRoleId(jobRoleId)).thenReturn(Flux.just(new CandidateRecord()));
        when(analysisTriggerIdempotencyRepo.findAnalysisRunId(jobRoleId, key)).thenReturn(Mono.just(oldRunId));

        var staleRun = new AnalysisRunRecord();
        staleRun.setId(oldRunId);
        staleRun.setStatus("PENDING");
        staleRun.setTriggeredAt(OffsetDateTime.now().minusMinutes(20));
        when(analysisRunRepo.findById(oldRunId)).thenReturn(Mono.just(staleRun));
        when(analysisRunRepo.updateStatus(eq(oldRunId), eq("FAILED"), any(), anyString()))
                .thenReturn(Mono.empty());

        var newRun = new AnalysisRunRecord();
        newRun.setId(newRunId);
        when(analysisRunRepo.insertPending(jobRoleId)).thenReturn(Mono.just(newRun));
        when(analysisTriggerIdempotencyRepo.repoint(jobRoleId, key, newRunId)).thenReturn(Mono.empty());

        StepVerifier.create(analysisSvc.triggerAnalysis(jobRoleId, Optional.of(key)))
                .assertNext(response -> assertThat(response.runId()).isEqualTo(newRunId))
                .verifyComplete();

        verify(analysisRunRepo).updateStatus(eq(oldRunId), eq("FAILED"), any(OffsetDateTime.class), anyString());
        verify(analysisTriggerIdempotencyRepo).repoint(jobRoleId, key, newRunId);
        verify(cvAnalyzerSvc).processAnalysisRun(newRunId);
        verify(analysisTriggerIdempotencyRepo, never()).insert(any(), any(), any());
    }

    @Test
    void getRun_whenStale_marksFailed() {
        var runId = UUID.randomUUID();
        var staleRun = new AnalysisRunRecord();
        staleRun.setId(runId);
        staleRun.setJobRoleId(UUID.randomUUID());
        staleRun.setStatus("PENDING");
        staleRun.setTriggeredAt(OffsetDateTime.now().minusMinutes(20));
        when(analysisRunRepo.findById(runId)).thenReturn(Mono.just(staleRun));
        when(analysisRunRepo.updateStatus(eq(runId), eq("FAILED"), any(), anyString()))
                .thenReturn(Mono.empty());

        StepVerifier.create(analysisSvc.getRun(runId))
                .assertNext(response -> assertThat(response.status()).isEqualTo("FAILED"))
                .verifyComplete();

        verify(analysisRunRepo).updateStatus(eq(runId), eq("FAILED"), any(OffsetDateTime.class), anyString());
    }

    @Test
    void listRuns_whenRoleNotFound_throws404() {
        var id = UUID.randomUUID();
        when(jobRoleRepo.findById(id)).thenReturn(Mono.empty());

        StepVerifier.create(analysisSvc.listRuns(id))
                .expectErrorSatisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.NOT_FOUND))
                .verify();
    }

    @Test
    void latestResults_whenNoCompletedRun_throws404() {
        var id = UUID.randomUUID();
        when(jobRoleRepo.findById(id)).thenReturn(Mono.just(new JobRoleRecord()));
        when(analysisRunRepo.findLatestCompletedByJobRoleId(id)).thenReturn(Mono.empty());

        StepVerifier.create(analysisSvc.latestResultsForJobRole(id))
                .expectErrorSatisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.NOT_FOUND))
                .verify();
    }

    @Test
    void triggerAnalysis_whenMonthlyBudgetExceeded_throws429() {
        var id = UUID.randomUUID();
        when(jobRoleRepo.findById(id)).thenReturn(Mono.just(new JobRoleRecord()));
        when(candidateRepo.findByJobRoleId(id)).thenReturn(Flux.just(new CandidateRecord()));
        when(analysisRunRepo.sumUsageSince(any()))
                .thenReturn(Mono.just(new UsageTotals(1000, 1000, new BigDecimal("5.00"))));

        StepVerifier.create(analysisSvc.triggerAnalysis(id, Optional.empty()))
                .expectErrorSatisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.TOO_MANY_REQUESTS))
                .verify();
        verify(analysisRunRepo, never()).insertPending(any());
    }

    @Test
    void monthlyUsage_returnsAggregatedTotalsAndBudgetStatus() {
        when(analysisRunRepo.sumUsageSince(any()))
                .thenReturn(Mono.just(new UsageTotals(1000, 500, new BigDecimal("2.50"))));

        StepVerifier.create(analysisSvc.monthlyUsage())
                .assertNext(usage -> {
                    assertThat(usage.promptTokens()).isEqualTo(1000);
                    assertThat(usage.completionTokens()).isEqualTo(500);
                    assertThat(usage.totalTokens()).isEqualTo(1500);
                    assertThat(usage.estimatedCostEur()).isEqualByComparingTo("2.50");
                    assertThat(usage.monthlyBudgetEur()).isEqualByComparingTo("5.00");
                    assertThat(usage.budgetExceeded()).isFalse();
                })
                .verifyComplete();
    }

    @Test
    void getRun_whenNotFound_throws404() {
        var id = UUID.randomUUID();
        when(analysisRunRepo.findById(id)).thenReturn(Mono.empty());

        StepVerifier.create(analysisSvc.getRun(id))
                .expectErrorSatisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.NOT_FOUND))
                .verify();
    }
}
