package com.endpointguard.github;

public record GithubRepositoryMetadata(
        String fullName,
        String ownerLogin,
        String name,
        String description,
        boolean isPrivate,
        String defaultBranch
) {
    public GithubRepositoryMetadata(String fullName, boolean isPrivate, String description) {
        this(fullName, null, null, description, isPrivate, null);
    }
}
