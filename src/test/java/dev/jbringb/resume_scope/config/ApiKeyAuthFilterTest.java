package dev.jbringb.resume_scope.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

class ApiKeyAuthFilterTest {

    private static WebFilterChain recording(AtomicBoolean passedThrough) {
        return exchange -> {
            passedThrough.set(true);
            return Mono.empty();
        };
    }

    @Test
    void inertWhenNoKeyConfigured() {
        var passed = new AtomicBoolean(false);
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/job-roles"));

        new ApiKeyAuthFilter("").filter(exchange, recording(passed)).block();

        assertThat(passed).isTrue();
    }

    @Test
    void rejectsMissingKey() {
        var passed = new AtomicBoolean(false);
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/job-roles"));

        new ApiKeyAuthFilter("secret").filter(exchange, recording(passed)).block();

        assertThat(passed).isFalse();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(exchange.getResponse().getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
    }

    @Test
    void rejectsWrongKey() {
        var passed = new AtomicBoolean(false);
        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/job-roles").header("X-API-Key", "nope"));

        new ApiKeyAuthFilter("secret").filter(exchange, recording(passed)).block();

        assertThat(passed).isFalse();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void allowsCorrectKey() {
        var passed = new AtomicBoolean(false);
        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/job-roles").header("X-API-Key", "secret"));

        new ApiKeyAuthFilter("secret").filter(exchange, recording(passed)).block();

        assertThat(passed).isTrue();
    }

    @Test
    void leavesNonApiPathsOpen() {
        var passed = new AtomicBoolean(false);
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/health"));

        new ApiKeyAuthFilter("secret").filter(exchange, recording(passed)).block();

        assertThat(passed).isTrue();
    }
}
