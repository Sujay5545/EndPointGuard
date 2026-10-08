package com.endpointguard;

import com.endpointguard.common.time.UtcDateTime;
import com.endpointguard.endpoint.domain.Endpoint;
import com.endpointguard.endpoint.repository.EndpointRepository;
import com.endpointguard.github.GithubRepositoryMetadata;
import com.endpointguard.github.GithubRestApiClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.endpointguard.praffected.domain.PrAffectedEndpoint;
import com.endpointguard.praffected.repository.PrAffectedEndpointRepository;
import com.endpointguard.pullrequest.domain.PrChangedFile;
import com.endpointguard.pullrequest.domain.PullRequest;
import com.endpointguard.pullrequest.repository.PrChangedFileRepository;
import com.endpointguard.pullrequest.repository.PullRequestRepository;
import com.endpointguard.repository.domain.GithubRepository;
import com.endpointguard.repository.repository.GithubRepositoryRepository;
import com.endpointguard.risk.domain.RiskAssessment;
import com.endpointguard.risk.repository.RiskAssessmentRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@ContextConfiguration(classes = PhaseFivePullRequestApiTests.TestGithubClientConfig.class)
class PhaseFivePullRequestApiTests extends RollbackIntegrationTest {

    @TestConfiguration
    static class TestGithubClientConfig {
        @Bean
        @Primary
        GithubRestApiClient githubRestApiClient() {
            GithubRestApiClient client = mock(GithubRestApiClient.class);
            when(client.validateRepository("acme/checkouts-api")).thenReturn(new GithubRepositoryMetadata(
                    "acme/checkouts-api",
                    "acme",
                    "checkouts-api",
                    "Checkouts API",
                    false,
                    "main"
            ));
            return client;
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PullRequestRepository pullRequestRepository;

    @Autowired
    private PrChangedFileRepository prChangedFileRepository;

    @Autowired
    private PrAffectedEndpointRepository prAffectedEndpointRepository;

    @Autowired
    private RiskAssessmentRepository riskAssessmentRepository;

    @Autowired
    private GithubRepositoryRepository githubRepositoryRepository;

    @Autowired
    private EndpointRepository endpointRepository;

    @Test
    void projectCanListAndDetailPullRequests() throws Exception {
        String tokenResponse = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"phase5@example.com\",\"password\":\"password123\"}"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String token = objectMapper.readTree(tokenResponse).get("token").asText();

        String projectResponse = mockMvc.perform(post("/api/projects")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Checkout API\"}"))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        long projectId = objectMapper.readTree(projectResponse).get("id").asLong();

        String repositoryResponse = mockMvc.perform(post("/api/projects/" + projectId + "/repositories")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"githubRepoFullName\":\"acme/checkouts-api\",\"webhookSecretRef\":\"CHECKOUTS_WEBHOOK_SECRET\"}"))
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
                                  "pathPattern": "/api/checkout",
                                  "criticality": "HIGH",
                                  "sourcePatterns": ["**/CheckoutController.java"]
                                }
                                """))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        long endpointId = objectMapper.readTree(endpointResponse).get("id").asLong();

        GithubRepository repository = githubRepositoryRepository.findById(repositoryId).orElseThrow();
        Endpoint endpoint = endpointRepository.findById(endpointId).orElseThrow();

        PullRequest pullRequest = PullRequest.builder()
                .repository(repository)
                .githubPrNumber(42)
                .title("Checkout flow update")
                .author("octocat")
                .status("OPEN")
                .openedAt(UtcDateTime.now())
                .headSha("abc123")
                .build();
        pullRequest = pullRequestRepository.save(pullRequest);

        prChangedFileRepository.save(PrChangedFile.builder()
                .pullRequest(pullRequest)
                .filePath("src/main/java/com/acme/CheckoutController.java")
                .additions(18)
                .deletions(2)
                .patch("@@ -1,1 +1,2 @@\n-old route\n+new route")
                .build());

        prAffectedEndpointRepository.save(PrAffectedEndpoint.builder()
                .pullRequest(pullRequest)
                .endpoint(endpoint)
                .build());

        riskAssessmentRepository.save(RiskAssessment.builder()
                .pullRequestId(pullRequest.getId())
                .score(0.84)
                .tier("HIGH")
                .computedAt(UtcDateTime.now())
                .factorBreakdown(objectMapper.createObjectNode().put("TRAFFIC_VOLUME", 0.4).put("ERROR_RATE_TREND", 0.3))
                .build());
                                pullRequest.setReviewResult("""
                                                                {
                                                                        "decision": "HIGH",
                                                                        "score": 0.0,
                                                                        "summary": "Structured AI score must remain authoritative.",
                                                                        "findings": [],
                                                                        "provider": "groq",
                                                                        "fallback": false,
                                                                        "analysis": {
                                                                                "overallRisk": {"level": "HIGH", "score": 80.0, "confidence": 0.9, "reason": "Test"}
                                                                        }
                                                                }
                                                                """);
                                pullRequestRepository.save(pullRequest);

        mockMvc.perform(get("/api/projects/{projectId}/pull-requests", projectId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].githubPrNumber").value(42))
                .andExpect(jsonPath("$.content[0].title").value("Checkout flow update"))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.totalPages").value(1));

        mockMvc.perform(get("/api/projects/{projectId}/pull-requests", projectId)
                        .param("page", "0")
                        .param("size", "1")
                        .param("status", "OPEN")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].githubPrNumber").value(42))
                .andExpect(jsonPath("$.size").value(1));

        mockMvc.perform(get("/api/projects/{projectId}/pull-requests", projectId)
                        .param("status", "INVALID")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest());

        mockMvc.perform(get("/api/projects/{projectId}/pull-requests/{pullRequestId}", projectId, pullRequest.getId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.githubPrNumber").value(42))
                .andExpect(jsonPath("$.changedFiles[0].filePath").value("src/main/java/com/acme/CheckoutController.java"))
                .andExpect(jsonPath("$.changedFiles[0].patch").value(org.hamcrest.Matchers.containsString("new route")))
                .andExpect(jsonPath("$.affectedEndpoints[0].pathPattern").value("/api/checkout"))
                .andExpect(jsonPath("$.riskHistory[0].tier").value("HIGH"))
                .andExpect(jsonPath("$.riskHistory[0].score").value(0.84))
                .andExpect(jsonPath("$.review.analysis.overallRisk.score").value(80.0))
                .andExpect(jsonPath("$.finalPrRiskScore").value(0.808));
    }
}
