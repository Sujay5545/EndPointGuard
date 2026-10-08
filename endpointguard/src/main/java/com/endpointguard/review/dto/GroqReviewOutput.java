package com.endpointguard.review.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Locale;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GroqReviewOutput(
        String overallAssessment,
        OverallRisk overallRisk,
        String changeSummary,
        List<FileAnalysis> files,
        List<CriticalFinding> criticalFindings,
        List<EndpointImpact> endpointImpact,
        BusinessImpact businessImpact,
        String recommendation,
        Double reviewConfidence,
        RiskAlignment riskAlignment
) {
    public GroqReviewOutput(
            String overallAssessment,
            OverallRisk overallRisk,
            String changeSummary,
            List<FileAnalysis> files,
            List<CriticalFinding> criticalFindings,
            List<EndpointImpact> endpointImpact,
            BusinessImpact businessImpact,
            String recommendation,
            Double reviewConfidence) {
        this(overallAssessment, overallRisk, changeSummary, files, criticalFindings, endpointImpact, businessImpact,
                recommendation, reviewConfidence, null);
    }

    public GroqReviewOutput {
        files = files == null ? List.of() : files.stream().filter(java.util.Objects::nonNull).toList();
        criticalFindings = criticalFindings == null ? List.of() : criticalFindings.stream().filter(java.util.Objects::nonNull).toList();
        endpointImpact = endpointImpact == null ? List.of() : endpointImpact.stream().filter(java.util.Objects::nonNull).toList();
        if (riskAlignment != null) {
            riskAlignment = new RiskAlignment(
                    riskAlignment.deterministicRisk(),
                    riskAlignment.llmRisk(),
                    riskAlignment.alignment(),
                    riskAlignment.explanation());
        }
    }

    public enum RiskLevel {
        LOW, MEDIUM, HIGH, CRITICAL;

        @JsonCreator
        public static RiskLevel fromString(String value) {
            if (value == null || value.isBlank()) {
                return null;
            }
            try {
                return RiskLevel.valueOf(value.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
    }

    public enum RiskCategory {
        BUILD_BREAKING,
        RUNTIME_BREAKING,
        FUNCTIONAL_REGRESSION,
        API_BEHAVIOR_CHANGE,
        SECURITY_RISK,
        DATA_RISK,
        PERFORMANCE_RISK,
        BUSINESS_LOGIC_CHANGE,
        LOW_IMPACT,
        DOCUMENTATION_ONLY,
        FORMATTING_ONLY;

        @JsonCreator
        public static RiskCategory fromString(String value) {
            if (value == null || value.isBlank()) {
                return null;
            }
            try {
                return RiskCategory.valueOf(value.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record OverallRisk(RiskLevel level, Double score, Double confidence, String reason) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record FileAnalysis(
            String file,
            String changeSummary,
            String whatChanged,
            String whatItDoes,
            String businessImpact,
            List<RiskCategory> riskCategories,
            String technicalImpact,
            List<String> evidence,
            List<EndpointImpact> affectedEndpoints,
            Double confidence) {
        public FileAnalysis {
            riskCategories = riskCategories == null ? List.of() : riskCategories.stream().filter(java.util.Objects::nonNull).toList();
            evidence = evidence == null ? List.of() : evidence.stream().filter(java.util.Objects::nonNull).toList();
            affectedEndpoints = affectedEndpoints == null ? List.of() : affectedEndpoints.stream().filter(java.util.Objects::nonNull).toList();
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CriticalFinding(
            RiskLevel severity,
            RiskCategory category,
            String file,
            String finding,
            String whyItMatters,
            String evidence,
            String recommendedAction) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record EndpointImpact(
            String method,
            String path,
            String criticality,
            String impact,
            String reason) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record BusinessImpact(String summary, String affectedCapability, Double confidence) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RiskAlignment(
            String deterministicRisk,
            String llmRisk,
            Alignment alignment,
            String explanation) {
        public enum Alignment {
            ALIGNED,
            DIFFERENT;

            @JsonCreator
            public static Alignment fromString(String value) {
                if (value == null || value.isBlank()) {
                    return null;
                }
                try {
                    return Alignment.valueOf(value.trim().toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException e) {
                    return null;
                }
            }
        }
    }
}