package com.endpointguard.repository.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record LinkRepositoryRequest(
        @NotBlank
        @Pattern(regexp = "[^/\\s]+/[^/\\s]+", message = "Repository must use owner/name format")
        @Size(max = 255) String githubRepoFullName,
        @NotBlank @Size(max = 255) String webhookSecretRef
) {
}