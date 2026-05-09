package dev.jbringb.resume_scope.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.jbringb.resume_scope.api.dto.AnalysisRunResponse;
import dev.jbringb.resume_scope.api.dto.CandidateResultResponse;
import dev.jbringb.resume_scope.api.dto.JobRoleResultsResponse;
import dev.jbringb.resume_scope.api.dto.TriggerAnalysisResponse;
import dev.jbringb.resume_scope.db.generated.tables.records.AnalysisRecord;
import dev.jbringb.resume_scope.db.generated.tables.records.AnalysisRunRecord;
import dev.jbringb.resume_scope.repository.AnalysisRepository;
import dev.jbringb.resume_scope.repository.AnalysisRunRepository;
import dev.jbringb.resume_scope.repository.AnalysisTriggerIdempotencyRepository;
import dev.jbringb.resume_scope.repository.CandidateRepository;
import dev.jbringb.resume_scope.repository.JobRoleRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jooq.JSONB;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

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

    @Transactional
    public TriggerAnalysisResponse triggerAnalysis(UUID jobRoleId, Optional<String> idempotencyKeyHeader) {
        log.info("Triggering analysis for job role {}", jobRoleId);
        if (jobRoleRepo.findById(jobRoleId).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Job role not found");
        }
        if (candidateRepo.findByJobRoleId(jobRoleId).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No candidates to analyze");
        }

        var key = normalizeIdempotencyKey(idempotencyKeyHeader);
        if (key.isEmpty()) {
            return insertRunAndSchedule(jobRoleId);
        }

        return analysisTriggerIdempotencyLock.withJobRoleKeyLock(jobRoleId, key.get(), () -> {
            var existing = analysisTriggerIdempotencyRepo.findAnalysisRunId(jobRoleId, key.get());
            if (existing.isPresent()) {
                log.info("Idempotent replay for job role {} → run {}", jobRoleId, existing.get());
                return new TriggerAnalysisResponse(existing.get());
            }
            var response = insertRunAndSchedule(jobRoleId);
            analysisTriggerIdempotencyRepo.insert(jobRoleId, key.get(), response.runId());
            return response;
        });
    }

    private TriggerAnalysisResponse insertRunAndSchedule(UUID jobRoleId) {
        var run = analysisRunRepo.insertPending(jobRoleId);
        log.info("Analysis run inserted: {}", run.getId());
        cvAnalyzerSvc.processAnalysisRunAsync(run.getId());
        return new TriggerAnalysisResponse(run.getId());
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

    public List<AnalysisRunResponse> listRuns(UUID jobRoleId) {
        if (jobRoleRepo.findById(jobRoleId).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Job role not found");
        }
        return analysisRunRepo.findByJobRoleIdOrderByTriggeredDesc(jobRoleId).stream()
                .map(this::toRunDto)
                .toList();
    }

    public JobRoleResultsResponse latestResultsForJobRole(UUID jobRoleId) {
        if (jobRoleRepo.findById(jobRoleId).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Job role not found");
        }
        var run = analysisRunRepo
                .findLatestCompletedByJobRoleId(jobRoleId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No completed analysis"));
        return mapRunResults(jobRoleId, run);
    }

    public AnalysisRunResponse getRun(UUID runId) {
        return analysisRunRepo
                .findById(runId)
                .map(this::toRunDto)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Analysis run not found"));
    }

    public JobRoleResultsResponse resultsForRun(UUID runId) {
        var run = analysisRunRepo
                .findById(runId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Analysis run not found"));
        return mapRunResults(run.getJobRoleId(), run);
    }

    private JobRoleResultsResponse mapRunResults(UUID jobRoleId, AnalysisRunRecord run) {
        var results = analysisRepo.findByAnalysisRunId(run.getId()).stream()
                .map(this::toResultDto)
                .toList();
        return new JobRoleResultsResponse(jobRoleId, run.getId(), run.getStatus(), results);
    }

    private AnalysisRunResponse toRunDto(AnalysisRunRecord r) {
        return new AnalysisRunResponse(
                r.getId(),
                r.getJobRoleId(),
                r.getStatus(),
                r.getTriggeredAt(),
                r.getCompletedAt(),
                r.getErrorMessage());
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
