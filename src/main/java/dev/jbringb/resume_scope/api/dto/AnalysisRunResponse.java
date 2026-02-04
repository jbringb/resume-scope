package dev.jbringb.resume_scope.api.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

public record AnalysisRunResponse(
        UUID id,
        UUID jobRoleId,
        String status,
        OffsetDateTime triggeredAt,
        OffsetDateTime completedAt,
        String errorMessage) {}
