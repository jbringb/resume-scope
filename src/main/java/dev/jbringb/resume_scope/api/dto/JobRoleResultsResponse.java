package dev.jbringb.resume_scope.api.dto;

import java.util.List;
import java.util.UUID;

public record JobRoleResultsResponse(
        UUID jobRoleId, UUID runId, String runStatus, List<CandidateResultResponse> results) {}
