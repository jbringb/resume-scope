package dev.jbringb.resume_scope.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpMethod;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

class SpaFallbackFilterTest {

    private final SpaFallbackFilter filter = new SpaFallbackFilter();

    @ParameterizedTest(name = "{0} {1} -> {2}")
    @CsvSource({
        // Angular client routes, including deep links and the root, get index.html
        "GET, /, /index.html",
        "GET, /job-roles, /index.html",
        "GET, /job-roles/3f2a/results, /index.html",
        // server endpoints are never rewritten, so unknown API routes still 404
        "GET, /api/job-roles, /api/job-roles",
        "GET, /api, /api",
        "GET, /api/does-not-exist, /api/does-not-exist",
        "GET, /health, /health",
        "GET, /actuator/prometheus, /actuator/prometheus",
        // files are served as files
        "GET, /openapi.json, /openapi.json",
        "GET, /main-4L6KQWNR.js, /main-4L6KQWNR.js",
        "GET, /media/roboto-latin-400-normal.woff2, /media/roboto-latin-400-normal.woff2",
        // only page loads (GET) are rewritten
        "POST, /job-roles, /job-roles",
    })
    void rewritesOnlyClientRoutes(String method, String path, String expectedPath) {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.method(HttpMethod.valueOf(method), path));
        var seenPath = new AtomicReference<String>();

        filter.filter(exchange, ex -> {
                    seenPath.set(ex.getRequest().getPath().value());
                    return Mono.empty();
                })
                .block();

        assertThat(seenPath.get()).isEqualTo(expectedPath);
    }
}
