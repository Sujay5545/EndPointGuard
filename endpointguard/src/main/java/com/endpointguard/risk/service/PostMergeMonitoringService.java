package com.endpointguard.risk.service;

import com.endpointguard.audit.service.AuditLogService;
import com.endpointguard.common.config.AppProperties;
import com.endpointguard.common.time.UtcDateTime;
import com.endpointguard.endpoint.domain.Endpoint;
import com.endpointguard.endpoint.repository.EndpointRepository;
import com.endpointguard.metrics.domain.TrafficMetric;
import com.endpointguard.metrics.repository.TrafficMetricRepository;
import com.endpointguard.praffected.repository.PrAffectedEndpointRepository;
import com.endpointguard.project.service.ProjectService;
import com.endpointguard.pullrequest.domain.PullRequest;
import com.endpointguard.pullrequest.repository.PullRequestRepository;
import com.endpointguard.risk.domain.PostMergeMonitoring;
import com.endpointguard.risk.dto.PostMergeMonitoringResponse;
import com.endpointguard.risk.repository.PostMergeMonitoringRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class PostMergeMonitoringService {

    private static final String PENDING = "PENDING";

    private final PostMergeMonitoringRepository monitoringRepository;
    private final PrAffectedEndpointRepository affectedEndpointRepository;
    private final TrafficMetricRepository trafficMetricRepository;
    private final AppProperties appProperties;
    private final PullRequestRepository pullRequestRepository;
    private final EndpointRepository endpointRepository;
    private final ProjectService projectService;
    private final AuditLogService auditLogService;

    @Transactional
    public void startForMergedPullRequest(PullRequest pullRequest) {
        if (pullRequest.getMergedAt() == null) return;

        LocalDateTime windowEnd = pullRequest.getMergedAt()
                .plusHours(appProperties.getMonitoring().getWindowHours());
        affectedEndpointRepository.findByPullRequestOrderByEndpointAsc(pullRequest).forEach(affected -> {
            Long endpointId = affected.getEndpoint().getId();
            if (monitoringRepository.existsByPullRequestIdAndEndpointId(pullRequest.getId(), endpointId)) return;
            monitoringRepository.save(PostMergeMonitoring.builder()
                    .pullRequestId(pullRequest.getId())
                    .endpointId(endpointId)
                    .windowStart(pullRequest.getMergedAt())
                    .windowEnd(windowEnd)
                    .verdict(PENDING)
                    .build());
                    auditLogService.record("PULL_REQUEST", pullRequest.getId().toString(), "MONITORING_STARTED",
                        pullRequest.getRepository().getProject().getOwner().getEmail(),
                        java.util.Map.of("endpointId", endpointId, "windowEnd", windowEnd.toString()));
        });
    }

                @Transactional(readOnly = true)
                public List<PostMergeMonitoringResponse> listForProject(Long projectId, String ownerEmail) {
                projectService.getOwned(projectId, ownerEmail);
                return pullRequestRepository.findOwnedForProject(projectId, ownerEmail).stream()
                    .flatMap(pullRequest -> monitoringRepository
                        .findByPullRequestIdOrderByWindowEndDesc(pullRequest.getId()).stream()
                        .map(record -> response(record, pullRequest,
                            endpointRepository.findById(record.getEndpointId()).orElse(null))))
                    .filter(response -> response.endpointPath() != null)
                    .toList();
                }

    @Scheduled(fixedDelayString = "${app.monitoring.check-interval-ms:900000}")
    @Transactional
    public void processDueWindows() {
        evaluateDueWindows(UtcDateTime.now());
    }

    @Transactional
    public void evaluateDueWindows(LocalDateTime now) {
        List<PostMergeMonitoring> due = monitoringRepository
                .findByVerdictAndWindowEndLessThanEqual(PENDING, now);
        due.forEach(record -> evaluate(record, now));
    }

    private void evaluate(PostMergeMonitoring record, LocalDateTime evaluatedAt) {
        LocalDateTime baselineStart = record.getWindowStart()
                .minusHours(appProperties.getMonitoring().getWindowHours());
        Aggregate baseline = aggregate(record.getEndpointId(), baselineStart, record.getWindowStart());
        Aggregate observed = aggregate(record.getEndpointId(), record.getWindowStart(), record.getWindowEnd());

        record.setBaselineRequestCount(baseline.requestCount());
        record.setObservedRequestCount(observed.requestCount());
        if (baseline.requestCount() < appProperties.getMonitoring().getMinSampleCount()
                || observed.requestCount() < appProperties.getMonitoring().getMinSampleCount()) {
            record.setVerdict("INCONCLUSIVE");
        } else {
            record.setBaselineErrorRate(baseline.errorRate());
            record.setObservedErrorRate(observed.errorRate());
            record.setBaselineLatencyMs(baseline.averageLatencyMs());
            record.setObservedLatencyMs(observed.averageLatencyMs());
            record.setVerdict(compare(baseline, observed));
        }
        record.setEvaluatedAt(evaluatedAt);
        monitoringRepository.save(record);
        pullRequestRepository.findById(record.getPullRequestId()).ifPresent(pullRequest ->
            auditLogService.record("POST_MERGE_MONITORING", record.getId().toString(), "MONITORING_VERDICT",
                pullRequest.getRepository().getProject().getOwner().getEmail(),
                java.util.Map.of("verdict", record.getVerdict(),
                    "baselineRequestCount", baseline.requestCount(),
                    "observedRequestCount", observed.requestCount())));
    }

        private static PostMergeMonitoringResponse response(
            PostMergeMonitoring record, PullRequest pullRequest, Endpoint endpoint) {
        return new PostMergeMonitoringResponse(record.getId(), pullRequest.getId(),
            pullRequest.getGithubPrNumber(), pullRequest.getRepository().getGithubRepoFullName(),
            record.getEndpointId(), endpoint == null ? null : endpoint.getMethod(),
            endpoint == null ? null : endpoint.getPathPattern(), record.getWindowStart(),
            record.getWindowEnd(), record.getBaselineRequestCount(), record.getObservedRequestCount(),
            record.getBaselineErrorRate(), record.getObservedErrorRate(), record.getBaselineLatencyMs(),
            record.getObservedLatencyMs(), record.getVerdict(), record.getEvaluatedAt());
        }

    private Aggregate aggregate(Long endpointId, LocalDateTime from, LocalDateTime to) {
        List<TrafficMetric> metrics = trafficMetricRepository
                .findByEndpointIdAndBucketStartBetweenOrderByBucketStartAsc(endpointId, from, to).stream()
                .filter(metric -> metric.getBucketStart().isBefore(to))
                .toList();
        long requests = metrics.stream().mapToLong(metric -> value(metric.getRequestCount())).sum();
        long serverErrors = metrics.stream().mapToLong(metric -> value(metric.getError5xxCount())).sum();
        double weightedLatency = metrics.stream()
                .mapToDouble(metric -> (metric.getAvgLatencyMs() == null ? 0.0 : metric.getAvgLatencyMs())
                        * value(metric.getRequestCount()))
                .sum();
        return new Aggregate(requests, requests == 0 ? 0.0 : (double) serverErrors / requests,
                requests == 0 ? 0.0 : weightedLatency / requests);
    }

    private static String compare(Aggregate baseline, Aggregate observed) {
        boolean degraded = observed.errorRate() > baseline.errorRate() + 0.005
                || observed.averageLatencyMs() > baseline.averageLatencyMs() * 1.10;
        if (degraded) return "DEGRADED";
        boolean improved = observed.errorRate() < baseline.errorRate() - 0.005
                || observed.averageLatencyMs() < baseline.averageLatencyMs() * 0.90;
        return improved ? "IMPROVED" : "NO_CHANGE";
    }

    private static long value(Long value) {
        return value == null ? 0L : Math.max(value, 0L);
    }

    private record Aggregate(long requestCount, double errorRate, double averageLatencyMs) { }
}