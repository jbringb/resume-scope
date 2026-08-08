package dev.jbringb.resume_scope.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record JobRoleRequest(
        @NotBlank @Size(max = 255) String title, @NotBlank String description, @NotBlank String requirements) {}
