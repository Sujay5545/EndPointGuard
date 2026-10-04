package com.endpointguard.metrics.service;

import com.endpointguard.common.config.AppProperties;
import com.endpointguard.common.time.UtcDateTime;
import com.endpointguard.endpoint.domain.Endpoint;
import com.endpointguard.endpoint.repository.EndpointRepository;
import com.endpointguard.metrics.domain.TrafficMetric;
import com.endpointguard.metrics.dto.DemoApiStatsResponse;
import com.endpointguard.metrics.repository.TrafficMetricRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class MetricsPollerService {

    private final TrafficMetricRepository trafficMetricRepository;
    private final EndpointRepository endpointRepository;
    private final AppProperties appProperties;
    private final RestTemplate restTemplate;

    @Scheduled(fixedDelayString = "${app.metrics.poll-interval-ms:60000}")
    public void pollDemoApiMetrics() {
        String url = appProperties.getMetrics().getDemoApiBaseUrl() + "/actuator/endpoint-stats";
        try {
            DemoApiStatsResponse stats = restTemplate.getForObject(url, DemoApiStatsResponse.class);
            if (stats == null || stats.endpoints() == null) {
                log.debug("No stats returned from demo API");
                return;
            }
            LocalDateTime bucketStart = UtcDateTime.now().truncatedTo(ChronoUnit.MINUTES);
            processStats(stats, bucketStart);
        } catch (Exception e) {
            log.warn("Failed to poll demo API metrics: {}", e.getMessage());
        }
    }

    @Transactional
    public void processStats(DemoApiStatsResponse stats, LocalDateTime bucketStart) {
        for (DemoApiStatsResponse.EndpointStat stat : stats.endpoints()) {
            if (stat == null || stat.endpointKey() == null || stat.endpointKey().isBlank()) {
                continue;
            }
            if (stat.requestCount() == null || stat.requestCount() == 0) {
                continue;
            }

            String normalizedKey = stat.endpointKey().trim();
            String[] parts = normalizedKey.split(":", 2);
            if (parts.length < 2) {
                continue;
            }

            String method = parts[0].trim();
            String path = parts[1].trim();
            if (method.isBlank() || path.isBlank()) {
                continue;
            }

            endpointRepository.findByMethodIgnoreCaseAndPathPattern(method, path).forEach(endpoint ->
                    persistMetric(endpoint, bucketStart, stat));
        }
    }

    private void persistMetric(Endpoint endpoint, LocalDateTime bucketStart, DemoApiStatsResponse.EndpointStat stat) {
        try {
            TrafficMetric metric = TrafficMetric.builder()
                .endpoint(endpoint)
                .bucketStart(bucketStart)
                .requestCount(stat.requestCount())
                .error4xxCount(stat.error4xxCount() != null ? stat.error4xxCount() : 0)
                .error5xxCount(stat.error5xxCount() != null ? stat.error5xxCount() : 0)
                .avgLatencyMs(stat.avgLatencyMs() != null ? stat.avgLatencyMs() : 0)
                .rateLimitUtilizationPct(stat.rateLimitUtilizationPct() != null ? stat.rateLimitUtilizationPct() : 0)
                .build();
            trafficMetricRepository.save(metric);
            log.debug("Saved metrics for endpoint {}: {} requests", endpoint.getPathPattern(), stat.requestCount());
        } catch (DataIntegrityViolationException e) {
            // Duplicate bucket — safe to ignore (idempotent)
            log.debug("Duplicate metric bucket for endpoint {} at {}", endpoint.getId(), bucketStart);
        }
    }
}
