package com.endpointguard.pullrequest.service;

import com.endpointguard.common.exception.ResourceNotFoundException;
import com.endpointguard.common.exception.BadRequestException;
import com.endpointguard.endpoint.domain.Endpoint;
import com.endpointguard.praffected.domain.PrAffectedEndpoint;
import com.endpointguard.praffected.repository.PrAffectedEndpointRepository;
import com.endpointguard.pullrequest.domain.PrChangedFile;
import com.endpointguard.pullrequest.domain.PullRequest;
import com.endpointguard.pullrequest.dto.PullRequestResponse;
import com.endpointguard.pullrequest.dto.PullRequestPageResponse;
import com.endpointguard.pullrequest.repository.PrChangedFileRepository;
import com.endpointguard.pullrequest.repository.PullRequestRepository;
import com.endpointguard.risk.domain.RiskAssessment;
import com.endpointguard.risk.repository.RiskAssessmentRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

import java.util.Comparator;
import java.util.List;

@Service
@RequiredArgsConstructor
public class PullRequestService {

    private final PullRequestRepository pullRequestRepository;
    private final PrChangedFileRepository prChangedFileRepository;
    private final PrAffectedEndpointRepository prAffectedEndpointRepository;
    private final RiskAssessmentRepository riskAssessmentRepository;
    private final ObjectMapper objectMapper;

        @Transactional(readOnly = true)
        public PullRequestPageResponse listOwnedForProject(
            Long projectId, String ownerEmail, int requestedPage, int requestedSize, String requestedStatus) {
        int pageNumber = Math.max(0, requestedPage);
        int pageSize = Math.max(1, Math.min(requestedSize, 100));
        String status = requestedStatus == null || requestedStatus.isBlank()
            ? null
            : requestedStatus.trim().toUpperCase(java.util.Locale.ROOT);
        if (status != null && !List.of("OPEN", "CLOSED", "MERGED").contains(status)) {
            throw new BadRequestException("Status filter must be OPEN, CLOSED, or MERGED");
        }
        Page<PullRequest> results = pullRequestRepository.findOwnedPage(projectId, ownerEmail,
            status, PageRequest.of(pageNumber, pageSize));
        return new PullRequestPageResponse(results.getContent().stream().map(this::toListResponse).toList(),
            results.getNumber(), results.getSize(), results.getTotalElements(), results.getTotalPages(),
            results.hasNext(), results.hasPrevious());
        }

    @Transactional(readOnly = true)
        public List<PullRequestResponse> listAllOwnedForProject(Long projectId, String ownerEmail) {
        return pullRequestRepository.findOwnedForProject(projectId, ownerEmail).stream()
                .map(this::toListResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public PullRequestResponse.Detail getOwnedDetail(Long projectId, Long pullRequestId, String ownerEmail) {
        PullRequest pullRequest = pullRequestRepository.findOwnedInProject(pullRequestId, projectId, ownerEmail)
                .orElseThrow(() -> new ResourceNotFoundException("Pull request", pullRequestId));

        List<PrChangedFile> changedFiles = prChangedFileRepository.findByPullRequestOrderByFilePathAsc(pullRequest);
        List<PrAffectedEndpoint> affectedEndpoints = prAffectedEndpointRepository.findByPullRequestOrderByEndpointAsc(pullRequest);
        List<RiskAssessment> riskAssessments = riskAssessmentRepository.findByPullRequestIdOrderByComputedAtDesc(pullRequestId);

        return new PullRequestResponse.Detail(
                pullRequest.getId(),
                pullRequest.getRepository().getProject().getId(),
                pullRequest.getRepository().getId(),
                pullRequest.getRepository().getGithubRepoFullName(),
                pullRequest.getGithubPrNumber(),
                pullRequest.getTitle(),
                pullRequest.getAuthor(),
                pullRequest.getStatus(),
                pullRequest.getOpenedAt(),
                pullRequest.getMergedAt(),
                pullRequest.getClosedAt(),
                pullRequest.getHeadSha(),
                changedFiles.stream().map(this::toChangedFile).toList(),
                affectedEndpoints.stream().map(this::toAffectedEndpoint).toList(),
                riskAssessments.stream().map(this::toRiskHistoryEntry).toList(),
                pullRequest.getReviewStatus(),
                parseReview(pullRequest.getReviewResult())
        );
    }

    private com.endpointguard.review.dto.ReviewDecision parseReview(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return objectMapper.readValue(value, com.endpointguard.review.dto.ReviewDecision.class);
        } catch (Exception ignored) {
            return null;
        }
    }

    private PullRequestResponse toListResponse(PullRequest pullRequest) {
        List<PrChangedFile> changedFiles = prChangedFileRepository.findByPullRequestOrderByFilePathAsc(pullRequest);
        List<PrAffectedEndpoint> affectedEndpoints = prAffectedEndpointRepository.findByPullRequestOrderByEndpointAsc(pullRequest);
        List<RiskAssessment> riskAssessments = riskAssessmentRepository.findByPullRequestIdOrderByComputedAtDesc(pullRequest.getId());

        RiskAssessment latest = riskAssessments.isEmpty() ? null : riskAssessments.get(0);

        return new PullRequestResponse(
                pullRequest.getId(),
                pullRequest.getRepository().getProject().getId(),
                pullRequest.getRepository().getId(),
                pullRequest.getRepository().getGithubRepoFullName(),
                pullRequest.getGithubPrNumber(),
                pullRequest.getTitle(),
                pullRequest.getAuthor(),
                pullRequest.getStatus(),
                pullRequest.getOpenedAt(),
                pullRequest.getMergedAt(),
                pullRequest.getClosedAt(),
                changedFiles.size(),
                affectedEndpoints.size(),
                latest == null ? null : latest.getTier(),
                latest == null ? null : latest.getScore()
        );
    }

    private PullRequestResponse.ChangedFile toChangedFile(PrChangedFile file) {
        return new PullRequestResponse.ChangedFile(
                file.getId(),
                file.getFilePath(),
                file.getAdditions(),
                file.getDeletions(),
                file.getPatch()
        );
    }

    private PullRequestResponse.AffectedEndpoint toAffectedEndpoint(PrAffectedEndpoint mapping) {
        Endpoint endpoint = mapping.getEndpoint();
        return new PullRequestResponse.AffectedEndpoint(
                endpoint.getId(),
                endpoint.getMethod(),
                endpoint.getPathPattern(),
                endpoint.getCriticality() == null ? null : endpoint.getCriticality().name()
        );
    }

    private PullRequestResponse.RiskHistoryEntry toRiskHistoryEntry(RiskAssessment assessment) {
        return new PullRequestResponse.RiskHistoryEntry(
                assessment.getId(),
                assessment.getScore(),
                assessment.getTier(),
                assessment.getComputedAt(),
                assessment.getEvaluationStatus(),
                parseDataQualityNotes(assessment.getDataQualityNotes())
        );
    }

    private List<String> parseDataQualityNotes(com.fasterxml.jackson.databind.JsonNode notes) {
        if (notes != null && notes.isTextual()) {
            try {
                notes = objectMapper.readTree(notes.asText());
            } catch (Exception ignored) {
                return List.of();
            }
        }
        if (notes == null || !notes.isArray()) return List.of();
        return java.util.stream.StreamSupport.stream(notes.spliterator(), false)
                .map(com.fasterxml.jackson.databind.JsonNode::asText)
                .toList();
    }
}
