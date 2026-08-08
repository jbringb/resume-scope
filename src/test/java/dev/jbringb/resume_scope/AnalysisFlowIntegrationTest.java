package dev.jbringb.resume_scope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.jbringb.resume_scope.api.dto.AnalysisRunResponse;
import dev.jbringb.resume_scope.api.dto.CandidateResponse;
import dev.jbringb.resume_scope.api.dto.JobRoleResponse;
import dev.jbringb.resume_scope.api.dto.TriggerAnalysisResponse;
import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.BodyInserters;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

/**
 * End-to-end reactive flow against a real Postgres: Flyway migrates over JDBC, jOOQ queries run over
 * R2DBC, the LLM is stubbed, and the run is observed to completion through the SSE endpoint.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient(timeout = "PT30S")
@Testcontainers
class AnalysisFlowIntegrationTest {

    private static final Set<String> TERMINAL = Set.of("COMPLETED", "FAILED");

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("resumescope")
            .withUsername("resumescope")
            .withPassword("resumescope");

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        // Flyway (its own JDBC connection)
        registry.add("spring.flyway.url", postgres::getJdbcUrl);
        registry.add("spring.flyway.user", postgres::getUsername);
        registry.add("spring.flyway.password", postgres::getPassword);
        // jOOQ runtime (R2DBC)
        registry.add("spring.r2dbc.url", () -> "r2dbc:postgresql://%s:%d/%s"
                .formatted(postgres.getHost(), postgres.getFirstMappedPort(), postgres.getDatabaseName()));
        registry.add("spring.r2dbc.username", postgres::getUsername);
        registry.add("spring.r2dbc.password", postgres::getPassword);
    }

    @MockitoBean
    ChatClient chatClient;

    @Autowired
    WebTestClient web;

    @BeforeEach
    void stubLlm() {
        var spec = mock(ChatClient.ChatClientRequestSpec.class);
        var call = mock(ChatClient.CallResponseSpec.class);
        when(chatClient.prompt()).thenReturn(spec);
        when(spec.user(anyString())).thenReturn(spec);
        when(spec.call()).thenReturn(call);
        var reply =
                """
                {"overallScore":88,"strengths":["Java"],"weaknesses":[],"summary":"Strong","recommendation":"Hire","extractedName":"Alice","extractedEmail":"alice@example.com"}
                """;
        when(call.chatResponse())
                .thenReturn(new ChatResponse(java.util.List.of(new Generation(new AssistantMessage(reply)))));
    }

    @Test
    void fullFlow_streamsRunToCompletion() throws Exception {
        UUID roleId = createJobRole();
        uploadPdf(roleId);
        UUID runId = triggerAnalysis(roleId);

        Flux<AnalysisRunResponse> events = web.get()
                .uri("/api/analysis-runs/{runId}/events", runId)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .exchange()
                .expectStatus()
                .isOk()
                .returnResult(AnalysisRunResponse.class)
                .getResponseBody();

        StepVerifier.create(events)
                .thenConsumeWhile(e -> !TERMINAL.contains(e.status()))
                .assertNext(e -> assertThat(e.status()).isEqualTo("COMPLETED"))
                .thenCancel()
                .verify(Duration.ofSeconds(30));

        // The completed run exposes the ranked, LLM-scored result.
        web.get()
                .uri("/api/analysis-runs/{runId}/results", runId)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.runStatus")
                .isEqualTo("COMPLETED")
                .jsonPath("$.results[0].overallScore")
                .isEqualTo(88)
                .jsonPath("$.results[0].rank")
                .isEqualTo(1)
                // Round-trips the real (non-mocked) Jackson 3 ObjectMapper through a real Postgres
                // JSONB column: CvAnalyzerService.jsonArray writes this list, AnalysisService.
                // parseJsonArray reads it back — regression guard for the Jackson 2->3 migration.
                .jsonPath("$.results[0].strengths[0]")
                .isEqualTo("Java")
                .jsonPath("$.results[0].weaknesses")
                .isEqualTo(java.util.List.of());
    }

    @Test
    void validationFailure_returnsProblemJsonWithFieldErrors() {
        web.post()
                .uri("/api/job-roles")
                .bodyValue(new java.util.LinkedHashMap<>() {
                    {
                        put("title", "  "); // blank — violates @NotBlank
                        put("description", "x");
                        put("requirements", "y");
                    }
                })
                .exchange()
                .expectStatus()
                .isBadRequest()
                .expectHeader()
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.title")
                .isEqualTo("Validation failed")
                .jsonPath("$.status")
                .isEqualTo(400)
                .jsonPath("$.errors[0].field")
                .isEqualTo("title");
    }

    @Test
    void deleteJobRole_returnsNoContent() {
        UUID roleId = createJobRole();

        web.delete()
                .uri("/api/job-roles/{id}", roleId)
                .exchange()
                .expectStatus()
                .isNoContent();

        web.get().uri("/api/job-roles/{id}", roleId).exchange().expectStatus().isNotFound();
    }

    @Test
    void deleteCandidate_returnsNoContent() throws Exception {
        UUID roleId = createJobRole();
        uploadPdf(roleId);
        UUID candidateId = web.get()
                .uri("/api/job-roles/{id}/candidates", roleId)
                .exchange()
                .expectBodyList(CandidateResponse.class)
                .returnResult()
                .getResponseBody()
                .get(0)
                .id();

        web.delete()
                .uri("/api/job-roles/{roleId}/candidates/{candidateId}", roleId, candidateId)
                .exchange()
                .expectStatus()
                .isNoContent();
    }

    @Test
    void createJobRole_missingDescriptionOrRequirements_returnsProblemJsonWithFieldErrors() {
        web.post()
                .uri("/api/job-roles")
                .bodyValue(new java.util.LinkedHashMap<>() {
                    {
                        put("title", "Backend Engineer");
                        put("description", "");
                        put("requirements", "  ");
                    }
                })
                .exchange()
                .expectStatus()
                .isBadRequest()
                .expectHeader()
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.errors[?(@.field=='description')]")
                .exists()
                .jsonPath("$.errors[?(@.field=='requirements')]")
                .exists();
    }

    @Test
    void unknownRole_returnsProblemJsonNotFound() {
        web.get()
                .uri("/api/job-roles/{id}", UUID.randomUUID())
                .exchange()
                .expectStatus()
                .isNotFound()
                .expectHeader()
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.status")
                .isEqualTo(404)
                .jsonPath("$.detail")
                .isEqualTo("Job role not found");
    }

    private UUID createJobRole() {
        JobRoleResponse role = web.post()
                .uri("/api/job-roles")
                .bodyValue(new java.util.LinkedHashMap<>() {
                    {
                        put("title", "Backend Engineer");
                        put("description", "Build APIs");
                        put("requirements", "Java, Spring, Postgres");
                    }
                })
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(JobRoleResponse.class)
                .returnResult()
                .getResponseBody();
        assertThat(role).isNotNull();
        return role.id();
    }

    private void uploadPdf(UUID roleId) throws Exception {
        var builder = new MultipartBodyBuilder();
        builder.part("files", new ByteArrayResource(samplePdf()) {
                    @Override
                    public String getFilename() {
                        return "alice.pdf";
                    }
                })
                .contentType(MediaType.APPLICATION_PDF);

        web.post()
                .uri("/api/job-roles/{id}/candidates", roleId)
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(builder.build()))
                .exchange()
                .expectStatus()
                .isOk();
    }

    private UUID triggerAnalysis(UUID roleId) {
        TriggerAnalysisResponse trigger = web.post()
                .uri("/api/job-roles/{id}/analyze", roleId)
                .exchange()
                .expectStatus()
                .isEqualTo(202)
                .expectBody(TriggerAnalysisResponse.class)
                .returnResult()
                .getResponseBody();
        assertThat(trigger).isNotNull();
        return trigger.runId();
    }

    private static byte[] samplePdf() throws Exception {
        try (var doc = new PDDocument();
                var out = new ByteArrayOutputStream()) {
            doc.addPage(new PDPage());
            doc.save(out);
            return out.toByteArray();
        }
    }
}
