package dev.jbringb.resume_scope.api;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * Unauthenticated liveness endpoint for platform health checks (Render, etc.).
 * Lives outside {@code /api/} so it is never gated by {@link dev.jbringb.resume_scope.config.ApiKeyAuthFilter}.
 */
@RestController
public class HealthController {

    @GetMapping("/health")
    public Mono<Map<String, String>> health() {
        return Mono.just(Map.of("status", "UP"));
    }
}
