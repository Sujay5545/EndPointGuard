package com.endpointguard.pullrequest.service;

import com.endpointguard.common.config.AppProperties;
import com.endpointguard.common.exception.ResourceNotFoundException;
import com.endpointguard.common.time.UtcDateTime;
import com.endpointguard.common.exception.WebhookSignatureException;
import com.endpointguard.github.GithubChangedFile;
import com.endpointguard.github.GithubRestApiClient;
import com.endpointguard.github.GithubWebhookSignatureVerifier;
import com.endpointguard.praffected.service.PrAffectedEndpointService;
import com.endpointguard.risk.service.RiskEvaluationService;
import com.endpointguard.risk.service.PostMergeMonitoringService;
import com.endpointguard.review.service.ReviewContextSanitizer;
import com.endpointguard.review.service.PullRequestReviewRequested;
import com.endpointguard.pullrequest.domain.PrChangedFile;
import com.endpointguard.pullrequest.domain.PullRequest;
import com.endpointguard.pullrequest.domain.WebhookEvent;
import com.endpointguard.pullrequest.repository.PrChangedFileRepository;
import com.endpointguard.pullrequest.repository.PullRequestRepository;
import com.endpointguard.pullrequest.repository.WebhookEventRepository;
import com.endpointguard.repository.domain.GithubRepository;
import com.endpointguard.repository.repository.GithubRepositoryRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class GithubWebhookService {

    private final GithubRepositoryRepository githubRepositoryRepository;
    private final PullRequestRepository pullRequestRepository;
    private final PrChangedFileRepository prChangedFileRepository;
    private final PrAffectedEndpointService prAffectedEndpointService;
    private final PostMergeMonitoringService postMergeMonitoringService;
    private final GithubRestApiClient githubRestApiClient;
    private final WebhookEventRepository webhookEventRepository;
    private final RiskEvaluationService riskEvaluationService;
    private final ApplicationEventPublisher eventPublisher;
    private final AppProperties appProperties;
    private final ObjectMapper objectMapper;

    @Transactional
    public void processWebhook(String rawPayload, String eventType, String deliveryId) {
        processWebhook(rawPayload, eventType, deliveryId, null);
    }

    @Transactional
    public void processWebhook(String rawPayload, String eventType, String deliveryId, String signatureHeader) {
        if (!"pull_request".equalsIgnoreCase(eventType)) {
            return;
        }

        try {
            JsonNode payload = objectMapper.readTree(rawPayload);
            JsonNode repoNode = payload.get("repository");
            JsonNode prNode = payload.get("pull_request");
            if (repoNode == null || prNode == null) {
                return;
            }

            String repoFullName = repoNode.path("full_name").asText(null);
            if (repoFullName == null || repoFullName.isBlank()) {
                return;
            }

            String configuredSecret = resolveWebhookSecret(repoFullName);
            if (configuredSecret != null && !configuredSecret.isBlank()) {
                GithubWebhookSignatureVerifier.verify(rawPayload, signatureHeader, configuredSecret, deliveryId);
            } else if (appProperties.getGithub().isRequireWebhookSignature()) {
                throw new WebhookSignatureException("GitHub webhook signature verification is not configured");
            }

            GithubRepository repository = githubRepositoryRepository.findByGithubRepoFullNameIgnoreCase(repoFullName)
                    .orElseThrow(() -> new ResourceNotFoundException("Repository", 0L));

            Integer prNumber = prNode.path("number").asInt(0);
            if (prNumber <= 0) {
                return;
            }

            WebhookEvent webhookEvent = claimDelivery(deliveryId, eventType, repoFullName);
            if (deliveryId != null && !deliveryId.isBlank() && webhookEvent == null) {
                log.info("Ignoring duplicate GitHub delivery {}", deliveryId);
                return;
            }

            PullRequest pullRequest = pullRequestRepository.findByRepositoryAndGithubPrNumber(repository, prNumber)
                    .orElseGet(() -> PullRequest.builder()
                            .repository(repository)
                            .githubPrNumber(prNumber)
                            .title(prNode.path("title").asText("Untitled PR"))
                            .author(prNode.path("user").path("login").asText("unknown"))
                            .openedAt(parseDate(prNode.path("created_at").asText(null)))
                            .headSha(prNode.path("head").path("sha").asText(null))
                            .status(prNode.path("state").asText("OPEN").toUpperCase())
                            .build());

            pullRequest.setTitle(prNode.path("title").asText(pullRequest.getTitle()));
            if (prNode.hasNonNull("body")) {
                pullRequest.setDescription(ReviewContextSanitizer.redactAndTruncate(
                        prNode.path("body").asText(), ReviewContextSanitizer.MAX_DESCRIPTION_CHARS,
                        "[PR description truncated]"));
            }
            pullRequest.setAuthor(prNode.path("user").path("login").asText(pullRequest.getAuthor()));
            pullRequest.setHeadSha(prNode.path("head").path("sha").asText(pullRequest.getHeadSha()));
                pullRequest.setStatus(prNode.hasNonNull("merged_at") ? "MERGED"
                    : prNode.path("state").asText(pullRequest.getStatus()).toUpperCase());
            if (prNode.hasNonNull("merged_at") && !prNode.path("merged_at").asText().isBlank()) {
                pullRequest.setMergedAt(parseDate(prNode.path("merged_at").asText()));
            }
            if (prNode.hasNonNull("closed_at") && !prNode.path("closed_at").asText().isBlank()) {
                pullRequest.setClosedAt(parseDate(prNode.path("closed_at").asText()));
            }
            if (pullRequest.getOpenedAt() == null) {
                pullRequest.setOpenedAt(parseDate(prNode.path("created_at").asText(null)));
            }
            pullRequestRepository.save(pullRequest);

            prChangedFileRepository.deleteByPullRequest_Id(pullRequest.getId());
            List<GithubChangedFile> githubFiles = new ArrayList<>();
            JsonNode filesNode = payload.get("files");
            if (filesNode != null && filesNode.isArray() && !filesNode.isEmpty()) {
                Iterator<JsonNode> it = filesNode.elements();
                while (it.hasNext()) {
                    JsonNode fileNode = it.next();
                    String filePath = fileNode.path("filename").asText(null);
                    if (filePath == null || filePath.isBlank()) {
                        continue;
                    }
                    githubFiles.add(new GithubChangedFile(filePath,
                            fileNode.path("additions").asInt(0),
                            fileNode.path("deletions").asInt(0),
                            fileNode.path("patch").asText(null)));
                }
            }

            if (githubFiles.isEmpty() && githubRestApiClient.isConfigured()) {
                try {
                    githubFiles = githubRestApiClient.getPullRequestFiles(repoFullName, prNumber);
                } catch (RuntimeException exception) {
                    log.warn("Could not retrieve changed files for {}/#{}: {}", repoFullName, prNumber, exception.getMessage());
                }
            }

            List<PrChangedFile> changedFiles = githubFiles.stream()
                    .filter(file -> file.filename() != null && !file.filename().isBlank())
                    .map(file -> prChangedFileRepository.save(PrChangedFile.builder()
                            .pullRequest(pullRequest)
                            .filePath(file.filename())
                            .additions(file.additions())
                            .deletions(file.deletions())
                            .patch(file.patch())
                            .build()))
                    .toList();

            prAffectedEndpointService.mapChangedFilesToAffectedEndpoints(pullRequest, changedFiles);
                String action = payload.path("action").asText("").toLowerCase(java.util.Locale.ROOT);
                boolean actionableOpenUpdate = "OPEN".equalsIgnoreCase(pullRequest.getStatus())
                    && List.of("opened", "reopened", "synchronize", "ready_for_review", "edited").contains(action);
                if (actionableOpenUpdate) {
                    long diffSize = changedFiles.stream()
                            .mapToLong(file -> (long) file.getAdditions() + file.getDeletions())
                            .sum();
                    riskEvaluationService.evaluateAutomatically(pullRequest, diffSize);
                    pullRequest.setReviewStatus("PENDING");
                    pullRequest.setReviewResult(null);
                    pullRequestRepository.save(pullRequest);
                    eventPublisher.publishEvent(new PullRequestReviewRequested(pullRequest.getId()));
                }
            postMergeMonitoringService.startForMergedPullRequest(pullRequest);
            if (webhookEvent != null) {
                webhookEvent.setProcessingStatus("PROCESSED");
                webhookEvent.setProcessedAt(UtcDateTime.now());
                webhookEventRepository.save(webhookEvent);
            }
        } catch (IllegalArgumentException | SecurityException e) {
            log.warn("Rejected GitHub webhook {}: {}", deliveryId, e.getMessage());
            throw e;
        } catch (Exception e) {
            log.error("Failed to process GitHub webhook {}: {}", deliveryId, e.getMessage(), e);
            throw new IllegalStateException("Unable to process GitHub webhook payload", e);
        }
    }

    private WebhookEvent claimDelivery(String deliveryId, String eventType, String repositoryFullName) {
        if (deliveryId == null || deliveryId.isBlank()) return null;
        var existing = webhookEventRepository.findByGithubDeliveryId(deliveryId);
        if (existing.isPresent()) {
            WebhookEvent event = existing.get();
            if ("PROCESSED".equals(event.getProcessingStatus())
                    || "PROCESSING".equals(event.getProcessingStatus())) {
                return null;
            }
            event.setProcessingStatus("PROCESSING");
            event.setProcessedAt(null);
            return webhookEventRepository.save(event);
        }
        return webhookEventRepository.saveAndFlush(WebhookEvent.builder()
                .githubDeliveryId(deliveryId)
                .eventType(eventType)
                .repositoryFullName(repositoryFullName)
                .payload(JsonNodeFactory.instance.objectNode())
                .processingStatus("PROCESSING")
                .build());
    }

    private String resolveWebhookSecret(String repoFullName) {
        if (repoFullName == null || repoFullName.isBlank()) {
            return null;
        }
        GithubRepository repository = githubRepositoryRepository.findByGithubRepoFullNameIgnoreCase(repoFullName).orElse(null);
        if (repository == null || repository.getWebhookSecretRef() == null || repository.getWebhookSecretRef().isBlank()) {
            return appProperties.getGithub().getWebhookSecret();
        }
        String configured = appProperties.getGithub().getWebhookSecret();
        if (configured != null && !configured.isBlank()) {
            return configured;
        }
        return null;
    }

    private LocalDateTime parseDate(String value) {
        if (value == null || value.isBlank()) {
            return UtcDateTime.now();
        }
        try {
            return OffsetDateTime.parse(value).withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
        } catch (DateTimeParseException ignored) {
            try {
                return LocalDateTime.parse(value);
            } catch (DateTimeParseException fallback) {
                return UtcDateTime.now();
            }
        }
    }
}
