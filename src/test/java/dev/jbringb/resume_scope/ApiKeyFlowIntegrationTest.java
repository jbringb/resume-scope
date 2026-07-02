package dev.jbringb.resume_scope;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Exercises the DB-backed API key flow end to end against a real Postgres: the legacy
 * {@code security.api-key} env var is seeded as a row named "default" on startup
 * ({@code ApiKeySeeder}), and {@code ApiKeyAuthFilter} resolves the {@code X-API-Key} header
 * against that row on every {@code /api/**} request.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "security.api-key=test-secret-key")
@AutoConfigureWebTestClient(timeout = "PT30S")
@Testcontainers
class ApiKeyFlowIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("resumescope")
            .withUsername("resumescope")
            .withPassword("resumescope");

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.flyway.url", postgres::getJdbcUrl);
        registry.add("spring.flyway.user", postgres::getUsername);
        registry.add("spring.flyway.password", postgres::getPassword);
        registry.add("spring.r2dbc.url", () -> "r2dbc:postgresql://%s:%d/%s"
                .formatted(postgres.getHost(), postgres.getFirstMappedPort(), postgres.getDatabaseName()));
        registry.add("spring.r2dbc.username", postgres::getUsername);
        registry.add("spring.r2dbc.password", postgres::getPassword);
    }

    @Autowired
    WebTestClient web;

    @Test
    void missingKey_isRejected() {
        web.get().uri("/api/job-roles").exchange().expectStatus().isUnauthorized();
    }

    @Test
    void wrongKey_isRejected() {
        web.get()
                .uri("/api/job-roles")
                .header("X-API-Key", "not-the-configured-key")
                .exchange()
                .expectStatus()
                .isUnauthorized();
    }

    @Test
    void seededDefaultKey_isAcceptedAndScopesUsageByName() {
        web.get()
                .uri("/api/job-roles")
                .header("X-API-Key", "test-secret-key")
                .exchange()
                .expectStatus()
                .isOk();

        // Retry: ApiKeySeeder runs as an ApplicationRunner concurrently with app startup, but this
        // request only proceeds once the context (and the seeder) has fully started, so no race here.
        web.get()
                .uri("/api/usage/monthly")
                .header("X-API-Key", "test-secret-key")
                .exchange()
                .expectStatus()
                .isOk()
                .expectHeader()
                .contentType(MediaType.APPLICATION_JSON)
                .expectBody()
                .jsonPath("$.apiKeyName")
                .isEqualTo("default");
    }

    @Test
    void health_staysOpenRegardlessOfKey() {
        web.get().uri("/health").exchange().expectStatus().isOk();
    }
}
