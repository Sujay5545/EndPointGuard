package com.endpointguard.pullrequest.dto;

import java.util.List;

public record PullRequestPageResponse(
        List<PullRequestResponse> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean hasNext,
        boolean hasPrevious) {
}