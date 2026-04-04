package dev.jbringb.resume_scope.api;

import dev.jbringb.resume_scope.api.dto.AnalysisRunResponse;
import dev.jbringb.resume_scope.api.dto.JobRoleResultsResponse;
import dev.jbringb.resume_scope.service.AnalysisService;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@RestController
@RequestMapping("/api/analysis-runs")
@RequiredArgsConstructor
public class AnalysisRunController {

    private final AnalysisService analysisSvc;

    @GetMapping("/{runId}")
    public Mono<AnalysisRunResponse> getRun(@PathVariable UUID runId) {
        return Mono.fromCallable(() -> analysisSvc.getRun(runId)).subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/{runId}/results")
    public Mono<JobRoleResultsResponse> results(@PathVariable UUID runId) {
        return Mono.fromCallable(() -> analysisSvc.resultsForRun(runId)).subscribeOn(Schedulers.boundedElastic());
    }
}
