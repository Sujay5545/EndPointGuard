package com.endpointguard.risk.service;

import com.endpointguard.audit.service.AuditLogService;
import com.endpointguard.common.config.AppProperties;
import com.endpointguard.common.exception.ConflictException;
import com.endpointguard.common.exception.ResourceNotFoundException;
import com.endpointguard.common.time.UtcDateTime;
import com.endpointguard.endpoint.repository.EndpointRepository;
import com.endpointguard.metrics.domain.TrafficMetric;
import com.endpointguard.metrics.repository.TrafficMetricRepository;
import com.endpointguard.praffected.domain.PrAffectedEndpoint;
import com.endpointguard.praffected.repository.PrAffectedEndpointRepository;
import com.endpointguard.pullrequest.repository.PrChangedFileRepository;
import com.endpointguard.pullrequest.domain.PullRequest;
import com.endpointguard.pullrequest.repository.PullRequestRepository;
import com.endpointguard.risk.domain.RiskAssessment;
import com.endpointguard.risk.domain.RiskRuleWeight;
import com.endpointguard.risk.dto.RiskEvaluationRequest;
import com.endpointguard.risk.dto.RiskEvaluationResponse;
import com.endpointguard.risk.repository.RiskAssessmentRepository;
import com.endpointguard.risk.repository.RiskRuleWeightRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class RiskEvaluationService {

    private final EndpointRepository endpointRepository;
    private final PullRequestRepository pullRequestRepository;
    private final PrAffectedEndpointRepository prAffectedEndpointRepository;
    private final TrafficMetricRepository trafficMetricRepository;
    private final PrChangedFileRepository prChangedFileRepository;
    private final RiskRuleWeightRepository riskRuleWeightRepository;
    private final RiskAssessmentRepository riskAssessmentRepository;
    private final ObjectMapper objectMapper;
    private final AppProperties appProperties;
    private final AuditLogService auditLogService;

    @Transactional
    public RiskEvaluationResponse evaluate(Long endpointId, String ownerEmail, RiskEvaluationRequest request) {
        var endpoint = endpointRepository.findOwnedById(endpointId, ownerEmail)
                .orElseThrow(() -> new ResourceNotFoundException("Endpoint", endpointId));
        PullRequest pullRequest = pullRequestRepository.findOwnedInProject(
                        request.pullRequestId(), endpoint.getProjectId(), ownerEmail)
                .orElseThrow(() -> new ResourceNotFoundException("Pull request", request.pullRequestId()));
        if (!prAffectedEndpointRepository.existsByPullRequest_IdAndEndpoint_Id(pullRequest.getId(), endpointId)) {
            throw new ConflictException("Pull request is not mapped to this endpoint");
        }
        long diffSize = prChangedFileRepository.findByPullRequestOrderByFilePathAsc(pullRequest).stream()
                .mapToLong(file -> (long) Math.max(0, file.getAdditions()) + Math.max(0, file.getDeletions()))
                .sum();
        return evaluateFromStoredData(endpointId, ownerEmail, pullRequest, diffSize);
    }

    @Transactional
    public List<RiskEvaluationResponse> evaluateAutomatically(PullRequest pullRequest, Long diffSize) {
        String ownerEmail = pullRequest.getRepository().getProject().getOwner().getEmail();
        List<RiskEvaluationResponse> results = new ArrayList<>();
        List<PrAffectedEndpoint> affectedEndpoints = prAffectedEndpointRepository
                .findByPullRequestOrderByEndpointAsc(pullRequest);
        if (affectedEndpoints.isEmpty()) {
            RiskAssessment assessment = RiskAssessment.builder()
                    .pullRequestId(pullRequest.getId())
                    .score(0.0)
                    .tier("INSUFFICIENT_DATA")
                    .computedAt(UtcDateTime.now())
                    .factorBreakdown(objectMapper.createObjectNode())
                    .evaluationStatus("INSUFFICIENT_DATA")
                    .dataQualityNotes(objectMapper.valueToTree(List.of("NO_AFFECTED_ENDPOINTS")))
                    .build();
            riskAssessmentRepository.save(assessment);
            auditLogService.record("PULL_REQUEST", pullRequest.getId().toString(), "RISK_ASSESSED", ownerEmail,
                    Map.of("status", "INSUFFICIENT_DATA", "reason", "NO_AFFECTED_ENDPOINTS"));
            return results;
        }

        long resolvedDiffSize = Math.max(0L, diffSize == null ? 0L : diffSize);
        for (PrAffectedEndpoint affected : affectedEndpoints) {
            results.add(evaluateFromStoredData(affected.getEndpoint().getId(), ownerEmail,
                    pullRequest, resolvedDiffSize));
        }
        return results;
    }

    private RiskEvaluationResponse evaluateFromStoredData(
            Long endpointId, String ownerEmail, PullRequest pullRequest, long diffSize) {
        LocalDateTime evaluatedAt = UtcDateTime.now();
        var endpoint = endpointRepository.findOwnedById(endpointId, ownerEmail)
                .orElseThrow(() -> new ResourceNotFoundException("Endpoint", endpointId));
        Long pullRequestId = pullRequest.getId();
        PullRequest ownedPullRequest = pullRequestRepository.findOwnedInProject(
                pullRequestId, endpoint.getProjectId(), ownerEmail)
            .orElseThrow(() -> new ResourceNotFoundException("Pull request", pullRequestId));
        pullRequest = ownedPullRequest;
        if (!prAffectedEndpointRepository.existsByPullRequest_IdAndEndpoint_Id(pullRequest.getId(), endpointId)) {
            throw new ConflictException("Pull request is not mapped to this endpoint");
        }

        int windowHours = Math.max(1, appProperties.getMonitoring().getWindowHours());
        LocalDateTime recentStart = evaluatedAt.minusHours(windowHours);
        LocalDateTime baselineStart = recentStart.minusHours(windowHours);
        MetricWindow baseline = summarize(endpointId, baselineStart, recentStart);
        MetricWindow recent = summarize(endpointId, recentStart, evaluatedAt);
        int minimumSamples = appProperties.getMonitoring().getMinSampleCount();
        List<String> dataQualityNotes = new ArrayList<>();
        if (baseline.requestCount() < minimumSamples) dataQualityNotes.add("BASELINE_SAMPLE_COUNT_BELOW_MINIMUM");
        if (recent.requestCount() < minimumSamples) dataQualityNotes.add("RECENT_SAMPLE_COUNT_BELOW_MINIMUM");
        dataQualityNotes.add("HISTORICAL_INCIDENT_DATA_UNAVAILABLE");
        String evaluationStatus = baseline.requestCount() < minimumSamples || recent.requestCount() < minimumSamples
                ? "INSUFFICIENT_DATA"
                : "EVALUATED";

        Map<String, Double> factorBreakdown = new LinkedHashMap<>();
        factorBreakdown.put("TRAFFIC_VOLUME", trafficVolumeFactor(recent.requestCount(),
            positiveThreshold(appProperties.getRisk().getTraffic().getRequestThreshold(), 1000.0)));
        factorBreakdown.put("RATE_LIMIT_UTILIZATION", rateLimitUtilizationFactor(recent.rateLimitUtilizationPct()));
        factorBreakdown.put("ERROR_RATE_TREND", errorRateTrendFactor(recent.errorRate(), baseline.errorRate()));
        factorBreakdown.put("LATENCY_TREND", latencyTrendFactor(recent.avgLatencyMs(), baseline.avgLatencyMs()));
        factorBreakdown.put("DIFF_SIZE", diffSizeFactor(diffSize,
            positiveThreshold(appProperties.getRisk().getTraffic().getDiffSizeThreshold(), 500.0)));
        factorBreakdown.put("HISTORICAL_INCIDENTS", incidentFactor(0L,
            positiveThreshold(appProperties.getRisk().getTraffic().getIncidentCountThreshold(), 5.0)));

        Map<String, Double> weights = riskRuleWeightRepository.findByActiveTrueOrderByIdAsc().stream()
                .collect(java.util.stream.Collectors.toMap(
                        RiskRuleWeight::getFactorName,
                        RiskRuleWeight::getWeight,
                        (left, right) -> right,
                        LinkedHashMap::new));

        final Map<String, Double> resolvedWeights = normalizedWeights(weights, factorBreakdown.keySet());

        double score = factorBreakdown.entrySet().stream()
                .mapToDouble(entry -> {
                    double weight = resolvedWeights.getOrDefault(entry.getKey(), 0.0);
                    return entry.getValue() * weight;
                })
                .sum();

        String tier = "INSUFFICIENT_DATA".equals(evaluationStatus) ? "INSUFFICIENT_DATA" : tierFor(score);
        RiskAssessment assessment = RiskAssessment.builder()
            .pullRequestId(pullRequest.getId())
                .score(score)
                .tier(tier)
                .computedAt(evaluatedAt)
                .factorBreakdown(objectMapper.valueToTree(factorBreakdown))
                .evaluationStatus(evaluationStatus)
                .dataQualityNotes(objectMapper.valueToTree(dataQualityNotes))
                .build();
        riskAssessmentRepository.save(assessment);
        auditLogService.record("PULL_REQUEST", pullRequest.getId().toString(), "RISK_ASSESSED", ownerEmail,
            Map.of("endpointId", endpointId, "score", score, "tier", tier));

        return new RiskEvaluationResponse(endpointId, pullRequest.getId(), score, tier, factorBreakdown,
            evaluatedAt, evaluationStatus, dataQualityNotes);
    }

        private MetricWindow summarize(Long endpointId, LocalDateTime from, LocalDateTime to) {
        List<TrafficMetric> metrics = trafficMetricRepository
            .findByEndpointIdAndBucketStartBetweenOrderByBucketStartAsc(endpointId, from, to).stream()
            .filter(metric -> metric.getBucketStart().isBefore(to))
            .toList();
        long requestCount = metrics.stream().mapToLong(metric -> nonNegative(metric.getRequestCount())).sum();
        long errorCount = metrics.stream()
            .mapToLong(metric -> nonNegative(metric.getError4xxCount()) + nonNegative(metric.getError5xxCount()))
            .sum();
        double weightedLatency = metrics.stream().mapToDouble(metric ->
            (metric.getAvgLatencyMs() == null ? 0.0 : Math.max(0.0, metric.getAvgLatencyMs()))
                * nonNegative(metric.getRequestCount())).sum();
        double weightedRateLimit = metrics.stream().mapToDouble(metric ->
            (metric.getRateLimitUtilizationPct() == null ? 0.0 : Math.max(0.0, metric.getRateLimitUtilizationPct()))
                * nonNegative(metric.getRequestCount())).sum();
        return new MetricWindow(requestCount,
            requestCount == 0 ? 0.0 : (double) errorCount / requestCount,
            requestCount == 0 ? 0.0 : weightedLatency / requestCount,
            requestCount == 0 ? 0.0 : Math.min(100.0, weightedRateLimit / requestCount));
        }

        private static long nonNegative(Long value) {
        return value == null ? 0L : Math.max(0L, value);
        }

    private String tierFor(double score) {
        double mediumThreshold = appProperties.getRisk().getThresholds().getMedium();
        double highThreshold = appProperties.getRisk().getThresholds().getHigh();
        if (!Double.isFinite(mediumThreshold) || mediumThreshold <= 0.0 || mediumThreshold >= 1.0) {
            mediumThreshold = 0.6;
        }
        if (!Double.isFinite(highThreshold) || highThreshold <= mediumThreshold || highThreshold > 1.0) {
            highThreshold = 0.8;
            if (highThreshold <= mediumThreshold) mediumThreshold = 0.6;
        }
        if (score >= highThreshold) {
            return "HIGH";
        }
        if (score >= mediumThreshold) {
            return "MEDIUM";
        }
        return "LOW";
    }

    static double trafficVolumeFactor(Long requestCount, double threshold) {
        if (requestCount == null || requestCount <= 0) return 0.0;
        return Math.min(requestCount / threshold, 1.0);
    }

    private static double rateLimitUtilizationFactor(Double rateLimitUtilization) {
        if (rateLimitUtilization == null) return 0.0;
        return Math.min(Math.max(rateLimitUtilization, 0.0) / 100.0, 1.0);
    }

    private static double errorRateTrendFactor(Double recentErrorRate, Double baselineErrorRate) {
        if (recentErrorRate == null || baselineErrorRate == null) return 0.0;
        double delta = recentErrorRate - baselineErrorRate;
        if (delta <= 0) return 0.0;
        return Math.min(delta / Math.max(baselineErrorRate, 0.01), 1.0);
    }

    private static double latencyTrendFactor(Double recentLatencyMs, Double baselineLatencyMs) {
        if (recentLatencyMs == null || baselineLatencyMs == null) return 0.0;
        double delta = recentLatencyMs - baselineLatencyMs;
        if (delta <= 0) return 0.0;
        return Math.min(delta / Math.max(baselineLatencyMs, 1.0), 1.0);
    }

    static double diffSizeFactor(Long diffSize, double threshold) {
        if (diffSize == null || diffSize <= 0) return 0.0;
        return Math.min(diffSize / threshold, 1.0);
    }

    static double incidentFactor(Long incidentCount, double threshold) {
        if (incidentCount == null || incidentCount <= 0) return 0.0;
        return Math.min(incidentCount / threshold, 1.0);
    }

    static double positiveThreshold(double configured, double fallback) {
        return Double.isFinite(configured) && configured > 0.0 ? configured : fallback;
    }

    static Map<String, Double> normalizedWeights(Map<String, Double> configured, Set<String> factors) {
        Map<String, Double> validWeights = new LinkedHashMap<>();
        if (configured != null) {
            configured.forEach((factor, weight) -> {
                if (factors.contains(factor) && weight != null && Double.isFinite(weight) && weight > 0.0) {
                    validWeights.put(factor, weight);
                }
            });
        }
        double total = validWeights.values().stream().mapToDouble(Double::doubleValue).sum();
        if (!Double.isFinite(total) || total <= 0.0) {
            validWeights.clear();
            validWeights.putAll(defaultWeights());
            total = validWeights.values().stream().mapToDouble(Double::doubleValue).sum();
        }
        final double totalWeight = total;
        validWeights.replaceAll((factor, weight) -> weight / totalWeight);
        return validWeights;
    }

    private static Map<String, Double> defaultWeights() {
        Map<String, Double> weights = new LinkedHashMap<>();
        weights.put("TRAFFIC_VOLUME", 0.20);
        weights.put("RATE_LIMIT_UTILIZATION", 0.20);
        weights.put("ERROR_RATE_TREND", 0.25);
        weights.put("LATENCY_TREND", 0.15);
        weights.put("DIFF_SIZE", 0.10);
        weights.put("HISTORICAL_INCIDENTS", 0.10);
        return weights;
    }

    private record MetricWindow(long requestCount, double errorRate, double avgLatencyMs,
                                double rateLimitUtilizationPct) { }
}
