package dev.jbringb.resume_scope.service;

import dev.jbringb.resume_scope.api.dto.AnalysisRunResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

@Slf4j
@Service
public class AnalysisEventBus {

    // autoCancel=false is required: the no-arg onBackpressureBuffer() defaults to autoCancel=true,
    // which permanently terminates the sink the first time its subscriber count returns to zero
    // (e.g. every time an SSE client disconnects after takeUntil(terminal) completes it — the normal
    // happy path). Once terminated, every later subscriber gets onComplete() with zero events, with
    // no error and no way to recover short of restarting the app. A bounded buffer (256) still caps
    // memory if events are ever published with nobody subscribed.
    private final Sinks.Many<AnalysisRunResponse> sink =
            Sinks.many().multicast().onBackpressureBuffer(256, false);

    public void publish(AnalysisRunResponse event) {
        var result = sink.tryEmitNext(event);
        if (result.isFailure()) {
            log.warn("Failed to publish analysis run event {}: {}", event.id(), result);
        }
    }

    public Flux<AnalysisRunResponse> stream() {
        return sink.asFlux();
    }
}
