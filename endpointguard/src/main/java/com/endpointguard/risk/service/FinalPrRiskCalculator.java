package com.endpointguard.risk.service;

public final class FinalPrRiskCalculator {

    private static final double OPERATIONAL_INFLUENCE = 0.20;

    private FinalPrRiskCalculator() {
    }

    public static Double calculate(Double aiAdvisoryRisk, Double operationalRisk) {
        if (aiAdvisoryRisk == null || !Double.isFinite(aiAdvisoryRisk)) {
            return null;
        }

        double aiRisk = clamp(aiAdvisoryRisk);
        double operational = operationalRisk == null || !Double.isFinite(operationalRisk)
                ? aiRisk
                : clamp(operationalRisk);
        double operationalDelta = Math.max(0.0, operational - aiRisk);
        return clamp(aiRisk + operationalDelta * OPERATIONAL_INFLUENCE);
    }

    private static double clamp(double score) {
        return Math.max(0.0, Math.min(1.0, score));
    }
}