package dev.jbringb.resume_scope.api;

import dev.jbringb.resume_scope.api.dto.AnalysisRunResponse;
import dev.jbringb.resume_scope.api.dto.JobRoleResultsResponse;
import dev.jbringb.resume_scope.service.AnalysisEventBus;
import dev.jbringb.resume_scope.service.AnalysisService;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/analysis-runs")
@RequiredArgsConstructor
public class AnalysisRunController {

    private static final Set<String> TERMINAL_STATUSES = Set.of("COMPLETED", "FAILED");

    private final AnalysisService analysisSvc;
    private final AnalysisEventBus eventBus;

    @GetMapping("/{runId}")
    public Mono<AnalysisRunResponse> getRun(@PathVariable UUID runId) {
        return analysisSvc.getRun(runId);
    }

    @GetMapping("/{runId}/results")
    public Mono<JobRoleResultsResponse> results(@PathVariable UUID runId) {
        return analysisSvc.resultsForRun(runId);
    }

    /**
     * Live run status via Server-Sent Events: emits the current state immediately, then each status
     * change as the background worker advances the run, and completes when the run is COMPLETED/FAILED.
     */
    @GetMapping(value = "/{runId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<AnalysisRunResponse>> events(@PathVariable UUID runId) {
        Flux<AnalysisRunResponse> live = eventBus.stream().filter(e -> e.id().equals(runId));
        return analysisSvc
                .getRun(runId)
                .concatWith(live)
                .takeUntil(e -> TERMINAL_STATUSES.contains(e.status()))
                .map(e -> ServerSentEvent.builder(e).event("run-status").build());
    }
}
