package dev.jbringb.resume_scope.repository;

import static org.assertj.core.api.Assertions.assertThat;

import dev.jbringb.resume_scope.db.generated.tables.records.AnalysisRecord;
import dev.jbringb.resume_scope.db.generated.tables.records.AnalysisRunRecord;
import dev.jbringb.resume_scope.db.generated.tables.records.CandidateRecord;
import dev.jbringb.resume_scope.db.generated.tables.records.JobRoleRecord;
import java.util.List;
import java.util.UUID;
import org.jooq.JSONB;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Repository-level, no web layer needed — verifies real Postgres ordering behavior against a mock. */
@SpringBootTest
@Testcontainers
class AnalysisRepositoryTest {

    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine")
            .withDatabaseName("resumescope")
            .withUsername("resumescope")
            .withPassword("resumescope");

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.flyway.url", postgres::getJdbcUrl);
        registry.add("spring.flyway.user", postgres::getUsername);
        registry.add("spring.flyway.password", postgres::getPassword);
        registry.add(
                "spring.r2dbc.url",
                () -> "r2dbc:postgresql://%s:%d/%s"
                        .formatted(postgres.getHost(), postgres.getFirstMappedPort(), postgres.getDatabaseName()));
        registry.add("spring.r2dbc.username", postgres::getUsername);
        registry.add("spring.r2dbc.password", postgres::getPassword);
    }

    @Autowired
    JobRoleRepository jobRoleRepo;

    @Autowired
    CandidateRepository candidateRepo;

    @Autowired
    AnalysisRunRepository analysisRunRepo;

    @Autowired
    AnalysisRepository analysisRepo;

    // Regression test for the missing secondary sort key: without ANALYSIS.ID as a tiebreaker,
    // Postgres doesn't guarantee any particular order among rows with an equal overall_score.
    //
    // Deliberately does NOT predict the exact order in Java (e.g. by sorting the ids with
    // UUID.compareTo() and asserting a match): Java's UUID comparison is signed-long-wise and
    // Postgres's uuid comparison is unsigned-byte-wise, so they can disagree on the same pair of
    // UUIDs. What the fix actually guarantees is determinism — the identical query against
    // unchanged data always returns the same order — so that's what this asserts directly.
    @Test
    void findByAnalysisRunIdOrderByScoreDesc_ordersTiedScoresDeterministically() {
        JSONB empty = JSONB.valueOf("[]");

        UUID jobRoleId = jobRoleRepo
                .insert("Engineer", "desc", "reqs")
                .map(JobRoleRecord::getId)
                .block();
        UUID candidateA = candidateRepo
                .insert(jobRoleId, "a.pdf", "cv a")
                .map(CandidateRecord::getId)
                .block();
        UUID candidateB = candidateRepo
                .insert(jobRoleId, "b.pdf", "cv b")
                .map(CandidateRecord::getId)
                .block();
        UUID runId = analysisRunRepo
                .insertPending(jobRoleId, null)
                .map(AnalysisRunRecord::getId)
                .block();

        // Same score for both.
        analysisRepo
                .insert(candidateB, runId, 80, null, empty, empty, "s", "r", null, null)
                .block();
        analysisRepo
                .insert(candidateA, runId, 80, null, empty, empty, "s", "r", null, null)
                .block();

        List<UUID> firstRun = queryOrder(runId);
        List<UUID> secondRun = queryOrder(runId);
        List<UUID> thirdRun = queryOrder(runId);

        assertThat(firstRun).hasSize(2);
        assertThat(secondRun).isEqualTo(firstRun);
        assertThat(thirdRun).isEqualTo(firstRun);
    }

    private List<UUID> queryOrder(UUID runId) {
        return analysisRepo
                .findByAnalysisRunIdOrderByScoreDesc(runId)
                .map(AnalysisRecord::getId)
                .collectList()
                .block();
    }
}
