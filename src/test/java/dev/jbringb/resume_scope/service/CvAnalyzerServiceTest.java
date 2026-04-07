package dev.jbringb.resume_scope.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.jbringb.resume_scope.db.generated.tables.records.AnalysisRecord;
import dev.jbringb.resume_scope.db.generated.tables.records.AnalysisRunRecord;
import dev.jbringb.resume_scope.db.generated.tables.records.CandidateRecord;
import dev.jbringb.resume_scope.db.generated.tables.records.JobRoleRecord;
import dev.jbringb.resume_scope.repository.AnalysisRepository;
import dev.jbringb.resume_scope.repository.AnalysisRunRepository;
import dev.jbringb.resume_scope.repository.CandidateRepository;
import dev.jbringb.resume_scope.repository.JobRoleRepository;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;

@ExtendWith(MockitoExtension.class)
class CvAnalyzerServiceTest {

    @Mock
    ChatClient chatClient;

    @Mock
    JobRoleRepository jobRoleRepo;

    @Mock
    CandidateRepository candidateRepo;

    @Mock
    AnalysisRunRepository analysisRunRepo;

    @Mock
    AnalysisRepository analysisRepo;

    @Mock
    AnalysisEventBus eventBus;

    CvAnalyzerService cvAnalyzerSvc;

    @BeforeEach
    void setUp() {
        cvAnalyzerSvc = new CvAnalyzerService(
                chatClient, new ObjectMapper(), jobRoleRepo, candidateRepo, analysisRunRepo, analysisRepo, eventBus);
    }

    @Test
    void processAnalysisRunAsync_whenRunNotFound_doesNothing() {
        var runId = UUID.randomUUID();
        when(analysisRunRepo.findById(runId)).thenReturn(Optional.empty());

        cvAnalyzerSvc.processAnalysisRunAsync(runId);

        verifyNoInteractions(jobRoleRepo, candidateRepo, analysisRepo, chatClient);
    }

    @Test
    void processAnalysisRunAsync_whenRoleNotFound_marksRunFailed() {
        var runId = UUID.randomUUID();
        var jobRoleId = UUID.randomUUID();
        when(analysisRunRepo.findById(runId)).thenReturn(Optional.of(pendingRun(runId, jobRoleId)));
        when(jobRoleRepo.findById(jobRoleId)).thenReturn(Optional.empty());

        cvAnalyzerSvc.processAnalysisRunAsync(runId);

        verify(analysisRunRepo)
                .updateStatus(eq(runId), eq("FAILED"), any(OffsetDateTime.class), eq("Job role not found"));
        verify(candidateRepo, never()).findByJobRoleId(any());
    }

    @Test
    void processAnalysisRunAsync_assignsRanksOneBasedByScoreDescending() {
        var runId = UUID.randomUUID();
        var jobRoleId = UUID.randomUUID();
        when(analysisRunRepo.findById(runId)).thenReturn(Optional.of(pendingRun(runId, jobRoleId)));
        when(jobRoleRepo.findById(jobRoleId)).thenReturn(Optional.of(jobRole(jobRoleId, "Engineer")));

        var cand1 = candidate();
        var cand2 = candidate();
        when(candidateRepo.findByJobRoleId(jobRoleId)).thenReturn(List.of(cand1, cand2));

        stubLlmResponse(cvJson(90, "Alice", "alice@example.com"), cvJson(70, "Bob", "bob@example.com"));

        var aliceAnalysisId = UUID.randomUUID();
        var bobAnalysisId = UUID.randomUUID();
        when(analysisRepo.insert(any(), any(), anyInt(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(aliceAnalysisId, bobAnalysisId);

        // score-ordered return: alice (90) -> rank 1, bob (70) -> rank 2
        when(analysisRepo.findByAnalysisRunIdOrderByScoreDesc(runId))
                .thenReturn(List.of(analysisRecord(aliceAnalysisId), analysisRecord(bobAnalysisId)));

        cvAnalyzerSvc.processAnalysisRunAsync(runId);

        var order = inOrder(analysisRepo);
        order.verify(analysisRepo).updateRank(aliceAnalysisId, 1);
        order.verify(analysisRepo).updateRank(bobAnalysisId, 2);
        verify(analysisRunRepo).updateStatus(eq(runId), eq("COMPLETED"), any(OffsetDateTime.class), isNull());
    }

    @Test
    void processAnalysisRunAsync_clampsScoreAboveHundred() {
        var runId = UUID.randomUUID();
        var jobRoleId = UUID.randomUUID();
        when(analysisRunRepo.findById(runId)).thenReturn(Optional.of(pendingRun(runId, jobRoleId)));
        when(jobRoleRepo.findById(jobRoleId)).thenReturn(Optional.of(jobRole(jobRoleId, "Engineer")));
        when(candidateRepo.findByJobRoleId(jobRoleId)).thenReturn(List.of(candidate()));

        stubLlmResponse(cvJson(150, "Alice", null));

        var analysisId = UUID.randomUUID();
        when(analysisRepo.insert(any(), any(), anyInt(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(analysisId);
        when(analysisRepo.findByAnalysisRunIdOrderByScoreDesc(runId)).thenReturn(List.of(analysisRecord(analysisId)));

        cvAnalyzerSvc.processAnalysisRunAsync(runId);

        // score 150 must be clamped to 100 before insertion
        verify(analysisRepo).insert(any(), any(), eq(100), any(), any(), any(), any(), any(), any(), any());
    }

    private void stubLlmResponse(String first, String... rest) {
        var spec = mock(ChatClient.ChatClientRequestSpec.class);
        var call = mock(ChatClient.CallResponseSpec.class);
        when(chatClient.prompt()).thenReturn(spec);
        when(spec.user(anyString())).thenReturn(spec);
        when(spec.call()).thenReturn(call);
        when(call.content()).thenReturn(first, rest);
    }

    private static String cvJson(int score, String name, String email) {
        String nameVal = name != null ? "\"" + name + "\"" : "null";
        String emailVal = email != null ? "\"" + email + "\"" : "null";
        return """
                {"overallScore":%d,"strengths":["Java"],"weaknesses":[],"summary":"Good","recommendation":"Hire","extractedName":%s,"extractedEmail":%s}
                """
                .formatted(score, nameVal, emailVal);
    }

    private static AnalysisRunRecord pendingRun(UUID id, UUID jobRoleId) {
        var r = new AnalysisRunRecord();
        r.setId(id);
        r.setJobRoleId(jobRoleId);
        r.setStatus("PENDING");
        r.setTriggeredAt(OffsetDateTime.now());
        return r;
    }

    private static JobRoleRecord jobRole(UUID id, String title) {
        var r = new JobRoleRecord();
        r.setId(id);
        r.setTitle(title);
        return r;
    }

    private static CandidateRecord candidate() {
        var r = new CandidateRecord();
        r.setId(UUID.randomUUID());
        r.setOriginalFilename("cv.pdf");
        return r;
    }

    private static AnalysisRecord analysisRecord(UUID id) {
        var r = new AnalysisRecord();
        r.setId(id);
        return r;
    }
}
