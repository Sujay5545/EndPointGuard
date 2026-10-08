package com.endpointguard.review;

import com.endpointguard.common.config.AppProperties;
import com.endpointguard.review.dto.GroqReviewOutput;
import com.endpointguard.review.dto.ReviewDecision;
import com.endpointguard.review.dto.ReviewRequest;
import com.endpointguard.review.provider.LlmReviewProvider;
import com.endpointguard.review.service.LlmReviewService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LlmReviewServiceTests {

    @Test
    void returnsDeterministicFallbackWhenLlmDisabled() {
        AppProperties properties = new AppProperties();
        properties.getLlm().setProvider("none");

        LlmReviewService service = new LlmReviewService(properties, List.of());
        ReviewDecision decision = service.review(new ReviewRequest(
                "acme/payments-api",
                "Add payout endpoint",
                "Updated payout flow and validation",
                "diff --git a/src/main/java/...",
                82.0
        ));

        assertThat(decision.decision()).isEqualTo("HIGH");
        assertThat(decision.fallback()).isTrue();
        assertThat(decision.score()).isEqualTo(82.0);
    }

    @Test
    void returnsProviderDecisionWhenValidResponseIsProvided() {
        AppProperties properties = new AppProperties();
        properties.getLlm().setProvider("openai");

        LlmReviewService service = new LlmReviewService(properties, List.of(new FixedReviewProvider()));
        ReviewDecision decision = service.review(new ReviewRequest(
                "acme/payments-api",
                "Improve validation",
                "Tightens checks around payment validation",
                "diff",
                52.0
        ));

        assertThat(decision.decision()).isEqualTo("MEDIUM");
        assertThat(decision.score()).isEqualTo(56.0);
        assertThat(decision.fallback()).isFalse();
    }

        @Test
        void preservesCriticalStructuredAdvisoryWithoutChangingDeterministicRisk() {
        AppProperties properties = new AppProperties();
        properties.getLlm().setProvider("groq");
        GroqReviewOutput analysis = new GroqReviewOutput(
            "Potential component render failure.",
            new GroqReviewOutput.OverallRisk(
                GroqReviewOutput.RiskLevel.CRITICAL, 96.0, 0.98, "The return token was removed."),
            "Remove return before JSX",
            List.of(new GroqReviewOutput.FileAnalysis(
                "src/Components/Github.jsx", "Removed the return keyword", "`return (` becomes `(`",
                "Component returns JSX", "The page may fail to render",
                List.of(GroqReviewOutput.RiskCategory.BUILD_BREAKING),
                "The JSX expression may not be returned", List.of("Diff removes `return (`."),
                List.of(), 0.98)),
            List.of(),
            List.of(),
            new GroqReviewOutput.BusinessImpact(
                "Business value cannot be determined from the provided diff/context.", "Unknown", 0.2),
            "Restore or validate the component return.",
            0.98);
        LlmReviewProvider provider = new LlmReviewProvider() {
            @Override
            public String providerName() {
            return "groq";
            }

            @Override
            public ReviewDecision review(ReviewRequest request) {
            return new ReviewDecision(
                "CRITICAL", 96.0, analysis.overallAssessment(), List.of(), "groq", false, analysis);
            }
        };

        ReviewDecision decision = new LlmReviewService(properties, List.of(provider)).review(new ReviewRequest(
            "acme/frontend", "Remove return", "- return (\n+ (", "", 18.0));

        assertThat(decision.decision()).isEqualTo("CRITICAL");
        assertThat(decision.score()).isEqualTo(96.0);
        assertThat(decision.analysis()).isSameAs(analysis);
        assertThat(decision.fallback()).isFalse();
        assertThat(decision.score()).isNotEqualTo(18.0);
        }

    private static final class FixedReviewProvider implements com.endpointguard.review.provider.LlmReviewProvider {
        @Override
        public String providerName() {
            return "openai";
        }

        @Override
        public ReviewDecision review(ReviewRequest request) {
            return new ReviewDecision("MEDIUM", 56.0, "Suggested review", List.of("Validate new checks"), "openai", false);
        }
    }
}
