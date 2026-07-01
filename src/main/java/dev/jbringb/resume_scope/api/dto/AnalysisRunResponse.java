package dev.jbringb.resume_scope.api.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

public record AnalysisRunResponse(
        UUID id,
        UUID jobRoleId,
        String status,
        OffsetDateTime triggeredAt,
        OffsetDateTime completedAt,
        String errorMessage,
        Integer promptTokens,
        Integer completionTokens,
        BigDecimal estimatedCostEur) {}
