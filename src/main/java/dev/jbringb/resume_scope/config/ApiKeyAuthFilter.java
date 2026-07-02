package dev.jbringb.resume_scope.config;

import dev.jbringb.resume_scope.repository.ApiKeyRepository;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Guards the data/AI endpoints ({@code /api/**}) against API keys resolved from the {@code api_key}
 * table (see {@code ApiKeySeeder} for how the legacy shared-secret env var seeds the first row).
 * Keys are looked up by a SHA-256 hash, never stored or compared in plaintext. When no key is
 * configured at all (empty table), the filter is inert so local runs and tests stay open.
 * Non-{@code /api/} paths (e.g. {@code /health}, {@code /openapi.json}) are always allowed.
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@RequiredArgsConstructor
public class ApiKeyAuthFilter implements WebFilter {

    // The resolved caller's api_key.id, stashed for downstream handlers (e.g. per-key usage/budget).
    public static final String API_KEY_ID_ATTRIBUTE = "apiKeyId";

    private static final String HEADER = "X-API-Key";
    private static final String PROTECTED_PREFIX = "/api/";

    private final ApiKeyRepository apiKeyRepo;

    // Reads the api_key.id this filter resolved for the request, or null when auth is disabled.
    public static UUID resolvedApiKeyId(ServerWebExchange exchange) {
        return exchange.getAttribute(API_KEY_ID_ATTRIBUTE);
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (!exchange.getRequest().getPath().value().startsWith(PROTECTED_PREFIX)) {
            return chain.filter(exchange);
        }
        var provided = exchange.getRequest().getHeaders().getFirst(HEADER);
        if (StringUtils.hasText(provided)) {
            return apiKeyRepo
                    .findActiveByHash(ApiKeyHashing.sha256Hex(provided))
                    .flatMap(key -> {
                        exchange.getAttributes().put(API_KEY_ID_ATTRIBUTE, key.getId());
                        return chain.filter(exchange);
                    })
                    .switchIfEmpty(Mono.defer(() -> unauthorized(exchange)));
        }
        // No header: only pass through if auth is effectively disabled (no keys configured at all).
        return apiKeyRepo
                .hasAnyActiveKey()
                .flatMap(anyConfigured -> anyConfigured ? unauthorized(exchange) : chain.filter(exchange));
    }

    // Runs before the dispatcher, so the @RestControllerAdvice can't reach it — emit an RFC 7807
    // problem+json body here directly so every error path stays consistent.
    private Mono<Void> unauthorized(ServerWebExchange exchange) {
        var response = exchange.getResponse();
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        response.getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);
        byte[] body = ("{\"type\":\"https://resume-scope.dev/errors/unauthorized\",\"title\":\"Unauthorized\","
                        + "\"status\":401,\"detail\":\"Missing or invalid API key.\"}")
                .getBytes(StandardCharsets.UTF_8);
        return response.writeWith(Mono.just(response.bufferFactory().wrap(body)));
    }
}
