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
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

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

    public Mono<Void> processAnalysisRun(UUID runId) {
        return analysisRunRepo
                .findById(runId)
                .switchIfEmpty(Mono.<AnalysisRunRecord>fromRunnable(() ->
                        log.warn("Analysis run {} not found — skipping (trigger transaction not committed?)", runId)))
                .flatMap(run -> runAnalysis(runId, run.getJobRoleId()))
                .onErrorResume(e -> {
                    log.error("Analysis run {} failed", runId, e);
                    return failRun(
                            runId,
                            e.getMessage() != null
                                    ? e.getMessage()
                                    : e.getClass().getSimpleName());
                });
    }

    private Mono<Void> runAnalysis(UUID runId, UUID jobRoleId) {
        return jobRoleRepo
                .findById(jobRoleId)
                .switchIfEmpty(
                        Mono.defer(() -> failRun(runId, "Job role not found").then(Mono.empty())))
                .flatMap(role -> analysisRunRepo
                        .updateStatusOnly(runId, "RUNNING")
                        .then(publishRun(runId))
                        .thenMany(candidateRepo.findByJobRoleId(jobRoleId))
                        .concatMap(candidate -> analyzeAndInsert(role, candidate, runId))
                        .then(applyRanks(runId))
                        .then(analysisRunRepo.updateStatus(runId, "COMPLETED", OffsetDateTime.now(), null))
                        .then(publishRun(runId)));
    }

    private Mono<Void> analyzeAndInsert(JobRoleRecord role, CandidateRecord candidate, UUID runId) {
        var cvText = candidate.getCvText() == null ? "" : candidate.getCvText();
        return analyzeWithLlm(role, candidate, cvText)
                .flatMap(ai -> {
                    var score = Math.clamp(ai.overallScore(), 0, 100);
                    return analysisRepo.insert(
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
                })
                .then();
    }

    private Mono<Void> failRun(UUID runId, String message) {
        return analysisRunRepo
                .updateStatus(runId, "FAILED", OffsetDateTime.now(), message)
                .then(publishRun(runId));
    }

    private Mono<Void> publishRun(UUID runId) {
        return analysisRunRepo
                .findById(runId)
                .doOnNext(r -> eventBus.publish(toRunDto(r)))
                .then();
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

    // Re-rank 1..N by descending score, preserving the score-ordered emission order.
    private Mono<Void> applyRanks(UUID runId) {
        return analysisRepo
                .findByAnalysisRunIdOrderByScoreDesc(runId)
                .index()
                .concatMap(indexed -> analysisRepo.updateRank(indexed.getT2().getId(), (int) (indexed.getT1() + 1)))
                .then();
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

    // The OpenAI call is blocking (Spring AI has no reactive ChatClient.call), so offload it to the
    // bounded-elastic scheduler — a legitimate use, unlike wrapping the now-reactive DB calls.
    private Mono<CvAnalysisResult> analyzeWithLlm(JobRoleRecord role, CandidateRecord candidate, String cvText) {
        return Mono.fromCallable(() -> {
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
                })
                .subscribeOn(Schedulers.boundedElastic());
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
