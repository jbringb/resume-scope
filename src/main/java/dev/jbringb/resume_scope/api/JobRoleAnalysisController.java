package dev.jbringb.resume_scope.api;

import dev.jbringb.resume_scope.api.dto.AnalysisRunResponse;
import dev.jbringb.resume_scope.api.dto.JobRoleResultsResponse;
import dev.jbringb.resume_scope.api.dto.TriggerAnalysisResponse;
import dev.jbringb.resume_scope.service.AnalysisService;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@RestController
@RequestMapping("/api/job-roles/{jobRoleId}")
@RequiredArgsConstructor
public class JobRoleAnalysisController {

    private final AnalysisService analysisSvc;

    @PostMapping("/analyze")
    public Mono<ResponseEntity<TriggerAnalysisResponse>> analyze(
            @PathVariable UUID jobRoleId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return Mono.fromCallable(() -> ResponseEntity.status(HttpStatus.ACCEPTED)
                        .body(analysisSvc.triggerAnalysis(jobRoleId, Optional.ofNullable(idempotencyKey))))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/analysis-runs")
    public Mono<List<AnalysisRunResponse>> listRuns(@PathVariable UUID jobRoleId) {
        return Mono.fromCallable(() -> analysisSvc.listRuns(jobRoleId)).subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/results")
    public Mono<JobRoleResultsResponse> latestResults(@PathVariable UUID jobRoleId) {
        return Mono.fromCallable(() -> analysisSvc.latestResultsForJobRole(jobRoleId))
                .subscribeOn(Schedulers.boundedElastic());
    }
}
