package com.endpointguard.risk.service;

import com.endpointguard.audit.service.AuditLogService;
import com.endpointguard.common.config.AppProperties;
import com.endpointguard.endpoint.repository.EndpointRepository;
import com.endpointguard.metrics.domain.TrafficMetric;
import com.endpointguard.metrics.repository.TrafficMetricRepository;
import com.endpointguard.praffected.repository.PrAffectedEndpointRepository;
import com.endpointguard.project.service.ProjectService;
import com.endpointguard.pullrequest.repository.PullRequestRepository;
import com.endpointguard.risk.domain.PostMergeMonitoring;
import com.endpointguard.risk.repository.PostMergeMonitoringRepository;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PostMergeMonitoringServiceTests {

    private final LocalDateTime mergedAt = LocalDateTime.parse("2026-01-01T12:00:00");

    @Test
    void marksWindowInconclusiveWhenEitherTrafficSampleIsInsufficient() {
        assertVerdict(5, 100, 0, 0, "INCONCLUSIVE");
        assertVerdict(100, 5, 0, 0, "INCONCLUSIVE");
    }

    @Test
    void identifiesImprovedAndDegradedWindows() {
        assertVerdict(100, 100, 10, 1, "IMPROVED");
        assertVerdict(100, 100, 1, 12, "DEGRADED");
    }

    @Test
    void identifiesNoChangeWithinComparisonTolerance() {
        assertVerdict(100, 100, 5, 5, "NO_CHANGE");
    }

    private void assertVerdict(long baselineCount, long observedCount, long baselineErrors,
            long observedErrors, String expectedVerdict) {
        PostMergeMonitoring record = PostMergeMonitoring.builder()
                .id(1L)
                .pullRequestId(2L)
                .endpointId(3L)
                .windowStart(mergedAt)
                .windowEnd(mergedAt.plusHours(2))
                .verdict("PENDING")
                .build();
        PostMergeMonitoringRepository monitoringRepository = mock(PostMergeMonitoringRepository.class);
        TrafficMetricRepository metricRepository = mock(TrafficMetricRepository.class);
        PullRequestRepository pullRequestRepository = mock(PullRequestRepository.class);
        when(monitoringRepository.findByVerdictAndWindowEndLessThanEqual("PENDING", record.getWindowEnd()))
                .thenReturn(List.of(record));
        when(metricRepository.findByEndpointIdAndBucketStartBetweenOrderByBucketStartAsc(eq(3L), any(), any()))
                .thenAnswer(invocation -> {
                    LocalDateTime from = invocation.getArgument(1);
                    if (from.isBefore(mergedAt)) {
                        return List.of(metric(mergedAt.minusMinutes(30), baselineCount, baselineErrors, 200.0));
                    }
                    return List.of(metric(mergedAt.plusMinutes(30), observedCount, observedErrors, 200.0));
                });
        when(pullRequestRepository.findById(2L)).thenReturn(Optional.empty());

        AppProperties properties = new AppProperties();
        properties.getMonitoring().setWindowHours(2);
        properties.getMonitoring().setMinSampleCount(10);
        PostMergeMonitoringService service = new PostMergeMonitoringService(
                monitoringRepository, mock(PrAffectedEndpointRepository.class), metricRepository,
                properties, pullRequestRepository, mock(EndpointRepository.class),
                mock(ProjectService.class), mock(AuditLogService.class));

        service.evaluateDueWindows(record.getWindowEnd());

        assertThat(record.getVerdict()).isEqualTo(expectedVerdict);
        assertThat(record.getBaselineRequestCount()).isEqualTo(baselineCount);
        assertThat(record.getObservedRequestCount()).isEqualTo(observedCount);
    }

    private static TrafficMetric metric(LocalDateTime bucket, long requests, long errors, double latency) {
        return TrafficMetric.builder()
                .bucketStart(bucket)
                .requestCount(requests)
                .error5xxCount(errors)
                .avgLatencyMs(latency)
                .build();
    }
}