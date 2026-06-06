package dev.jbringb.resume_scope.config;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Guards the data/AI endpoints ({@code /api/**}) with a shared API key supplied in the
 * {@code X-API-Key} header. When no key is configured the filter is inert, so local runs and
 * tests stay open; deployed environments enable it by setting the {@code API_KEY} env var.
 * Non-{@code /api/} paths (e.g. {@code /health}, {@code /openapi.json}) are always allowed.
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ApiKeyAuthFilter implements WebFilter {

    private static final String HEADER = "X-API-Key";
    private static final String PROTECTED_PREFIX = "/api/";

    private final boolean enabled;
    private final byte[] expectedKey;

    public ApiKeyAuthFilter(@Value("${security.api-key:}") String apiKey) {
        this.enabled = StringUtils.hasText(apiKey);
        this.expectedKey = enabled ? apiKey.getBytes(StandardCharsets.UTF_8) : new byte[0];
        if (!enabled) {
            log.warn("API key auth is DISABLED (security.api-key / API_KEY unset) — /api/** is open.");
        }
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (!enabled || !exchange.getRequest().getPath().value().startsWith(PROTECTED_PREFIX)) {
            return chain.filter(exchange);
        }
        var provided = exchange.getRequest().getHeaders().getFirst(HEADER);
        if (provided != null && MessageDigest.isEqual(expectedKey, provided.getBytes(StandardCharsets.UTF_8))) {
            return chain.filter(exchange);
        }
        exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
        return exchange.getResponse().setComplete();
    }
}
