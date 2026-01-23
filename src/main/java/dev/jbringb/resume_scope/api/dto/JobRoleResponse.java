package dev.jbringb.resume_scope.api.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

public record JobRoleResponse(
        UUID id, String title, String description, String requirements, OffsetDateTime createdAt) {}
