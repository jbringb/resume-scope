package dev.jbringb.resume_scope.api;

import dev.jbringb.resume_scope.api.dto.MonthlyUsageResponse;
import dev.jbringb.resume_scope.config.ApiKeyAuthFilter;
import dev.jbringb.resume_scope.service.AnalysisService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/usage")
@RequiredArgsConstructor
public class UsageController {

    private final AnalysisService analysisSvc;

    @GetMapping("/monthly")
    public Mono<MonthlyUsageResponse> monthly(ServerWebExchange exchange) {
        return analysisSvc.monthlyUsage(ApiKeyAuthFilter.resolvedApiKeyId(exchange));
    }
}
