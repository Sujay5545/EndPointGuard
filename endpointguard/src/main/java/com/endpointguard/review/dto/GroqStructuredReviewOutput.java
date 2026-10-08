package com.endpointguard.review.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GroqStructuredReviewOutput(
        String overallAssessment,
        String overallRiskLevel,
        Double overallRiskScore,
        Double overallRiskConfidence,
        String overallRiskReason,
        String changeSummary,
        List<FileAnalysis> files,
        List<CriticalFinding> criticalFindings,
        List<EndpointImpact> endpointImpact,
        String businessImpactSummary,
        String businessImpactAffectedCapability,
        Double businessImpactConfidence,
        String recommendation,
        Double reviewConfidence,
        String riskAlignmentDeterministicRisk,
        String riskAlignmentLlmRisk,
        String riskAlignment,
        String riskAlignmentExplanation
) {
    public GroqStructuredReviewOutput {
        files = files == null ? List.of() : files.stream().filter(java.util.Objects::nonNull).toList();
        criticalFindings = criticalFindings == null
                ? List.of() : criticalFindings.stream().filter(java.util.Objects::nonNull).toList();
        endpointImpact = endpointImpact == null
                ? List.of() : endpointImpact.stream().filter(java.util.Objects::nonNull).toList();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record FileAnalysis(
            String file,
            String changeSummary,
            String whatChanged,
            String whatItDoes,
            String businessImpact,
            List<String> riskCategories,
            String technicalImpact,
            List<String> evidence,
            Double confidence) {
        public FileAnalysis {
            riskCategories = riskCategories == null
                    ? List.of() : riskCategories.stream().filter(java.util.Objects::nonNull).toList();
            evidence = evidence == null ? List.of() : evidence.stream().filter(java.util.Objects::nonNull).toList();
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CriticalFinding(
            String severity,
            String category,
            String file,
            String finding,
            String whyItMatters,
            String evidence,
            String recommendedAction) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record EndpointImpact(
            String sourceFile,
            String method,
            String path,
            String criticality,
            String impact,
            String reason) {
    }
}