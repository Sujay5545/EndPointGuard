package com.endpointguard.review.dto;

import java.util.List;

public record ReviewRequest(
        String repository,
        String changeSummary,
        String diff,
        String affectedEndpoints,
        Double computedRiskScore,
        List<ChangedFile> changedFiles,
        String pullRequestDescription
) {
    public ReviewRequest(
            String repository,
            String changeSummary,
            String diff,
            String affectedEndpoints,
            Double computedRiskScore) {
        this(repository, changeSummary, diff, affectedEndpoints, computedRiskScore, List.of(), null);
    }

    public ReviewRequest(
            String repository,
            String changeSummary,
            String diff,
            String affectedEndpoints,
            Double computedRiskScore,
            List<ChangedFile> changedFiles) {
        this(repository, changeSummary, diff, affectedEndpoints, computedRiskScore, changedFiles, null);
    }

    public ReviewRequest {
        if (repository == null || repository.isBlank()) {
            repository = "unknown";
        }
        if (changeSummary == null || changeSummary.isBlank()) {
            changeSummary = "No summary supplied";
        }
        if (diff == null) {
            diff = "";
        }
        if (affectedEndpoints == null) {
            affectedEndpoints = "";
        }
        if (computedRiskScore == null) {
            computedRiskScore = 0.0;
        }
        changedFiles = changedFiles == null ? List.of() : List.copyOf(changedFiles);
        pullRequestDescription = pullRequestDescription == null ? "" : pullRequestDescription;
    }

    public record ChangedFile(String file, int additions, int deletions, String patch) {
        public ChangedFile {
            if (file == null || file.isBlank()) {
                file = "unknown";
            }
            patch = patch == null ? "Patch unavailable" : patch;
        }
    }
}
