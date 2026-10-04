package com.endpointguard.risk.service;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class RiskEvaluationServiceTests {

    private static final Set<String> FACTORS = Set.of(
            "TRAFFIC_VOLUME", "RATE_LIMIT_UTILIZATION", "ERROR_RATE_TREND",
            "LATENCY_TREND", "DIFF_SIZE", "HISTORICAL_INCIDENTS");

    @Test
    void activeRecognizedWeightsAreNormalizedAndUnknownFactorsIgnored() {
        Map<String, Double> configured = new LinkedHashMap<>();
        configured.put("TRAFFIC_VOLUME", 1.0);
        configured.put("DIFF_SIZE", 3.0);
        configured.put("UNRECOGNIZED_FACTOR", 100.0);

        Map<String, Double> weights = RiskEvaluationService.normalizedWeights(configured, FACTORS);

        assertThat(weights).containsEntry("TRAFFIC_VOLUME", 0.25)
                .containsEntry("DIFF_SIZE", 0.75)
                .doesNotContainKey("UNRECOGNIZED_FACTOR");
        assertThat(weights.values()).allMatch(weight -> weight >= 0.0 && weight <= 1.0);
        assertThat(weights.values().stream().mapToDouble(Double::doubleValue).sum()).isEqualTo(1.0);
    }

    @Test
    void invalidWeightSetFallsBackToBoundedDefaults() {
        Map<String, Double> configured = Map.of(
                "TRAFFIC_VOLUME", Double.NaN,
                "DIFF_SIZE", -1.0);

        Map<String, Double> weights = RiskEvaluationService.normalizedWeights(configured, FACTORS);

        assertThat(weights.values().stream().mapToDouble(Double::doubleValue).sum()).isEqualTo(1.0);
        assertThat(weights).containsEntry("TRAFFIC_VOLUME", 0.20)
                .containsEntry("HISTORICAL_INCIDENTS", 0.10);
    }

    @Test
    void configuredTrafficDiffAndIncidentThresholdsControlTheirFactors() {
        assertThat(RiskEvaluationService.trafficVolumeFactor(500L, 2000.0)).isEqualTo(0.25);
        assertThat(RiskEvaluationService.diffSizeFactor(125L, 500.0)).isEqualTo(0.25);
        assertThat(RiskEvaluationService.incidentFactor(2L, 8.0)).isEqualTo(0.25);
    }

    @Test
    void invalidConfiguredFactorThresholdUsesFallback() {
        assertThat(RiskEvaluationService.positiveThreshold(0.0, 1000.0)).isEqualTo(1000.0);
        assertThat(RiskEvaluationService.positiveThreshold(Double.NaN, 500.0)).isEqualTo(500.0);
        assertThat(RiskEvaluationService.positiveThreshold(250.0, 500.0)).isEqualTo(250.0);
    }
}