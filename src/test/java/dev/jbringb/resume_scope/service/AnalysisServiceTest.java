package dev.jbringb.resume_scope.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.jbringb.resume_scope.db.generated.tables.records.AnalysisRunRecord;
import dev.jbringb.resume_scope.db.generated.tables.records.CandidateRecord;
import dev.jbringb.resume_scope.db.generated.tables.records.JobRoleRecord;
import dev.jbringb.resume_scope.repository.AnalysisRepository;
import dev.jbringb.resume_scope.repository.AnalysisRunRepository;
import dev.jbringb.resume_scope.repository.CandidateRepository;
import dev.jbringb.resume_scope.repository.JobRoleRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class AnalysisServiceTest {

    @Mock
    JobRoleRepository jobRoleRepo;

    @Mock
    CandidateRepository candidateRepo;

    @Mock
    AnalysisRunRepository analysisRunRepo;

    @Mock
    AnalysisRepository analysisRepo;

    @Mock
    CvAnalyzerService cvAnalyzerSvc;

    @Mock
    ObjectMapper objectMapper;

    @InjectMocks
    AnalysisService analysisSvc;

    @Test
    void triggerAnalysis_whenRoleNotFound_throws404() {
        var id = UUID.randomUUID();
        when(jobRoleRepo.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> analysisSvc.triggerAnalysis(id))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void triggerAnalysis_whenNoCandidates_throws400() {
        var id = UUID.randomUUID();
        when(jobRoleRepo.findById(id)).thenReturn(Optional.of(new JobRoleRecord()));
        when(candidateRepo.findByJobRoleId(id)).thenReturn(List.of());

        assertThatThrownBy(() -> analysisSvc.triggerAnalysis(id))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void triggerAnalysis_createsRunAndSchedulesProcessing() {
        var jobRoleId = UUID.randomUUID();
        var runId = UUID.randomUUID();
        when(jobRoleRepo.findById(jobRoleId)).thenReturn(Optional.of(new JobRoleRecord()));
        when(candidateRepo.findByJobRoleId(jobRoleId)).thenReturn(List.of(new CandidateRecord()));

        var run = new AnalysisRunRecord();
        run.setId(runId);
        when(analysisRunRepo.insertPending(jobRoleId)).thenReturn(run);

        var response = analysisSvc.triggerAnalysis(jobRoleId);

        assertThat(response.runId()).isEqualTo(runId);
        verify(cvAnalyzerSvc).processAnalysisRunAsync(runId);
    }

    @Test
    void listRuns_whenRoleNotFound_throws404() {
        var id = UUID.randomUUID();
        when(jobRoleRepo.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> analysisSvc.listRuns(id))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void latestResults_whenNoCompletedRun_throws404() {
        var id = UUID.randomUUID();
        when(jobRoleRepo.findById(id)).thenReturn(Optional.of(new JobRoleRecord()));
        when(analysisRunRepo.findLatestCompletedByJobRoleId(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> analysisSvc.latestResultsForJobRole(id))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void getRun_whenNotFound_throws404() {
        var id = UUID.randomUUID();
        when(analysisRunRepo.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> analysisSvc.getRun(id))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.NOT_FOUND));
    }
}
