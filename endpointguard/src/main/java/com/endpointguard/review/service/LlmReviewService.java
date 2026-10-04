package com.endpointguard.review.service;

import com.endpointguard.common.config.AppProperties;
import com.endpointguard.review.dto.ReviewDecision;
import com.endpointguard.review.dto.ReviewRequest;
import com.endpointguard.review.provider.LlmReviewProvider;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Locale;

@Service
public class LlmReviewService {

    private AppProperties appProperties;
    private List<LlmReviewProvider> providers;

    public LlmReviewService() {
        this(new AppProperties(), List.of());
    }

    public LlmReviewService(AppProperties appProperties) {
        this(appProperties, List.of());
    }

    @Autowired
    public LlmReviewService(AppProperties appProperties, List<LlmReviewProvider> providers) {
        this.appProperties = appProperties == null ? new AppProperties() : appProperties;
        this.providers = providers == null ? List.of() : providers;
    }

    public ReviewDecision review(ReviewRequest request) {
        String providerName = appProperties == null || appProperties.getLlm() == null
                ? "none"
                : appProperties.getLlm().getProvider();

        if (providerName == null || providerName.isBlank() || "none".equalsIgnoreCase(providerName)) {
            return fallbackDecision(request, "none");
        }

        LlmReviewProvider provider = providers.stream()
                .filter(candidate -> candidate.providerName().equalsIgnoreCase(providerName))
                .findFirst()
                .orElse(null);

        if (provider == null) {
            return fallbackDecision(request, providerName);
        }

        try {
            ReviewDecision decision = provider.review(request);
            if (!isValid(decision)) {
                return fallbackDecision(request, providerName);
            }
            return decision;
        } catch (Exception exception) {
            return fallbackDecision(request, providerName);
        }
    }

    private static boolean isValid(ReviewDecision decision) {
        if (decision == null
                || decision.decision() == null
                || !List.of("LOW", "MEDIUM", "HIGH", "CRITICAL")
                        .contains(decision.decision().toUpperCase(Locale.ROOT))
                || decision.score() == null
                || !Double.isFinite(decision.score())
                || decision.score() < 0.0
                || decision.score() > 100.0
                || decision.summary() == null
                || decision.summary().length() > 4000
                || decision.findings() == null
                || decision.findings().size() > 50) {
            return false;
        }
        if (decision.analysis() != null && !com.endpointguard.review.provider.GroqLlmReviewProvider.isValid(decision.analysis())) {
            return false;
        }
        return true;
    }

    private ReviewDecision fallbackDecision(ReviewRequest request, String providerName) {
        double score = request.computedRiskScore() == null ? 0.0 : request.computedRiskScore();
        if (score <= 0.0) {
            score = request.changeSummary() == null ? 0.0 : Math.min(100.0, request.changeSummary().length() * 0.8);
        }
        String decision = score >= 70.0 ? "HIGH" : score >= 40.0 ? "MEDIUM" : "LOW";
        return new ReviewDecision(
                decision,
                score,
                "Deterministic fallback review because the configured LLM provider was unavailable or not enabled",
                List.of("LLM provider unavailable", "Review requires explicit human or provider validation"),
                providerName == null ? "none" : providerName.toLowerCase(Locale.ROOT),
                true
        );
    }
}
