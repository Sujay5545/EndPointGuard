package com.endpointguard.risk.dto;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public record RiskEvaluationResponse(
        Long endpointId,
        Long pullRequestId,
        double score,
        String tier,
        Map<String, Double> factorBreakdown,
        LocalDateTime evaluatedAt,
        String evaluationStatus,
        List<String> dataQualityNotes
) {}
