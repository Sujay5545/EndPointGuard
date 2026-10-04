package com.endpointguard.metrics.service;

import com.endpointguard.common.time.UtcDateTime;
import com.endpointguard.metrics.repository.TrafficMetricRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Provides aggregated metric summaries used by the Risk Engine.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MetricsAggregationService {

    private final TrafficMetricRepository trafficMetricRepository;

    /**
     * Returns the average requests per minute over the last N minutes.
     */
    @Transactional(readOnly = true)
    public double getAvgRequestsPerMinute(Long endpointId, int lookbackMinutes) {
        LocalDateTime to = UtcDateTime.now();
        LocalDateTime from = to.minusMinutes(lookbackMinutes);
        Long total = trafficMetricRepository.sumRequestCount(endpointId, from, to);
        return total != null ? (double) total / lookbackMinutes : 0.0;
    }

    /**
     * Returns the recent error rate (5xx / total) over the last N minutes.
     */
    @Transactional(readOnly = true)
    public double getRecentErrorRate(Long endpointId, int lookbackMinutes) {
        LocalDateTime to = UtcDateTime.now();
        LocalDateTime from = to.minusMinutes(lookbackMinutes);
        Double rate = trafficMetricRepository.computeAvgErrorRate(endpointId, from, to);
        return rate != null ? rate : 0.0;
    }

    /**
     * Returns baseline error rate over the last 24 hours (excluding recent window).
     */
    @Transactional(readOnly = true)
    public double getBaselineErrorRate(Long endpointId) {
        LocalDateTime to = UtcDateTime.now().minusHours(1);
        LocalDateTime from = to.minusHours(23);
        Double rate = trafficMetricRepository.computeAvgErrorRate(endpointId, from, to);
        return rate != null ? rate : 0.0;
    }

    /**
     * Returns the average rate-limit utilization over the last N minutes.
     */
    @Transactional(readOnly = true)
    public double getAvgRateLimitUtilization(Long endpointId, int lookbackMinutes) {
        LocalDateTime to = UtcDateTime.now();
        LocalDateTime from = to.minusMinutes(lookbackMinutes);
        Double pct = trafficMetricRepository.computeAvgRateLimitUtilization(endpointId, from, to);
        return pct != null ? pct : 0.0;
    }

    /**
     * Returns the average latency over the last N minutes.
     */
    @Transactional(readOnly = true)
    public double getAvgLatency(Long endpointId, int lookbackMinutes) {
        LocalDateTime to = UtcDateTime.now();
        LocalDateTime from = to.minusMinutes(lookbackMinutes);
        Double latency = trafficMetricRepository.computeAvgLatency(endpointId, from, to);
        return latency != null ? latency : 0.0;
    }

    /**
     * Returns baseline latency over the last 24 hours.
     */
    @Transactional(readOnly = true)
    public double getBaselineLatency(Long endpointId) {
        LocalDateTime to = UtcDateTime.now().minusHours(1);
        LocalDateTime from = to.minusHours(23);
        Double latency = trafficMetricRepository.computeAvgLatency(endpointId, from, to);
        return latency != null ? latency : 0.0;
    }
}
