package dev.jbringb.resume_scope.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.jbringb.resume_scope.db.generated.tables.records.ApiKeyRecord;
import dev.jbringb.resume_scope.repository.ApiKeyRepository;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

class ApiKeyAuthFilterTest {

    private final ApiKeyRepository apiKeyRepo = mock(ApiKeyRepository.class);
    private final ApiKeyAuthFilter filter = new ApiKeyAuthFilter(apiKeyRepo);

    private static WebFilterChain recording(AtomicBoolean passedThrough) {
        return exchange -> {
            passedThrough.set(true);
            return Mono.empty();
        };
    }

    @Test
    void inertWhenNoKeysConfigured() {
        when(apiKeyRepo.hasAnyActiveKey()).thenReturn(Mono.just(false));
        var passed = new AtomicBoolean(false);
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/job-roles"));

        filter.filter(exchange, recording(passed)).block();

        assertThat(passed).isTrue();
    }

    @Test
    void rejectsMissingKeyWhenKeysAreConfigured() {
        when(apiKeyRepo.hasAnyActiveKey()).thenReturn(Mono.just(true));
        var passed = new AtomicBoolean(false);
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/job-roles"));

        filter.filter(exchange, recording(passed)).block();

        assertThat(passed).isFalse();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(exchange.getResponse().getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
    }

    @Test
    void rejectsWrongKey() {
        when(apiKeyRepo.findActiveByHash(anyString())).thenReturn(Mono.empty());
        var passed = new AtomicBoolean(false);
        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/job-roles").header("X-API-Key", "nope"));

        filter.filter(exchange, recording(passed)).block();

        assertThat(passed).isFalse();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void allowsCorrectKeyAndStashesResolvedApiKeyId() {
        var keyId = UUID.randomUUID();
        var record = new ApiKeyRecord();
        record.setId(keyId);
        when(apiKeyRepo.findActiveByHash(anyString())).thenReturn(Mono.just(record));
        var passed = new AtomicBoolean(false);
        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/job-roles").header("X-API-Key", "secret"));

        filter.filter(exchange, recording(passed)).block();

        assertThat(passed).isTrue();
        assertThat(ApiKeyAuthFilter.resolvedApiKeyId(exchange)).isEqualTo(keyId);
    }

    @Test
    void leavesNonApiPathsOpen() {
        var passed = new AtomicBoolean(false);
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/health"));

        filter.filter(exchange, recording(passed)).block();

        assertThat(passed).isTrue();
    }
}
