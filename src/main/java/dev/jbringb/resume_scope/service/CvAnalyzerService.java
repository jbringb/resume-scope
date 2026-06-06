package dev.jbringb.resume_scope.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.jbringb.resume_scope.api.dto.AnalysisRunResponse;
import dev.jbringb.resume_scope.db.generated.tables.records.AnalysisRunRecord;
import dev.jbringb.resume_scope.db.generated.tables.records.CandidateRecord;
import dev.jbringb.resume_scope.db.generated.tables.records.JobRoleRecord;
import dev.jbringb.resume_scope.repository.AnalysisRepository;
import dev.jbringb.resume_scope.repository.AnalysisRunRepository;
import dev.jbringb.resume_scope.repository.CandidateRepository;
import dev.jbringb.resume_scope.repository.JobRoleRepository;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jooq.JSONB;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class CvAnalyzerService {

    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;
    private final JobRoleRepository jobRoleRepo;
    private final CandidateRepository candidateRepo;
    private final AnalysisRunRepository analysisRunRepo;
    private final AnalysisRepository analysisRepo;
    private final AnalysisEventBus eventBus;

    @Async
    public void processAnalysisRunAsync(UUID runId) {
        try {
            var run = analysisRunRepo.findById(runId).orElse(null);
            if (run == null) {
                log.warn("Analysis run {} not found — skipping (trigger transaction not committed?)", runId);
                return;
            }
            log.info("Processing analysis run {}", runId);
            var jobRoleId = run.getJobRoleId();
            var role = jobRoleRepo.findById(jobRoleId).orElse(null);
            if (role == null) {
                failRun(runId, "Job role not found");
                return;
            }
            analysisRunRepo.updateStatusOnly(runId, "RUNNING");
            publishRun(runId);
            var candidates = candidateRepo.findByJobRoleId(jobRoleId);
            for (var candidate : candidates) {
                var cvText = candidate.getCvText() == null ? "" : candidate.getCvText();
                log.info("CV text: {}", cvText);
                var ai = analyzeWithLlm(role, candidate, cvText);
                var score = Math.clamp(ai.overallScore(), 0, 100);
                analysisRepo.insert(
                        candidate.getId(),
                        runId,
                        score,
                        null,
                        jsonArray(ai.strengths()),
                        jsonArray(ai.weaknesses()),
                        ai.summary(),
                        ai.recommendation(),
                        emptyToNull(ai.extractedName()),
                        emptyToNull(ai.extractedEmail()));
            }
            applyRanks(runId);
            analysisRunRepo.updateStatus(runId, "COMPLETED", OffsetDateTime.now(), null);
            publishRun(runId);
        } catch (Exception e) {
            log.error("Analysis run {} failed", runId, e);
            failRun(
                    runId,
                    e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
        }
    }

    private void failRun(UUID runId, String message) {
        analysisRunRepo.updateStatus(runId, "FAILED", OffsetDateTime.now(), message);
        publishRun(runId);
    }

    private void publishRun(UUID runId) {
        analysisRunRepo.findById(runId).map(this::toRunDto).ifPresent(eventBus::publish);
    }

    private AnalysisRunResponse toRunDto(AnalysisRunRecord r) {
        return new AnalysisRunResponse(
                r.getId(),
                r.getJobRoleId(),
                r.getStatus(),
                r.getTriggeredAt(),
                r.getCompletedAt(),
                r.getErrorMessage());
    }

    private void applyRanks(UUID runId) {
        var ordered = analysisRepo.findByAnalysisRunIdOrderByScoreDesc(runId);
        int rank = 1;
        for (var row : ordered) {
            analysisRepo.updateRank(row.getId(), rank++);
        }
    }

    private JSONB jsonArray(List<String> items) {
        try {
            return JSONB.valueOf(objectMapper.writeValueAsString(items == null ? List.of() : items));
        } catch (Exception e) {
            return JSONB.valueOf("[]");
        }
    }

    private static String emptyToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }

    private CvAnalysisResult analyzeWithLlm(JobRoleRecord role, CandidateRecord candidate, String cvText)
            throws Exception {
        String prompt = buildUserPrompt(role, candidate, cvText);
        var raw = chatClient.prompt().user(prompt).call().content();
        var json = stripMarkdownFences(raw);
        var parsed = objectMapper.readValue(json, CvAnalysisResult.class);
        return new CvAnalysisResult(
                parsed.overallScore(),
                parsed.strengths() == null ? List.of() : parsed.strengths(),
                parsed.weaknesses() == null ? List.of() : parsed.weaknesses(),
                parsed.summary() == null ? "" : parsed.summary(),
                parsed.recommendation() == null ? "" : parsed.recommendation(),
                parsed.extractedName(),
                parsed.extractedEmail());
    }

    private static String buildUserPrompt(JobRoleRecord role, CandidateRecord candidate, String cvText) {
        return """
                You evaluate a CV against a job role. Reply with JSON only, no markdown, matching this shape:
                {"overallScore":0-100,"strengths":["string"],"weaknesses":["string"],"summary":"string","recommendation":"string","extractedName":"string or null","extractedEmail":"string or null"}

                Job title: %s
                Job description: %s
                Job requirements: %s

                Candidate file name: %s
                CV text:
                %s
                """
                .formatted(
                        role.getTitle(),
                        role.getDescription() == null ? "" : role.getDescription(),
                        role.getRequirements() == null ? "" : role.getRequirements(),
                        candidate.getOriginalFilename(),
                        cvText.isBlank() ? "(empty - score low and explain in weaknesses)" : cvText);
    }

    private static String stripMarkdownFences(String raw) {
        String s = raw.trim();
        if (s.startsWith("```")) {
            int nl = s.indexOf('\n');
            if (nl > 0) {
                s = s.substring(nl + 1);
            }
            int end = s.lastIndexOf("```");
            if (end > 0) {
                s = s.substring(0, end);
            }
        }
        return s.trim();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record CvAnalysisResult(
            int overallScore,
            List<String> strengths,
            List<String> weaknesses,
            String summary,
            String recommendation,
            String extractedName,
            String extractedEmail) {}
}
