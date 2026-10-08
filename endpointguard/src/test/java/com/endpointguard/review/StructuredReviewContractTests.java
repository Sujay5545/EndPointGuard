package com.endpointguard.review;

import com.endpointguard.review.dto.GroqReviewOutput;
import com.endpointguard.review.dto.GroqStructuredReviewOutput;
import com.endpointguard.review.dto.ReviewDecision;
import com.endpointguard.review.provider.GroqLlmReviewProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.converter.BeanOutputConverter;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class StructuredReviewContractTests {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private GroqReviewOutput validOutput() {
        return new GroqReviewOutput(
                "Overall assessment of the PR.",
                new GroqReviewOutput.OverallRisk(
                        GroqReviewOutput.RiskLevel.CRITICAL, 95.0, 0.95, "Valid critical risk reason"),
                "Change summary text",
                List.of(new GroqReviewOutput.FileAnalysis(
                        "src/main/OrderService.java",
                        "Summary of changes",
                        "Modified order processing logic",
                        "Processes orders and computes totals",
                        "Direct impact on order placement capability",
                        List.of(GroqReviewOutput.RiskCategory.BUSINESS_LOGIC_CHANGE, GroqReviewOutput.RiskCategory.RUNTIME_BREAKING),
                        "Throws exception on zero order items",
                        List.of("Added validation check in line 42"),
                        List.of(new GroqReviewOutput.EndpointImpact(
                                "POST", "/api/orders", "HIGH", "Fails empty requests", "Validates item count")),
                        0.9
                )),
                List.of(new GroqReviewOutput.CriticalFinding(
                        GroqReviewOutput.RiskLevel.CRITICAL,
                        GroqReviewOutput.RiskCategory.BUSINESS_LOGIC_CHANGE,
                        "src/main/OrderService.java",
                        "Empty order rejection breaks batch processor",
                        "Batch jobs send empty payloads during keep-alive",
                        "Added check throws IllegalArgumentException",
                        "Add explicit batch exemption or handle empty lists gracefully"
                )),
                List.of(new GroqReviewOutput.EndpointImpact(
                        "POST", "/api/orders", "HIGH", "Rejects empty payloads", "Enforces item presence")),
                new GroqReviewOutput.BusinessImpact(
                        "Affects order placement throughput and partner sync",
                        "Order checkout and fulfillment",
                        0.88),
                "Review batch job compatibility before merging",
                0.95
        );
    }

        private GroqReviewOutput withFileRiskCategories(List<GroqReviewOutput.RiskCategory> categories) {
                GroqReviewOutput valid = validOutput();
                GroqReviewOutput.FileAnalysis original = valid.files().get(0);
                GroqReviewOutput.FileAnalysis file = new GroqReviewOutput.FileAnalysis(
                                original.file(), original.changeSummary(), original.whatChanged(), original.whatItDoes(),
                                original.businessImpact(), categories, original.technicalImpact(),
                                original.evidence(), original.affectedEndpoints(), original.confidence());
                return new GroqReviewOutput(valid.overallAssessment(), valid.overallRisk(), valid.changeSummary(),
                                List.of(file), valid.criticalFindings(), valid.endpointImpact(), valid.businessImpact(),
                                valid.recommendation(), valid.reviewConfidence(), valid.riskAlignment());
        }

    @Test
    void validStructuredOutputPassesValidation() {
        GroqReviewOutput output = validOutput();
        assertThat(GroqLlmReviewProvider.isValid(output)).isTrue();
    }

        @Test
        void fileWithOneRiskCategoryPassesValidation() {
                GroqReviewOutput output = withFileRiskCategories(
                                List.of(GroqReviewOutput.RiskCategory.BUSINESS_LOGIC_CHANGE));

                assertThat(GroqLlmReviewProvider.isValid(output)).isTrue();
        }

        @Test
        void fileWithMultipleRiskCategoriesPassesValidation() {
                GroqReviewOutput output = withFileRiskCategories(List.of(
                                GroqReviewOutput.RiskCategory.BUSINESS_LOGIC_CHANGE,
                                GroqReviewOutput.RiskCategory.RUNTIME_BREAKING,
                                GroqReviewOutput.RiskCategory.DATA_RISK));

                assertThat(GroqLlmReviewProvider.isValid(output)).isTrue();
        }

        @Test
        void fileWithEmptyRiskCategoriesFailsValidation() {
                GroqReviewOutput output = withFileRiskCategories(List.of());

                assertThat(GroqLlmReviewProvider.isValid(output)).isFalse();
        }

    @Test
    void enumsAreCaseInsensitiveWhenDeserializingJson() throws Exception {
        String json = """
                {
                  "overallAssessment": "Safe change",
                  "overallRisk": {
                    "level": "critical",
                    "score": 85.0,
                    "confidence": 0.9,
                    "reason": "Risk reason"
                  },
                  "changeSummary": "Change summary",
                  "files": [
                    {
                      "file": "src/App.jsx",
                      "changeSummary": "Summary",
                      "whatChanged": "Changed return",
                      "whatItDoes": "Renders UI",
                      "businessImpact": "UI rendering",
                      "riskCategories": ["build_breaking", "runtime_breaking"],
                      "technicalImpact": "May crash on load",
                      "evidence": ["Removed return"],
                      "affectedEndpoints": [],
                      "confidence": 0.85
                    }
                  ],
                  "criticalFindings": [],
                  "endpointImpact": [],
                  "businessImpact": {
                    "summary": "Impact",
                    "affectedCapability": "Web frontend",
                    "confidence": 0.8
                  },
                  "recommendation": "Fix return",
                  "reviewConfidence": 0.92
                }
                """;
        GroqReviewOutput parsed = objectMapper.readValue(json, GroqReviewOutput.class);
        assertThat(parsed.overallRisk().level()).isEqualTo(GroqReviewOutput.RiskLevel.CRITICAL);
        assertThat(parsed.files().get(0).riskCategories()).containsExactly(
                GroqReviewOutput.RiskCategory.BUILD_BREAKING,
                GroqReviewOutput.RiskCategory.RUNTIME_BREAKING
        );
        assertThat(GroqLlmReviewProvider.isValid(parsed)).isTrue();
    }

    @Test
    void riskAlignmentStructIsParsedAndValidated() throws Exception {
        String json = """
                {
                  "overallAssessment": "Safe change",
                  "overallRisk": {
                    "level": "HIGH",
                    "score": 72.0,
                    "confidence": 0.9,
                    "reason": "Risk reason"
                  },
                  "changeSummary": "Summary",
                  "files": [
                    {
                      "file": "src/App.jsx",
                      "changeSummary": "Summary",
                      "whatChanged": "Changed return",
                      "whatItDoes": "Renders UI",
                      "businessImpact": "UI rendering",
                      "riskCategories": ["BUILD_BREAKING"],
                      "technicalImpact": "May crash on load",
                      "evidence": ["Removed return"],
                      "affectedEndpoints": [],
                      "confidence": 0.85
                    }
                  ],
                  "criticalFindings": [],
                  "endpointImpact": [],
                  "businessImpact": {
                    "summary": "Impact",
                    "affectedCapability": "Web frontend",
                    "confidence": 0.8
                  },
                  "recommendation": "Fix return",
                  "reviewConfidence": 0.92,
                  "riskAlignment": {
                    "deterministicRisk": "MEDIUM",
                    "llmRisk": "HIGH",
                    "alignment": "DIFFERENT",
                    "explanation": "The static risk model underestimates this build-breaking JSX regression."
                  }
                }
                """;
        GroqReviewOutput parsed = objectMapper.readValue(json, GroqReviewOutput.class);
        assertThat(parsed.riskAlignment()).isNotNull();
        assertThat(parsed.riskAlignment().deterministicRisk()).isEqualTo("MEDIUM");
        assertThat(parsed.riskAlignment().llmRisk()).isEqualTo("HIGH");
        assertThat(parsed.riskAlignment().alignment()).isEqualTo(GroqReviewOutput.RiskAlignment.Alignment.DIFFERENT);
        assertThat(GroqLlmReviewProvider.isValid(parsed)).isTrue();
    }

    @Test
        void riskAlignmentSchemaUsesOnlyExistingRiskLevelEnumValues() throws Exception {
                BeanOutputConverter<GroqStructuredReviewOutput> converter = providerSchemaConverter();
        JsonNode schema = objectMapper.valueToTree(converter.getJsonSchemaMap());

        JsonNode deterministicRisk = schema.path("properties").path("riskAlignmentDeterministicRisk");
        JsonNode llmRisk = schema.path("properties").path("riskAlignmentLlmRisk");

        assertThat(deterministicRisk.path("enum").isArray()).isTrue();
        assertThat(llmRisk.path("enum").isArray()).isTrue();
        assertThat(enumValues(deterministicRisk)).containsExactlyInAnyOrder("LOW", "MEDIUM", "HIGH", "CRITICAL");
        assertThat(enumValues(llmRisk)).containsExactlyInAnyOrder("LOW", "MEDIUM", "HIGH", "CRITICAL");
    }

        @SuppressWarnings("unchecked")
        private static BeanOutputConverter<GroqStructuredReviewOutput> providerSchemaConverter() throws Exception {
                Class<?> converterClass = Class.forName("com.endpointguard.review.provider.GroqLlmReviewProvider$GroqStructuredReviewConverter");
                Constructor<?> constructor = converterClass.getDeclaredConstructor();
                constructor.setAccessible(true);
                return (BeanOutputConverter<GroqStructuredReviewOutput>) constructor.newInstance();
        }

    @ParameterizedTest
    @EnumSource(GroqReviewOutput.RiskLevel.class)
    void riskAlignmentAcceptsEveryExistingRiskLevelEnumValue(GroqReviewOutput.RiskLevel riskLevel) {
        GroqReviewOutput output = withRiskAlignment(riskLevel.name(), riskLevel.name(), "ALIGNED", "Agreement by exact level match.");
        assertThat(GroqLlmReviewProvider.isValid(output)).isTrue();
    }

    @Test
    void invalidRiskAlignmentLlmRiskIsRejected() {
        GroqReviewOutput output = withRiskAlignment("LOW", "HIGH RISK", "DIFFERENT", "The advisory score is more severe than deterministic risk.");
        assertThat(GroqLlmReviewProvider.isValid(output)).isFalse();
    }

    private GroqReviewOutput withRiskAlignment(
            String deterministicRisk,
            String llmRisk,
            String alignment,
            String explanation) {
        GroqReviewOutput valid = validOutput();
        GroqReviewOutput.RiskAlignment riskAlignment = new GroqReviewOutput.RiskAlignment(
                deterministicRisk,
                llmRisk,
                GroqReviewOutput.RiskAlignment.Alignment.fromString(alignment),
                explanation);
        return new GroqReviewOutput(
                valid.overallAssessment(),
                valid.overallRisk(),
                valid.changeSummary(),
                valid.files(),
                valid.criticalFindings(),
                valid.endpointImpact(),
                valid.businessImpact(),
                valid.recommendation(),
                valid.reviewConfidence(),
                riskAlignment);
    }

    private static List<String> enumValues(JsonNode enumNode) {
        List<String> values = new ArrayList<>();
        enumNode.path("enum").forEach(node -> values.add(node.asText()));
        return values;
    }

    @Test
    void unknownEnumSafelyDeserializesToNullAndFailsValidation() throws Exception {
        String json = """
                {
                  "overallAssessment": "Safe change",
                  "overallRisk": {
                    "level": "EXTREME_DANGER",
                    "score": 85.0,
                    "confidence": 0.9,
                    "reason": "Risk reason"
                  },
                  "changeSummary": "Summary",
                  "files": [],
                  "criticalFindings": [],
                  "endpointImpact": [],
                  "businessImpact": {
                    "summary": "Impact",
                    "affectedCapability": "Web",
                    "confidence": 0.8
                  },
                  "recommendation": "Check",
                  "reviewConfidence": 0.9
                }
                """;
        GroqReviewOutput parsed = objectMapper.readValue(json, GroqReviewOutput.class);
        assertThat(parsed.overallRisk().level()).isNull();
        assertThat(GroqLlmReviewProvider.isValid(parsed)).isFalse();
    }

    @Test
    void unknownPropertiesAreIgnoredGracefully() throws Exception {
        String json = """
                {
                  "overallAssessment": "Assessment text",
                  "unexpectedTopLevelField": "should be ignored",
                  "overallRisk": {
                    "level": "LOW",
                    "score": 10.0,
                    "confidence": 0.9,
                    "reason": "Low risk",
                    "unexpectedNestedField": 42
                  },
                  "changeSummary": "Summary",
                  "files": [
                    {
                      "file": "README.md",
                      "changeSummary": "Doc update",
                      "whatChanged": "Text changed",
                      "whatItDoes": "Documents usage",
                      "businessImpact": "None",
                      "riskCategories": ["DOCUMENTATION_ONLY"],
                      "technicalImpact": "None",
                      "evidence": ["README edited"],
                      "affectedEndpoints": [],
                      "confidence": 0.99,
                      "futureField": true
                    }
                  ],
                  "criticalFindings": [],
                  "endpointImpact": [],
                  "businessImpact": {
                    "summary": "No impact",
                    "affectedCapability": "Docs",
                    "confidence": 0.95
                  },
                  "recommendation": "Merge safely",
                  "reviewConfidence": 0.95
                }
                """;
        GroqReviewOutput parsed = objectMapper.readValue(json, GroqReviewOutput.class);
        assertThat(GroqLlmReviewProvider.isValid(parsed)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(doubles = {-0.1, 1.01, Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY})
    void invalidConfidenceFailsValidation(double invalidConfidence) {
        GroqReviewOutput valid = validOutput();
        GroqReviewOutput badConfidence = new GroqReviewOutput(
                valid.overallAssessment(),
                new GroqReviewOutput.OverallRisk(
                        valid.overallRisk().level(), valid.overallRisk().score(), invalidConfidence, valid.overallRisk().reason()),
                valid.changeSummary(),
                valid.files(),
                valid.criticalFindings(),
                valid.endpointImpact(),
                valid.businessImpact(),
                valid.recommendation(),
                valid.reviewConfidence()
        );
        assertThat(GroqLlmReviewProvider.isValid(badConfidence)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(doubles = {-1.0, 100.1, Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY})
    void invalidScoreFailsValidation(double invalidScore) {
        GroqReviewOutput valid = validOutput();
        GroqReviewOutput badScore = new GroqReviewOutput(
                valid.overallAssessment(),
                new GroqReviewOutput.OverallRisk(
                        valid.overallRisk().level(), invalidScore, valid.overallRisk().confidence(), valid.overallRisk().reason()),
                valid.changeSummary(),
                valid.files(),
                valid.criticalFindings(),
                valid.endpointImpact(),
                valid.businessImpact(),
                valid.recommendation(),
                valid.reviewConfidence()
        );
        assertThat(GroqLlmReviewProvider.isValid(badScore)).isFalse();
    }

    @Test
    void excessiveFileCountFailsValidation() {
        GroqReviewOutput valid = validOutput();
        List<GroqReviewOutput.FileAnalysis> tooManyFiles = new ArrayList<>();
        for (int i = 0; i < 26; i++) {
            tooManyFiles.add(valid.files().get(0));
        }
        GroqReviewOutput output = new GroqReviewOutput(
                valid.overallAssessment(), valid.overallRisk(), valid.changeSummary(),
                tooManyFiles, valid.criticalFindings(), valid.endpointImpact(),
                valid.businessImpact(), valid.recommendation(), valid.reviewConfidence()
        );
        assertThat(GroqLlmReviewProvider.isValid(output)).isFalse();
    }

    @Test
    void excessiveFindingsCountFailsValidation() {
        GroqReviewOutput valid = validOutput();
        List<GroqReviewOutput.CriticalFinding> tooMany = new ArrayList<>();
        for (int i = 0; i < 51; i++) {
            tooMany.add(valid.criticalFindings().get(0));
        }
        GroqReviewOutput output = new GroqReviewOutput(
                valid.overallAssessment(), valid.overallRisk(), valid.changeSummary(),
                valid.files(), tooMany, valid.endpointImpact(),
                valid.businessImpact(), valid.recommendation(), valid.reviewConfidence()
        );
        assertThat(GroqLlmReviewProvider.isValid(output)).isFalse();
    }

    @Test
    void excessiveStringLengthFailsValidation() {
        GroqReviewOutput valid = validOutput();
        String hugeString = "a".repeat(4001);
        GroqReviewOutput output = new GroqReviewOutput(
                hugeString, valid.overallRisk(), valid.changeSummary(),
                valid.files(), valid.criticalFindings(), valid.endpointImpact(),
                valid.businessImpact(), valid.recommendation(), valid.reviewConfidence()
        );
        assertThat(GroqLlmReviewProvider.isValid(output)).isFalse();
    }

    @Test
    void reviewDecisionNormalizesAndBoundsInputs() {
        ReviewDecision decision = new ReviewDecision(
                " critical ",
                150.0,
                "a".repeat(5000),
                List.of("f1", "   ", "f2"),
                "groq",
                false,
                validOutput()
        );
        assertThat(decision.decision()).isEqualTo("CRITICAL");
        assertThat(decision.score()).isEqualTo(0.0); // out of bounds clamped to 0.0
        assertThat(decision.summary()).hasSize(4000);
        assertThat(decision.findings()).containsExactly("f1", "f2");
    }

    @Test
    void structuredReviewSurvivesFullRoundTripLifecycle() throws Exception {
        GroqReviewOutput originalOutput = validOutput();
        ReviewDecision originalDecision = new ReviewDecision(
                "CRITICAL",
                95.0,
                originalOutput.overallAssessment(),
                List.of("Critical logic regression"),
                "groq",
                false,
                originalOutput
        );

        // 1. Serialization (persistence to pull_requests.review_result TEXT column)
        String storedJson = objectMapper.writeValueAsString(originalDecision);
        assertThat(storedJson).contains("\"decision\":\"CRITICAL\"", "overallAssessment", "OrderService.java");

        // 2. Deserialization (as done in PullRequestService.parseReview)
        ReviewDecision restoredDecision = objectMapper.readValue(storedJson, ReviewDecision.class);
        assertThat(restoredDecision.decision()).isEqualTo("CRITICAL");
        assertThat(restoredDecision.score()).isEqualTo(95.0);
        assertThat(restoredDecision.fallback()).isFalse();
        assertThat(restoredDecision.analysis()).isNotNull();

        GroqReviewOutput restoredAnalysis = restoredDecision.analysis();
        assertThat(restoredAnalysis.overallRisk().level()).isEqualTo(GroqReviewOutput.RiskLevel.CRITICAL);
        assertThat(restoredAnalysis.overallRisk().score()).isEqualTo(95.0);
        assertThat(restoredAnalysis.overallRisk().confidence()).isEqualTo(0.95);
        assertThat(restoredAnalysis.files()).hasSize(1);

        GroqReviewOutput.FileAnalysis file = restoredAnalysis.files().get(0);
        assertThat(file.file()).isEqualTo("src/main/OrderService.java");
        assertThat(file.riskCategories()).contains(
                GroqReviewOutput.RiskCategory.BUSINESS_LOGIC_CHANGE,
                GroqReviewOutput.RiskCategory.RUNTIME_BREAKING
        );
        assertThat(file.affectedEndpoints()).hasSize(1);
        assertThat(file.affectedEndpoints().get(0).path()).isEqualTo("/api/orders");

        assertThat(restoredAnalysis.criticalFindings()).hasSize(1);
        assertThat(restoredAnalysis.criticalFindings().get(0).severity()).isEqualTo(GroqReviewOutput.RiskLevel.CRITICAL);
        assertThat(restoredAnalysis.criticalFindings().get(0).category()).isEqualTo(GroqReviewOutput.RiskCategory.BUSINESS_LOGIC_CHANGE);

        assertThat(restoredAnalysis.businessImpact().affectedCapability()).isEqualTo("Order checkout and fulfillment");
        assertThat(restoredAnalysis.recommendation()).contains("Review batch job compatibility");
    }

    @Test
    void legacyReviewDecisionWithoutAnalysisRemainsCompatible() throws Exception {
        String legacyJson = """
                {
                  "decision": "HIGH",
                  "score": 82.0,
                  "summary": "Legacy review summary",
                  "findings": ["Finding 1", "Finding 2"],
                  "provider": "openai",
                  "fallback": false
                }
                """;
        ReviewDecision decision = objectMapper.readValue(legacyJson, ReviewDecision.class);
        assertThat(decision.decision()).isEqualTo("HIGH");
        assertThat(decision.score()).isEqualTo(82.0);
        assertThat(decision.findings()).hasSize(2);
        assertThat(decision.provider()).isEqualTo("openai");
        assertThat(decision.fallback()).isFalse();
        assertThat(decision.analysis()).isNull();
    }
}
