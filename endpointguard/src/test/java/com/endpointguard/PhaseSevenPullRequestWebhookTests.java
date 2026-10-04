package com.endpointguard;

import com.endpointguard.pullrequest.repository.PullRequestRepository;
import java.time.LocalDateTime;
import com.endpointguard.pullrequest.repository.PrChangedFileRepository;
import com.endpointguard.risk.repository.RiskAssessmentRepository;
import com.endpointguard.pullrequest.repository.WebhookEventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PhaseSevenPullRequestWebhookTests extends RollbackIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PullRequestRepository pullRequestRepository;

    @Autowired
    private PrChangedFileRepository prChangedFileRepository;

        @Autowired
        private RiskAssessmentRepository riskAssessmentRepository;

        @Autowired
        private WebhookEventRepository webhookEventRepository;

    @Test
    void githubWebhookStoresPullRequestAndChangedFiles() throws Exception {
        String tokenResponse = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"phase7@example.com\",\"password\":\"password123\"}"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String token = objectMapper.readTree(tokenResponse).get("token").asText();

        String projectResponse = mockMvc.perform(post("/api/projects")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"PR Webhooks\"}"))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        long projectId = objectMapper.readTree(projectResponse).get("id").asLong();

        String repositoryResponse = mockMvc.perform(post("/api/projects/" + projectId + "/repositories")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"githubRepoFullName\":\"acme/webhook-api\",\"webhookSecretRef\":\"WH_SECRET\"}"))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        long repositoryId = objectMapper.readTree(repositoryResponse).get("id").asLong();

        String endpointResponse = mockMvc.perform(post("/api/repositories/" + repositoryId + "/endpoints")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "method": "GET",
                                  "pathPattern": "/api/orders",
                                  "criticality": "HIGH",
                                  "sourcePatterns": ["**/OrderController.java"]
                                }
                                """))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        objectMapper.readTree(endpointResponse).get("id").asLong();

        String payload = """
                {
                  "action": "opened",
                  "repository": {
                    "full_name": "acme/webhook-api"
                  },
                  "pull_request": {
                    "number": 14,
                    "title": "Add orders endpoint",
                    "user": { "login": "alice" },
                    "state": "open",
                    "created_at": "2026-09-26T10:00:00Z",
                    "merged_at": null,
                    "closed_at": null,
                    "head": { "sha": "abc123" },
                    "changed_files": 2,
                    "additions": 44,
                    "deletions": 10
                  },
                  "files": [
                    { "filename": "src/main/java/com/acme/OrderController.java", "additions": 20, "deletions": 5, "patch": "patch contains new route" },
                    { "filename": "src/main/resources/application.yml", "additions": 2, "deletions": 1 }
                  ]
                }
                """;

        mockMvc.perform(post("/api/webhooks/github")
                        .header("X-GitHub-Event", "pull_request")
                        .header("X-GitHub-Delivery", "phase7-delivery-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isAccepted());

        assertThat(pullRequestRepository.findAll()).anyMatch(pr ->
                pr.getGithubPrNumber().equals(14) && pr.getRepository().getGithubRepoFullName().equals("acme/webhook-api"));
        assertThat(pullRequestRepository.findAll()).anyMatch(pr ->
                pr.getGithubPrNumber().equals(14)
                        && pr.getOpenedAt().equals(LocalDateTime.parse("2026-09-26T10:00:00")));
        assertThat(prChangedFileRepository.findAll()).anyMatch(file ->
                file.getFilePath().contains("OrderController.java"));
        assertThat(prChangedFileRepository.findAll()).anyMatch(file ->
                file.getPatch() != null && file.getPatch().contains("new route"));
        assertThat(riskAssessmentRepository.findAll()).anyMatch(assessment ->
                "INSUFFICIENT_DATA".equals(assessment.getEvaluationStatus()));
        assertThat(webhookEventRepository.findByGithubDeliveryId("phase7-delivery-001"))
                .hasValueSatisfying(event -> org.assertj.core.api.Assertions.assertThat(event.getProcessingStatus()).isEqualTo("PROCESSED"));

        Long pullRequestId = pullRequestRepository.findAll().stream()
                .filter(pullRequest -> pullRequest.getGithubPrNumber().equals(14))
                .findFirst().orElseThrow().getId();
        long assessmentCountBeforeReplay = riskAssessmentRepository
                .findByPullRequestIdOrderByComputedAtDesc(pullRequestId).size();
        mockMvc.perform(post("/api/webhooks/github")
                        .header("X-GitHub-Event", "pull_request")
                        .header("X-GitHub-Delivery", "phase7-delivery-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isAccepted());
        assertThat(riskAssessmentRepository.findByPullRequestIdOrderByComputedAtDesc(pullRequestId))
                .hasSize((int) assessmentCountBeforeReplay);
    }
}
