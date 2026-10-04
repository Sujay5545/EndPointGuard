package com.endpointguard.pullrequest.dto;

import java.time.LocalDateTime;
import java.util.List;
import com.endpointguard.review.dto.ReviewDecision;

public record PullRequestResponse(
        Long id,
        Long projectId,
        Long repositoryId,
        String repositoryName,
        Integer githubPrNumber,
        String title,
        String author,
        String status,
        LocalDateTime openedAt,
        LocalDateTime mergedAt,
        LocalDateTime closedAt,
        int changedFileCount,
        int affectedEndpointCount,
        String latestRiskTier,
        Double latestRiskScore
) {
    public record ChangedFile(
            Long id,
            String filePath,
            Integer additions,
            Integer deletions,
            String patch
    ) {}

    public record AffectedEndpoint(
            Long endpointId,
            String method,
            String pathPattern,
            String criticality
    ) {}

    public record RiskHistoryEntry(
            Long id,
            Double score,
            String tier,
            LocalDateTime evaluatedAt,
            String evaluationStatus,
            List<String> dataQualityNotes
    ) {}

    public record Detail(
            Long id,
            Long projectId,
            Long repositoryId,
            String repositoryName,
            Integer githubPrNumber,
            String title,
            String author,
            String status,
            LocalDateTime openedAt,
            LocalDateTime mergedAt,
            LocalDateTime closedAt,
            String headSha,
            List<ChangedFile> changedFiles,
            List<AffectedEndpoint> affectedEndpoints,
                        List<RiskHistoryEntry> riskHistory,
                        String reviewStatus,
                        ReviewDecision review
    ) {}
}
