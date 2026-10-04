package com.endpointguard.repository.dto;

import com.endpointguard.repository.domain.GithubRepository;

import java.time.LocalDateTime;

public record RepositoryResponse(
        Long id,
        Long projectId,
        String githubRepoFullName,
        String webhookSecretRef,
        LocalDateTime installedAt) {
    public static RepositoryResponse from(GithubRepository repository) {
        return new RepositoryResponse(
                repository.getId(),
                repository.getProject().getId(),
                repository.getGithubRepoFullName(),
                repository.getWebhookSecretRef(),
                repository.getInstalledAt()
        );
    }
}