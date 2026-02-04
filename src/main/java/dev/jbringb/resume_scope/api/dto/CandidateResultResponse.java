package dev.jbringb.resume_scope.api.dto;

import java.util.List;
import java.util.UUID;

public record CandidateResultResponse(
        UUID candidateId,
        int rank,
        int overallScore,
        String extractedName,
        String extractedEmail,
        List<String> strengths,
        List<String> weaknesses,
        String summary,
        String recommendation) {}
