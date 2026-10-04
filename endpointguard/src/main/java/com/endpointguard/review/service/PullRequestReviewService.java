package com.endpointguard.review.service;

import com.endpointguard.audit.service.AuditLogService;
import com.endpointguard.praffected.repository.PrAffectedEndpointRepository;
import com.endpointguard.pullrequest.domain.PullRequest;
import com.endpointguard.pullrequest.repository.PrChangedFileRepository;
import com.endpointguard.pullrequest.repository.PullRequestRepository;
import com.endpointguard.risk.repository.RiskAssessmentRepository;
import com.endpointguard.review.dto.ReviewDecision;
import com.endpointguard.review.dto.ReviewRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
@Slf4j
public class PullRequestReviewService {

    private static final int MAX_FILES = 25;
    private static final int MAX_DIFF_CHARS = 16_000;
        private static final int MAX_ENDPOINT_CONTEXT_CHARS = 6_000;
    private static final Pattern ASSIGNED_SECRET = Pattern.compile(
            "(?i)((?:api[_-]?key|token|password|secret)\\s*[:=]\\s*[\\\"']?)[^\\s\\\"',;]+"
    );
    private static final Pattern BEARER_TOKEN = Pattern.compile("(?i)Bearer\\s+[A-Za-z0-9._~+/=-]+");
    private static final Pattern GITHUB_TOKEN = Pattern.compile("\\bgh[pousr]_[A-Za-z0-9_]{20,}\\b");

    private final PullRequestRepository pullRequestRepository;
    private final PrChangedFileRepository prChangedFileRepository;
    private final PrAffectedEndpointRepository prAffectedEndpointRepository;
    private final RiskAssessmentRepository riskAssessmentRepository;
    private final LlmReviewService llmReviewService;
    private final AuditLogService auditLogService;
    private final ObjectMapper objectMapper;

    @Transactional
    public void review(Long pullRequestId) {
        PullRequest pullRequest = pullRequestRepository.findById(pullRequestId).orElse(null);
        if (pullRequest == null || !"PENDING".equals(pullRequest.getReviewStatus())) {
            return;
        }

        pullRequest.setReviewStatus("PROCESSING");
        pullRequestRepository.save(pullRequest);
        String ownerEmail = pullRequest.getRepository().getProject().getOwner().getEmail();

        try {
            var latestRisk = riskAssessmentRepository.findByPullRequestIdOrderByComputedAtDesc(pullRequestId)
                    .stream().findFirst().orElse(null);
            var affectedEndpoints = prAffectedEndpointRepository
                    .findByPullRequestOrderByEndpointAsc(pullRequest);
            String endpointContext = affectedEndpoints.isEmpty()
                    ? "No configured endpoints were matched."
                    : affectedEndpoints.stream()
                    .map(mapping -> mapping.getEndpoint().getMethod() + " "
                            + mapping.getEndpoint().getPathPattern() + " (criticality "
                            + mapping.getEndpoint().getCriticality() + ")")
                    .collect(java.util.stream.Collectors.joining("\n"));
            if (latestRisk != null) {
                endpointContext += "\nDeterministic risk tier: " + latestRisk.getTier()
                        + "; score: " + latestRisk.getScore()
                        + "; evaluation status: " + latestRisk.getEvaluationStatus()
                        + "; factors: " + latestRisk.getFactorBreakdown()
                        + "; data quality: " + latestRisk.getDataQualityNotes();
            } else {
                endpointContext += "\nDeterministic risk: INSUFFICIENT_DATA (no affected endpoint assessment).";
            }
                        endpointContext = ReviewContextSanitizer.redactAndTruncate(
                                        endpointContext, MAX_ENDPOINT_CONTEXT_CHARS, "[Endpoint/risk context truncated]");

            var changedFiles = prChangedFileRepository.findByPullRequestOrderByFilePathAsc(pullRequest).stream()
                    .limit(MAX_FILES)
                    .toList();
            String diff = changedFiles.stream()
                    .map(file -> "FILE " + file.getFilePath() + " (+" + file.getAdditions() + "/-"
                            + file.getDeletions() + ")\n" + (file.getPatch() == null ? "Patch unavailable" : file.getPatch()))
                    .collect(java.util.stream.Collectors.joining("\n\n"));
            diff = redact(diff);
            if (diff.length() > MAX_DIFF_CHARS) {
                diff = diff.substring(0, MAX_DIFF_CHARS) + "\n[Diff truncated]";
            }

            List<ReviewRequest.ChangedFile> reviewFiles = new java.util.ArrayList<>();
            int remainingPatchChars = MAX_DIFF_CHARS;
            for (var file : changedFiles) {
                String patch = redact(file.getPatch() == null ? "Patch unavailable" : file.getPatch());
                int patchLength = Math.min(patch.length(), remainingPatchChars);
                String boundedPatch = patch.substring(0, patchLength);
                if (patchLength < patch.length()) {
                    boundedPatch += "\n[File patch truncated]";
                }
                reviewFiles.add(new ReviewRequest.ChangedFile(
                        file.getFilePath(), file.getAdditions(), file.getDeletions(), boundedPatch));
                remainingPatchChars -= patchLength;
            }

            Double deterministicScore = latestRisk == null ? null : latestRisk.getScore() * 100.0;
            ReviewDecision decision = llmReviewService.review(new ReviewRequest(
                    pullRequest.getRepository().getGithubRepoFullName(),
                    "PR #" + pullRequest.getGithubPrNumber() + ": " + pullRequest.getTitle()
                            + " by " + pullRequest.getAuthor() + " (" + pullRequest.getStatus() + ")",
                    diff,
                    endpointContext,
                    deterministicScore,
                    reviewFiles,
                    pullRequest.getDescription() == null ? "" : pullRequest.getDescription()));

            pullRequest.setReviewResult(objectMapper.writeValueAsString(decision));
            pullRequest.setReviewStatus(decision.fallback() ? "FALLBACK" : "COMPLETED");
            pullRequestRepository.save(pullRequest);
            auditLogService.record("PULL_REQUEST", pullRequestId.toString(),
                    decision.fallback() ? "LLM_REVIEW_FALLBACK" : "LLM_REVIEW_COMPLETED", ownerEmail,
                    Map.of("provider", decision.provider(), "fallback", decision.fallback()));
        } catch (Exception exception) {
            log.warn("Pull request review failed for {}: {}", pullRequestId, exception.getMessage());
            pullRequest.setReviewStatus("FAILED");
            pullRequestRepository.save(pullRequest);
            auditLogService.record("PULL_REQUEST", pullRequestId.toString(), "LLM_REVIEW_FAILED", ownerEmail,
                    Map.of("reason", "Review could not be completed"));
        }
    }

    private static String redact(String value) {
        String redacted = ASSIGNED_SECRET.matcher(value).replaceAll("$1[REDACTED]");
        redacted = BEARER_TOKEN.matcher(redacted).replaceAll("Bearer [REDACTED]");
        return GITHUB_TOKEN.matcher(redacted).replaceAll("[REDACTED_GITHUB_TOKEN]");
    }
}