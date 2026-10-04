package com.endpointguard.metrics.dto;

import com.endpointguard.metrics.domain.TrafficMetric;

import java.time.LocalDateTime;

public record TrafficMetricResponse(
    Long id,
    Long endpointId,
    LocalDateTime bucketStart,
    Long requestCount,
    Long error4xxCount,
    Long error5xxCount,
    Double avgLatencyMs,
    Double rateLimitUtilizationPct,
    LocalDateTime collectedAt
) {
    public static TrafficMetricResponse from(TrafficMetric m) {
        return new TrafficMetricResponse(
            m.getId(), m.getEndpoint().getId(), m.getBucketStart(),
            m.getRequestCount(), m.getError4xxCount(), m.getError5xxCount(),
            m.getAvgLatencyMs(), m.getRateLimitUtilizationPct(), m.getCollectedAt()
        );
    }
}
