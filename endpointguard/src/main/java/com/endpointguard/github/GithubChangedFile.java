package com.endpointguard.github;

public record GithubChangedFile(
        String filename,
        int additions,
        int deletions,
        String patch
) {}
