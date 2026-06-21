package dev.jbringb.resume_scope;

import dev.jbringb.resume_scope.repository.AnalysisRepository;
import dev.jbringb.resume_scope.repository.AnalysisRunRepository;
import dev.jbringb.resume_scope.repository.AnalysisTriggerIdempotencyRepository;
import dev.jbringb.resume_scope.repository.CandidateRepository;
import dev.jbringb.resume_scope.repository.JobRoleRepository;
import dev.jbringb.resume_scope.service.AnalysisTriggerIdempotencyLock;
import dev.jbringb.resume_scope.service.CvAnalyzerService;
import io.r2dbc.spi.ConnectionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest
@ActiveProfiles("test")
class ResumeScopeApplicationTests {

    @MockitoBean
    JobRoleRepository jobRoleRepo;

    @MockitoBean
    CandidateRepository candidateRepo;

    @MockitoBean
    AnalysisRunRepository analysisRunRepo;

    @MockitoBean
    AnalysisRepository analysisRepo;

    @MockitoBean
    CvAnalyzerService cvAnalyzerSvc;

    @MockitoBean
    AnalysisTriggerIdempotencyRepository analysisTriggerIdempotencyRepo;

    @MockitoBean
    AnalysisTriggerIdempotencyLock analysisTriggerIdempotencyLock;

    // R2DBC auto-config is excluded in the test profile; supply the ConnectionFactory that the
    // jOOQ DSLContext bean depends on (DSL.using never opens a connection on it).
    @MockitoBean
    ConnectionFactory connectionFactory;

    @Test
    void contextLoads() {}
}
