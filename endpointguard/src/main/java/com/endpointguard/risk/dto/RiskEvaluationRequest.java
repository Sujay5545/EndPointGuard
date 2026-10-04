package com.endpointguard.risk.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record RiskEvaluationRequest(
        @NotNull @Positive Long pullRequestId
) {}
