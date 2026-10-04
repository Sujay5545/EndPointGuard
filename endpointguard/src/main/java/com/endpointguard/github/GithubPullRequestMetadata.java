package com.endpointguard.github;

public record GithubPullRequestMetadata(
        Integer number,
        String title,
        String state,
        String author,
        String htmlUrl
) {}
