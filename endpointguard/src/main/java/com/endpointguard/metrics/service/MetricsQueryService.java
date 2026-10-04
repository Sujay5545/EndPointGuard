package com.endpointguard.metrics.service;

import com.endpointguard.common.exception.ResourceNotFoundException;
import com.endpointguard.endpoint.repository.EndpointRepository;
import com.endpointguard.metrics.domain.TrafficMetric;
import com.endpointguard.metrics.repository.TrafficMetricRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class MetricsQueryService {

    private final TrafficMetricRepository trafficMetricRepository;
    private final EndpointRepository endpointRepository;

    @Transactional(readOnly = true)
        public List<TrafficMetric> getMetrics(Long endpointId, LocalDateTime from, LocalDateTime to, String ownerEmail) {
            endpointRepository.findOwnedById(endpointId, ownerEmail)
                .orElseThrow(() -> new ResourceNotFoundException("Endpoint", endpointId));
        return trafficMetricRepository.findByEndpointIdAndBucketStartBetweenOrderByBucketStartAsc(
                endpointId, from, to);
    }

    @Transactional(readOnly = true)
    public Double getAverageErrorRate(Long endpointId, LocalDateTime from, LocalDateTime to) {
        Double rate = trafficMetricRepository.computeAvgErrorRate(endpointId, from, to);
        return rate != null ? rate : 0.0;
    }

    @Transactional(readOnly = true)
    public Double getAverageLatency(Long endpointId, LocalDateTime from, LocalDateTime to) {
        Double latency = trafficMetricRepository.computeAvgLatency(endpointId, from, to);
        return latency != null ? latency : 0.0;
    }

    @Transactional(readOnly = true)
    public Long getTotalRequestCount(Long endpointId, LocalDateTime from, LocalDateTime to) {
        Long count = trafficMetricRepository.sumRequestCount(endpointId, from, to);
        return count != null ? count : 0L;
    }

    @Transactional(readOnly = true)
    public Double getAverageRateLimitUtilization(Long endpointId, LocalDateTime from, LocalDateTime to) {
        Double pct = trafficMetricRepository.computeAvgRateLimitUtilization(endpointId, from, to);
        return pct != null ? pct : 0.0;
    }
}
