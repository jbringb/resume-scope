package dev.jbringb.resume_scope.api.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

public record CandidateResponse(UUID id, UUID jobRoleId, String originalFilename, OffsetDateTime createdAt) {}
