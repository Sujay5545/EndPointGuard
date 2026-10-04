package com.endpointguard.risk.dto;

import java.time.LocalDateTime;

public record PostMergeMonitoringResponse(
        Long id,
        Long pullRequestId,
        Integer githubPrNumber,
        String repositoryName,
        Long endpointId,
        String endpointMethod,
        String endpointPath,
        LocalDateTime windowStart,
        LocalDateTime windowEnd,
        Long baselineRequestCount,
        Long observedRequestCount,
        Double baselineErrorRate,
        Double observedErrorRate,
        Double baselineLatencyMs,
        Double observedLatencyMs,
        String verdict,
        LocalDateTime evaluatedAt) {
}