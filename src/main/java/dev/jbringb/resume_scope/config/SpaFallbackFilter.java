package dev.jbringb.resume_scope.config;

import java.util.List;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Serves the Angular app (built into {@code static/}) for its client-side routes. A browser that opens
 * or reloads a deep link like {@code /job-roles/123} asks the server for that path, which has no file,
 * so it gets {@code index.html} and the Angular router takes over. API, health and actuator paths and
 * anything that looks like a file ({@code main-ABC.js}, {@code /openapi.json}) pass through untouched,
 * so unknown API routes still 404 and assets are served as files.
 */
@Component
public class SpaFallbackFilter implements WebFilter {

    private static final String INDEX = "/index.html";
    private static final List<String> SERVER_PREFIXES = List.of("/api/", "/actuator/");
    private static final List<String> SERVER_PATHS = List.of("/api", "/actuator", "/health");

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        var request = exchange.getRequest();
        if (request.getMethod() != HttpMethod.GET
                || !isClientRoute(request.getPath().value())) {
            return chain.filter(exchange);
        }
        return chain.filter(exchange.mutate().request(r -> r.path(INDEX)).build());
    }

    static boolean isClientRoute(String path) {
        if (SERVER_PATHS.contains(path) || SERVER_PREFIXES.stream().anyMatch(path::startsWith)) {
            return false;
        }
        var lastSegment = path.substring(path.lastIndexOf('/') + 1);
        return !lastSegment.contains(".");
    }
}
