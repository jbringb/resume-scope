package dev.jbringb.resume_scope.service;

import dev.jbringb.resume_scope.api.dto.AnalysisRunResponse;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

@Service
public class AnalysisEventBus {

    private final Sinks.Many<AnalysisRunResponse> sink =
            Sinks.many().multicast().onBackpressureBuffer();

    public void publish(AnalysisRunResponse event) {
        sink.tryEmitNext(event);
    }

    public Flux<AnalysisRunResponse> stream() {
        return sink.asFlux();
    }
}
