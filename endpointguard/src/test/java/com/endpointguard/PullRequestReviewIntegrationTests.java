package com.endpointguard;

import com.endpointguard.pullrequest.domain.PullRequest;
import com.endpointguard.pullrequest.repository.PullRequestRepository;
import com.endpointguard.repository.repository.GithubRepositoryRepository;
import com.endpointguard.risk.repository.RiskAssessmentRepository;
import com.endpointguard.review.dto.GroqReviewOutput;
import com.endpointguard.review.dto.ReviewDecision;
import com.endpointguard.review.dto.ReviewRequest;
import com.endpointguard.review.provider.LlmReviewProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "app.llm.provider=test")
class PullRequestReviewIntegrationTests {

    private static final AtomicReference<ReviewRequest> LAST_REQUEST = new AtomicReference<>();

    @TestConfiguration
    static class ReviewProviderConfiguration {
        @Bean
        LlmReviewProvider testReviewProvider() {
            return new LlmReviewProvider() {
                @Override
                public String providerName() {
                    return "test";
                }

                @Override
                public ReviewDecision review(ReviewRequest request) {
                    LAST_REQUEST.set(request);
                    GroqReviewOutput analysis = new GroqReviewOutput(
                            "Structured review completed from PR context",
                            new GroqReviewOutput.OverallRisk(
                                    GroqReviewOutput.RiskLevel.MEDIUM, 55.0, 0.86, "Contract change is advisory."),
                            "Review order response compatibility",
                            List.of(new GroqReviewOutput.FileAnalysis(
                                    "src/main/java/OrderController.java", "Updates the order response",
                                    "Changes the response payload", "Returns order data to API clients",
                                    "Clients may depend on the existing response shape",
                                    List.of(GroqReviewOutput.RiskCategory.BUSINESS_LOGIC_CHANGE),
                                    "The response contract may change",
                                    List.of("Patch changes the order response field."),
                                    List.of(new GroqReviewOutput.EndpointImpact(
                                            "GET", "/api/orders", "HIGH", "Response contract changed",
                                            "The changed controller maps to this endpoint.")),
                                    0.86)),
                            List.of(),
                            List.of(new GroqReviewOutput.EndpointImpact(
                                    "GET", "/api/orders", "HIGH", "Response contract changed",
                                    "The changed controller maps to this endpoint.")),
                            new GroqReviewOutput.BusinessImpact(
                                    "Order clients may need to adjust to the response change.", "Order lookup", 0.8),
                            "Review client compatibility before merge.",
                            0.86);
                    return new ReviewDecision("MEDIUM", 55.0, analysis.overallAssessment(),
                            List.of("Check the orders response compatibility"), "test", false, analysis);
                }
            };
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PullRequestRepository pullRequestRepository;

    @Autowired
    private GithubRepositoryRepository githubRepositoryRepository;

        @Autowired
        private RiskAssessmentRepository riskAssessmentRepository;

    @AfterEach
    void clearCapturedRequest() {
        LAST_REQUEST.set(null);
    }

    @Test
    void webhookPersistsServerRiskAndAsynchronousReviewForPrDetail() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String email = "review-" + suffix + "@example.com";
        String repositoryName = "endpointguard-e2e/review-" + suffix;

        String auth = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(java.util.Map.of(
                                "email", email, "password", "password123"))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String token = objectMapper.readTree(auth).path("token").asText();

        String project = mockMvc.perform(post("/api/projects")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Review Integration\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long projectId = objectMapper.readTree(project).path("id").asLong();

        String repository = mockMvc.perform(post("/api/projects/{projectId}/repositories", projectId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(java.util.Map.of(
                                "githubRepoFullName", repositoryName,
                                "webhookSecretRef", "LOCAL_TEST_WEBHOOK_SECRET"))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long repositoryId = objectMapper.readTree(repository).path("id").asLong();

        mockMvc.perform(post("/api/repositories/{repositoryId}/endpoints", repositoryId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"method":"GET","pathPattern":"/api/orders","criticality":"HIGH",
                                 "sourcePatterns":["**/OrderController.java"]}
                                """))
                .andExpect(status().isCreated());

        String githubPayload = """
                {
                  "action":"opened",
                  "repository":{"full_name":"%s"},
                                                                        "pull_request":{"number":42,"title":"Change order response","user":{"login":"reviewer"},
                                                                                "body":"This change preserves order response compatibility. password=super-secret-value Bearer abc.def.ghi ghp_abcdefghijklmnopqrstuvwxyz0123456789 %s",
                                                                                "state":"open","created_at":"2026-10-01T08:00:00Z","head":{"sha":"review-sha"}},
                  "files":[{"filename":"src/main/java/OrderController.java","additions":2,"deletions":0,
                    "patch":"+ password=super-secret-value\\n+ Bearer abc.def.ghi\\n+ ghp_abcdefghijklmnopqrstuvwxyz0123456789"}]
                }
                """.formatted(repositoryName, "context-padding ".repeat(400));
        mockMvc.perform(post("/api/webhooks/github")
                        .header("X-GitHub-Event", "pull_request")
                        .header("X-GitHub-Delivery", "review-" + suffix)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(githubPayload))
                .andExpect(status().isAccepted());

        var githubRepository = githubRepositoryRepository
                .findByGithubRepoFullNameIgnoreCase(repositoryName).orElseThrow();
        await().atMost(Duration.ofSeconds(8)).untilAsserted(() -> {
            PullRequest pullRequest = pullRequestRepository
                    .findByRepositoryAndGithubPrNumber(githubRepository, 42).orElseThrow();
            assertThat(pullRequest.getReviewStatus()).isEqualTo("COMPLETED");
            assertThat(pullRequest.getReviewResult()).contains("Structured review completed from PR context");
            assertThat(pullRequest.getDescription()).contains("order response compatibility")
                    .doesNotContain("super-secret-value", "abc.def.ghi", "ghp_abcdefghijklmnopqrstuvwxyz");
            assertThat(pullRequest.getReviewResult()).contains("src/main/java/OrderController.java", "BUSINESS_LOGIC_CHANGE")
                    .doesNotContain("super-secret-value", "abc.def.ghi", "ghp_abcdefghijklmnopqrstuvwxyz");
        });

        ReviewRequest reviewRequest = LAST_REQUEST.get();
        assertThat(reviewRequest).isNotNull();
        assertThat(reviewRequest.affectedEndpoints()).contains("/api/orders", "Deterministic risk tier:");
        assertThat(reviewRequest.computedRiskScore()).isNotNull();
        assertThat(reviewRequest.diff()).doesNotContain("super-secret-value", "Bearer abc.def.ghi", "ghp_abcdefghijklmnopqrstuvwxyz");
        assertThat(reviewRequest.changedFiles()).hasSize(1);
        assertThat(reviewRequest.pullRequestDescription())
                .contains("order response compatibility", "[PR description truncated]")
                .doesNotContain("super-secret-value", "abc.def.ghi", "ghp_abcdefghijklmnopqrstuvwxyz");
        assertThat(reviewRequest.pullRequestDescription()).hasSizeLessThanOrEqualTo(4_000);
        assertThat(reviewRequest.affectedEndpoints()).hasSizeLessThanOrEqualTo(6_000);
        ReviewRequest.ChangedFile changedFile = reviewRequest.changedFiles().get(0);
        assertThat(changedFile.file()).isEqualTo("src/main/java/OrderController.java");
        assertThat(changedFile.patch()).doesNotContain("super-secret-value", "Bearer abc.def.ghi", "ghp_abcdefghijklmnopqrstuvwxyz");

        String pullRequests = mockMvc.perform(get("/api/projects/{projectId}/pull-requests", projectId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        long pullRequestId = objectMapper.readTree(pullRequests).path("content").path(0).path("id").asLong();
        mockMvc.perform(get("/api/projects/{projectId}/pull-requests/{pullRequestId}", projectId, pullRequestId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reviewStatus").value("COMPLETED"))
                .andExpect(jsonPath("$.review.provider").value("test"))
                .andExpect(jsonPath("$.review.fallback").value(false))
                .andExpect(jsonPath("$.review.analysis.files[0].file").value("src/main/java/OrderController.java"))
                .andExpect(jsonPath("$.review.analysis.files[0].riskCategories[0]").value("BUSINESS_LOGIC_CHANGE"))
                .andExpect(jsonPath("$.riskHistory[0].tier").value("INSUFFICIENT_DATA"))
                .andExpect(jsonPath("$.riskHistory[0].evaluationStatus").value("INSUFFICIENT_DATA"));

        String unmatchedPayload = githubPayload.replace("\"number\":42", "\"number\":43")
                .replace("OrderController.java", "README.md");
        mockMvc.perform(post("/api/webhooks/github")
                        .header("X-GitHub-Event", "pull_request")
                        .header("X-GitHub-Delivery", "review-unmatched-" + suffix)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(unmatchedPayload))
                .andExpect(status().isAccepted());
        PullRequest unmatchedPullRequest = pullRequestRepository
                .findByRepositoryAndGithubPrNumber(githubRepository, 43).orElseThrow();
        await().atMost(Duration.ofSeconds(8)).untilAsserted(() -> {
            PullRequest refreshed = pullRequestRepository.findById(unmatchedPullRequest.getId()).orElseThrow();
            assertThat(refreshed.getReviewStatus()).isEqualTo("COMPLETED");
        });
        assertThat(riskAssessmentRepository.findByPullRequestIdOrderByComputedAtDesc(unmatchedPullRequest.getId())
                .get(0).getDataQualityNotes().toString()).contains("NO_AFFECTED_ENDPOINTS");
        mockMvc.perform(get("/api/projects/{projectId}/pull-requests/{pullRequestId}", projectId, unmatchedPullRequest.getId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.affectedEndpoints").isEmpty())
                .andExpect(jsonPath("$.riskHistory[0].tier").value("INSUFFICIENT_DATA"))
                .andExpect(jsonPath("$.riskHistory[0].dataQualityNotes[0]").value("NO_AFFECTED_ENDPOINTS"));

        String noFilesPayload = """
                {
                  "action":"opened",
                  "repository":{"full_name":"%s"},
                  "pull_request":{"number":44,"title":"Changed files unavailable","user":{"login":"reviewer"},
                    "state":"open","created_at":"2026-10-01T08:00:00Z","head":{"sha":"no-files-sha"}}
                }
                """.formatted(repositoryName);
        mockMvc.perform(post("/api/webhooks/github")
                        .header("X-GitHub-Event", "pull_request")
                        .header("X-GitHub-Delivery", "review-no-files-" + suffix)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(noFilesPayload))
                .andExpect(status().isAccepted());
        PullRequest noFilesPullRequest = pullRequestRepository
                .findByRepositoryAndGithubPrNumber(githubRepository, 44).orElseThrow();
        assertThat(riskAssessmentRepository.findByPullRequestIdOrderByComputedAtDesc(noFilesPullRequest.getId()))
                .anySatisfy(assessment -> assertThat(assessment.getEvaluationStatus()).isEqualTo("INSUFFICIENT_DATA"));
    }
}