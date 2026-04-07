package dev.jbringb.resume_scope;

import dev.jbringb.resume_scope.repository.AnalysisRepository;
import dev.jbringb.resume_scope.repository.AnalysisRunRepository;
import dev.jbringb.resume_scope.repository.CandidateRepository;
import dev.jbringb.resume_scope.repository.JobRoleRepository;
import dev.jbringb.resume_scope.service.CvAnalyzerService;
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

    @Test
    void contextLoads() {}
}
