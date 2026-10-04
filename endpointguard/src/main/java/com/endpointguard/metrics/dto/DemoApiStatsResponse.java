package com.endpointguard.metrics.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record DemoApiStatsResponse(
    String timestamp,
    Integer windowSeconds,
    List<EndpointStat> endpoints
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record EndpointStat(
        String endpointKey,
        Long requestCount,
        Long error4xxCount,
        Long error5xxCount,
        Double avgLatencyMs,
        Double rateLimitUtilizationPct
    ) {}
}
