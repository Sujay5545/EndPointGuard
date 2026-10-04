package com.endpointguard;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.endpointguard.endpoint.repository.EndpointRepository;
import com.endpointguard.praffected.repository.PrAffectedEndpointRepository;
import com.endpointguard.pullrequest.repository.PullRequestRepository;
import com.endpointguard.repository.repository.GithubRepositoryRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PhaseFiveRiskApiTests extends RollbackIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

        @Autowired
        private GithubRepositoryRepository githubRepositoryRepository;

        @Autowired
        private PullRequestRepository pullRequestRepository;

        @Autowired
        private EndpointRepository endpointRepository;

        @Autowired
        private PrAffectedEndpointRepository prAffectedEndpointRepository;

    @Test
        void endpointRiskEvaluationIgnoresCallerSuppliedMetricFactors() throws Exception {
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
                        .content("{\"name\":\"Risk API\"}"))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        long projectId = objectMapper.readTree(projectResponse).get("id").asLong();

        String repositoryResponse = mockMvc.perform(post("/api/projects/" + projectId + "/repositories")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"githubRepoFullName\":\"acme/risk-api\",\"webhookSecretRef\":\"RISK_SECRET\"}"))
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

        long pullRequestId = PhaseOneTestData.createLinkedPullRequest(42, repositoryId, endpointId,
                githubRepositoryRepository, pullRequestRepository, endpointRepository, prAffectedEndpointRepository);

        mockMvc.perform(post("/api/endpoints/" + endpointId + "/risk/evaluate")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                                                                                                .content("""
                                {
                                                                                                                                        "pullRequestId": %d,
                                  "diffSize": 1400,
                                  "incidentCount": 8,
                                  "requestCount": 1800,
                                  "rateLimitUtilization": 72.0,
                                  "recentErrorRate": 0.18,
                                  "baselineErrorRate": 0.04,
                                  "recentLatencyMs": 520.0,
                                  "baselineLatencyMs": 210.0
                                }
                                """.formatted(pullRequestId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tier").value("INSUFFICIENT_DATA"))
                .andExpect(jsonPath("$.score").value(0.0))
                .andExpect(jsonPath("$.evaluationStatus").value("INSUFFICIENT_DATA"))
                .andExpect(jsonPath("$.score").isNumber())
                .andExpect(jsonPath("$.factorBreakdown.TRAFFIC_VOLUME").value(0.0))
                .andExpect(jsonPath("$.factorBreakdown.ERROR_RATE_TREND").value(0.0))
                .andExpect(jsonPath("$.factorBreakdown.DIFF_SIZE").value(0.0));
    }
}
