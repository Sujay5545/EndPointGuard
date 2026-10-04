package com.endpointguard.review.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Locale;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ReviewDecision(
        String decision,
        Double score,
        String summary,
        List<String> findings,
        String provider,
        boolean fallback,
        GroqReviewOutput analysis
) {
    public ReviewDecision(
            String decision,
            Double score,
            String summary,
            List<String> findings,
            String provider,
            boolean fallback) {
        this(decision, score, summary, findings, provider, fallback, null);
    }

    public ReviewDecision {
        if (decision == null || decision.isBlank()) {
            decision = "LOW";
        } else {
            String normalized = decision.trim().toUpperCase(Locale.ROOT);
            decision = List.of("LOW", "MEDIUM", "HIGH", "CRITICAL").contains(normalized)
                    ? normalized
                    : "LOW";
        }
        if (score == null || !Double.isFinite(score) || score < 0.0 || score > 100.0) {
            score = 0.0;
        }
        if (summary == null || summary.isBlank()) {
            summary = "Deterministic fallback review";
        } else if (summary.length() > 4000) {
            summary = summary.substring(0, 4000);
        }
        if (findings == null) {
            findings = List.of();
        } else {
            findings = findings.stream()
                    .filter(f -> f != null && !f.isBlank())
                    .map(f -> f.length() > 1000 ? f.substring(0, 1000) : f)
                    .limit(50)
                    .toList();
        }
        if (provider == null || provider.isBlank()) {
            provider = "none";
        } else if (provider.length() > 50) {
            provider = provider.substring(0, 50);
        }
    }
}
