package com.endpointguard.risk.controller;

import com.endpointguard.risk.dto.RiskEvaluationRequest;
import com.endpointguard.risk.dto.RiskEvaluationResponse;
import com.endpointguard.risk.service.RiskEvaluationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/endpoints")
@RequiredArgsConstructor
public class RiskController {

    private final RiskEvaluationService riskEvaluationService;

    @PostMapping("/{endpointId}/risk/evaluate")
    public RiskEvaluationResponse evaluate(
            @PathVariable Long endpointId,
            @Valid @RequestBody RiskEvaluationRequest request,
            Authentication authentication) {
        return riskEvaluationService.evaluate(endpointId, authentication.getName(), request);
    }
}
