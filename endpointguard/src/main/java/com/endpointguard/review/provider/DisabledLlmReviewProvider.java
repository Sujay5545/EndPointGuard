package com.endpointguard.review.provider;

import com.endpointguard.review.dto.ReviewDecision;
import com.endpointguard.review.dto.ReviewRequest;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class DisabledLlmReviewProvider implements LlmReviewProvider {

    @Override
    public String providerName() {
        return "none";
    }

    @Override
    public ReviewDecision review(ReviewRequest request) {
        double score = request.computedRiskScore() == null ? heuristicScore(request) : request.computedRiskScore();
        String decision = score >= 70.0 ? "HIGH" : score >= 40.0 ? "MEDIUM" : "LOW";

        return new ReviewDecision(
                decision,
                score,
                "Deterministic fallback review without an LLM provider",
                List.of("No external provider configured", "Manual review recommended for policy-sensitive changes"),
                providerName(),
                true
        );
    }

    private double heuristicScore(ReviewRequest request) {
        int score = 0;
        if (request.changeSummary() != null && request.changeSummary().length() > 80) {
            score += 20;
        }
        if (request.diff() != null && request.diff().length() > 200) {
            score += 25;
        }
        if (request.affectedEndpoints() != null && !request.affectedEndpoints().isBlank()) {
            score += 25;
        }
        return Math.max(0.0, Math.min(100.0, score));
    }
}
