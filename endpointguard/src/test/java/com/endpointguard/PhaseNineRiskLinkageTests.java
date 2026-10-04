package com.endpointguard;

import com.endpointguard.praffected.service.PrAffectedEndpointService;
import com.endpointguard.pullrequest.repository.PullRequestRepository;
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
class PhaseNineRiskLinkageTests extends RollbackIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PullRequestRepository pullRequestRepository;

    @Autowired
    private PrAffectedEndpointService prAffectedEndpointService;

    @Test
    void affectedEndpointsAreResolvableForRiskEvaluation() throws Exception {
        String tokenResponse = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"phase9@example.com\",\"password\":\"password123\"}"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String token = objectMapper.readTree(tokenResponse).get("token").asText();

        String projectResponse = mockMvc.perform(post("/api/projects")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Risk Linkage\"}"))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        long projectId = objectMapper.readTree(projectResponse).get("id").asLong();

        String repositoryResponse = mockMvc.perform(post("/api/projects/" + projectId + "/repositories")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"githubRepoFullName\":\"acme/risk-linkage\",\"webhookSecretRef\":\"LINKAGE_SECRET\"}"))
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
        long endpointId = objectMapper.readTree(endpointResponse).get("id").asLong();

        String payload = """
                {
                  "action": "opened",
                  "repository": {
                    "full_name": "acme/risk-linkage"
                  },
                  "pull_request": {
                    "number": 42,
                    "title": "Order risk linkage",
                    "user": { "login": "bob" },
                    "state": "open",
                    "created_at": "2026-09-26T11:00:00Z",
                    "head": { "sha": "def456" }
                  },
                  "files": [
                    { "filename": "src/main/java/com/acme/OrderController.java", "additions": 80, "deletions": 12 }
                  ]
                }
                """;

        mockMvc.perform(post("/api/webhooks/github")
                        .header("X-GitHub-Event", "pull_request")
                        .header("X-GitHub-Delivery", "phase9-delivery-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isAccepted());

        var pullRequest = pullRequestRepository.findAll().stream()
                .filter(pr -> pr.getGithubPrNumber().equals(42)
                        && pr.getRepository().getGithubRepoFullName().equals("acme/risk-linkage"))
                .findFirst()
                .orElseThrow();

        assertThat(prAffectedEndpointService.findAffectedEndpointsForPullRequest(pullRequest))
                .extracting("id")
                .contains(endpointId);

        String updatedPayload = """
                {
                  "action": "synchronize",
                  "repository": {
                    "full_name": "acme/risk-linkage"
                  },
                  "pull_request": {
                    "number": 42,
                    "title": "Order risk linkage",
                    "user": { "login": "bob" },
                    "state": "open",
                    "created_at": "2026-09-26T11:00:00Z",
                    "head": { "sha": "new-sha" }
                  },
                  "files": [
                    { "filename": "README.md", "additions": 1, "deletions": 0 }
                  ]
                }
                """;

        mockMvc.perform(post("/api/webhooks/github")
                        .header("X-GitHub-Event", "pull_request")
                        .header("X-GitHub-Delivery", "phase9-delivery-002")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updatedPayload))
                .andExpect(status().isAccepted());

        assertThat(prAffectedEndpointService.findAffectedEndpointsForPullRequest(pullRequest)).isEmpty();
    }
}
